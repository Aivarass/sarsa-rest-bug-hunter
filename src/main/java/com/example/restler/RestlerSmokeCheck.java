package com.example.restler;

import com.example.harness.BugOracle;
import com.example.harness.HttpResult;
import com.example.harness.HttpTransport;
import com.example.harness.RunRecorder;
import com.example.sarsa.strategy.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-flight check: can this configuration actually exercise the API?
 *
 * Every invariant in {@link RestlerSelfCheck} can pass while a configuration is quietly
 * incapable of building the dependency chain at all - and a configuration that cannot
 * build the chain scores zero on both planted defects for a reason that has nothing to
 * do with search strategy. That failure mode is invisible in the results table: it looks
 * exactly like "RESTler didn't find it".
 *
 * It is not hypothetical. Three separate dictionary-construction bugs produced it during
 * this work: a field-major walk that made the matched dictionary all-positive, a shared
 * cursor that put {@code null} at index 0 of {@code quantity} so every
 * {@code POST /items} returned 500, and a per-leaf cursor that still left index 0
 * arbitrary for {@code PUT} and {@code PATCH}.
 *
 * So every configuration is run briefly against a live service before it enters the
 * matrix. Exactly one property is **gated**:
 *
 *  - **Chain buildable** - Items, Prices, Discounts and Points all exist in the store,
 *    i.e. all four producer-consumer edges resolved end to end. One Point is enough to
 *    prove it, so the check is budget-insensitive, and its failure is always a harness
 *    bug rather than a property of the configuration.
 *
 * Three further properties are **reported but not gated**, because they legitimately
 * differ by search strategy and budget - gating on them would reject RESTler's own
 * default mode for being slow:
 *
 *  - addressability (all 24 request types sent),
 *  - depth (the {@code DELETE /points} guard, five dependent requests deep),
 *  - poison in the store (a negative price landed).
 *
 * {@code bfs} reaches generation 5 in 20k requests where {@code bfs-fast} reaches 41;
 * that breadth-versus-depth gap is a finding for the results table, not a defect.
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.example.restler.RestlerSmokeCheck \
 *     -Dexec.args="mode=bfs-fast checkers=all dict=matched budget=20000"
 * </pre>
 */
public class RestlerSmokeCheck {

    public static void main(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (String s : args) {
            int i = s.indexOf('=');
            if (i > 0) a.put(s.substring(0, i), s.substring(i + 1));
        }
        int budget = Integer.parseInt(a.getOrDefault("budget", "20000"));
        String dict = a.getOrDefault("dict", "default");
        String checkers = a.getOrDefault("checkers", "none");
        long seed = Long.parseLong(a.getOrDefault("seed", "3001"));

        RestlerFuzzer.Config cfg = new RestlerFuzzer.Config();
        cfg.seed = seed;
        cfg.mode = switch (a.getOrDefault("mode", "bfs")) {
            case "bfs" -> RestlerFuzzer.Mode.BFS;
            case "bfs-fast" -> RestlerFuzzer.Mode.BFS_FAST;
            case "bfs-cheap" -> RestlerFuzzer.Mode.BFS_CHEAP;
            case "bfs-minimal" -> RestlerFuzzer.Mode.BFS_MINIMAL;
            case "random-walk" -> RestlerFuzzer.Mode.RANDOM_WALK;
            default -> throw new IllegalArgumentException("mode");
        };
        cfg.maxCombinations = Integer.parseInt(a.getOrDefault("maxcomb", "20"));
        cfg.checkerMaxCombinations = Integer.parseInt(a.getOrDefault("checkercomb", "100"));
        cfg.maxSequenceLength = Integer.parseInt(a.getOrDefault("maxseq", "100"));
        cfg.allowGetProducers = Boolean.parseBoolean(a.getOrDefault("getproducers", "false"));
        cfg.createPrefixOnce = Boolean.parseBoolean(a.getOrDefault("prefixonce", "false"));
        cfg.grammar = Boolean.parseBoolean(a.getOrDefault("deleteall", "false"))
                ? ApiGrammar.withDeleteAll() : ApiGrammar.matched();
        cfg.dictionary = switch (dict) {
            case "default" -> DefaultDictionary.restlerDefaults();
            case "negatives" -> DefaultDictionary.withNegatives();
            case "matched" -> new MatchedValueSource(seed);
            case "matched-negfirst" -> new MatchedValueSource(seed, MatchedValueSource.Order.NEGATIVE_FIRST);
            case "matched-negfirst-pe" -> new MatchedValueSource(seed, MatchedValueSource.Order.PER_ENDPOINT);
            // Whole-body mode: the dictionary is unused, bodySource takes over.
            case "matched-body" -> DefaultDictionary.restlerDefaults();
            default -> throw new IllegalArgumentException("dict");
        };
        if (dict.equals("matched-body")) cfg.bodySource = new MatchedBodySource(seed);
        cfg.invalidValueChecker = checkers.equals("invalid") || checkers.equals("all");
        cfg.payloadBodyChecker = checkers.equals("body") || checkers.equals("all");
        String cvals = a.getOrDefault("checkervals", dict.startsWith("matched") ? "matched" : "restler");
        cfg.checkerValues = cvals.equals("matched")
                ? new MatchedValueSource(seed + 1, orderOf(dict)) : new RestlerValueGenerator(seed);
        // No windowed reset: the point is to inspect everything the run built.
        cfg.resetEveryRequests = 0;

        HttpTransport http = new HttpTransport(
                System.getProperty("sarsa.baseUrl", "http://localhost:8080/api/"), budget);
        RunRecorder rec = new RunRecorder(http);
        RestlerFuzzer fuzzer = new RestlerFuzzer(cfg, http, rec);
        fuzzer.run();

        boolean negativesInVocabulary = !dict.equals("default") || cfg.invalidValueChecker;

        // 1. Addressability.
        List<String> missing = new ArrayList<>();
        for (RequestType r : cfg.grammar) {
            boolean seen = rec.executedPaths().stream()
                    .anyMatch(s -> s.startsWith(r.method + "+" + r.endpoint.name()));
            if (!seen) missing.add(r.key());
        }

        // 2. Chain buildable - read the store, which is a response any client gets.
        Map<Endpoint, Integer> counts = new LinkedHashMap<>();
        for (Endpoint e : Endpoint.values()) counts.put(e, countOf(http, e.name().toLowerCase()));

        // 3. Depth reachable.
        boolean guard = rec.firstSeen().containsKey("DELETE+POINTS+GUARD");

        // 4. Poison expressible.
        int negPrices = negativeCount(http, "prices", "price");
        int negDiscounts = negativeCount(http, "discounts", "discount");

        String cfgLabel = String.format("mode=%s dict=%s checkers=%s maxcomb=%d",
                a.getOrDefault("mode", "bfs"), dict, checkers, cfg.maxCombinations);
        System.out.println("\n" + "=".repeat(78));
        System.out.println("SMOKE  " + cfgLabel);
        System.out.println("=".repeat(78));
        System.out.printf("  requests %,d   renderings %,d   maxgen %d   valid seqs %,d%n",
                http.fuzzRequests(), fuzzer.renderings(), fuzzer.maxGeneration(),
                fuzzer.validSequences());
        System.out.printf("  store: items %d, prices %d, discounts %d, points %d%n",
                counts.get(Endpoint.ITEMS), counts.get(Endpoint.PRICES),
                counts.get(Endpoint.DISCOUNTS), counts.get(Endpoint.POINTS));
        System.out.printf("  negative in store: prices %d, discounts %d%n", negPrices, negDiscounts);
        System.out.printf("  shapes %d, signatures %d%n",
                rec.executedPaths().size(), rec.firesBySignature().size());

        // ---- Hard gate: structural capability only.
        //
        // Only "the chain resolves end to end" is asserted, because that is the property
        // whose failure is a *bug in the harness* rather than a property of the
        // configuration. It is also budget-insensitive: one Point in the store is enough
        // to prove the four producer-consumer edges all resolve.
        //
        // Addressability, depth and poison-in-store are reported but NOT gated: they
        // legitimately differ by search strategy and budget. bfs reaches only generation
        // 5 in 20k requests while bfs-fast reaches 41 - that is a finding about breadth
        // versus depth, not a broken configuration, and gating on it would have thrown
        // away RESTler's default mode for being slow.
        int failures = 0;
        boolean chain = counts.get(Endpoint.ITEMS) > 0 && counts.get(Endpoint.PRICES) > 0
                && counts.get(Endpoint.DISCOUNTS) > 0 && counts.get(Endpoint.POINTS) > 0;
        failures += report("GATE dependency chain built end to end (Item->Price->Discount->Point)",
                chain, "store=" + counts);

        System.out.println("  --- informational (strategy- and budget-dependent, not gated) ---");
        report("all 24 request types addressed", missing.isEmpty(), "missing: " + missing);
        report("depth 5 reached (DELETE /points guard hit)", guard, "not at this budget");
        if (negativesInVocabulary) {
            report("negative price reached the store", negPrices > 0,
                    "vocabulary contains negatives but none landed at this budget");
        } else {
            System.out.println("  EXPECTED  no negative price - RESTler's default dictionary "
                    + "has no negative value, so this configuration cannot arm either defect");
        }

        System.out.println("=".repeat(78));
        System.out.printf("SMOKE %s  (%s)%n", failures == 0 ? "PASS" : "FAIL", cfgLabel);
        if (failures > 0) System.exit(1);
    }

    /** Dictionary ordering implied by the dict name, for the checker's own dictionary. */
    private static MatchedValueSource.Order orderOf(String dict) {
        return switch (dict) {
            case "matched-negfirst" -> MatchedValueSource.Order.NEGATIVE_FIRST;
            case "matched-negfirst-pe" -> MatchedValueSource.Order.PER_ENDPOINT;
            default -> MatchedValueSource.Order.VALID_FIRST;
        };
    }

    private static int report(String name, boolean ok, String detail) {
        System.out.printf("  %-8s %s%s%n", ok ? "PASS" : "FAIL", name,
                ok || detail.isEmpty() ? "" : "  <- " + detail);
        return ok ? 0 : 1;
    }

    private static int countOf(HttpTransport http, String collection) {
        HttpResult r = http.harness("GET", collection, null);
        if (r == null || r.status() != 200) return -1;
        try {
            JsonNode arr = BugOracle.mapper().readTree(r.body());
            return arr.isArray() ? arr.size() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int negativeCount(HttpTransport http, String collection, String field) {
        HttpResult r = http.harness("GET", collection, null);
        if (r == null || r.status() != 200) return -1;
        try {
            JsonNode arr = BugOracle.mapper().readTree(r.body());
            int n = 0;
            for (JsonNode o : arr) if (o.path(field).asDouble(0) < 0) n++;
            return n;
        } catch (Exception e) {
            return -1;
        }
    }
}
