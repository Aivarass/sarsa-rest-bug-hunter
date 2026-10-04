package com.example.restler;

import com.example.harness.BugOracle;
import com.example.harness.HttpResult;
import com.example.harness.HttpTransport;
import com.example.harness.RunRecorder;
import com.example.sarsa.strategy.Endpoint;

import java.util.*;

/**
 * Invariant suite for the RESTler arm.
 *
 * Two jobs. The first is ordinary: catch bugs in the port. The second matters more -
 * pin the facts the comparison rests on, so that if one of them ever changes, a test
 * fails instead of a conclusion quietly becoming wrong. The load-bearing ones are:
 *
 *  - the grammar's addressable surface equals the RL arm's (24 shapes, 6 methods x 4
 *    endpoints), so neither arm can reach API surface the other cannot;
 *  - RESTler's default dictionary contains **no negative value**, which is why the
 *    out-of-the-box configurations cannot arm either defect;
 *  - RESTler's own invalid-value generator **does** produce negatives, which is why the
 *    checker-enabled configurations can;
 *  - the vocabulary-matched source draws only values the RL generator can produce;
 *  - every request, checkers included, is charged to the budget.
 *
 * Runs with no service up: it asserts on structure and on value sources, and the one
 * end-to-end check deliberately points at a dead port.
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=com.example.restler.RestlerSelfCheck</pre>
 */
public class RestlerSelfCheck {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("RESTler arm invariant suite");
        System.out.println("=".repeat(64));

        grammarMatchesRlSurface();
        grammarHasNoCollectionDelete();
        dependencyEdgesFormTheChain();
        onlyIndependentRequestsStartASequence();
        bfsKeepsEverySequence();
        bfsFastKeepsOnePerRequestType();
        randomWalkKeepsExactlyOne();
        validityIsTwoXxOnly();

        defaultDictionaryHasNoNegative();
        negativeDictionaryHasOne();
        restlerGeneratorProducesNegatives();
        restlerGeneratorReproducesBoundaryValues();
        matchedSourceDrawsOnlyRlValues();
        matchedSourceOffersANegativePrice();
        matchedSourceOffersNegativesWithinMaxCombinations();
        matchedSourceLabelsLandInRlShapeSpace();
        matchedShapeKeysStayInsideTheRlShapeSpace();
        everyLeafStartsWithAValidValue();
        combinationZeroIsFullyValidForEveryRequestType();

        combinationsAreCappedAtMaxCombinations();
        budgetIsNeverExceeded();
        checkerRequestsAreCharged();
        oracleIsSharedWithTheRlArm();

        System.out.println("=".repeat(64));
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  PASS  %s%n", name);
        } else {
            failed++;
            System.out.printf("  FAIL  %s%n        %s%n", name, detail);
        }
    }

    // ==================== Grammar ====================

    private static void grammarMatchesRlSurface() {
        List<RequestType> g = ApiGrammar.matched();
        Set<String> methods = new TreeSet<>();
        Set<Endpoint> endpoints = EnumSet.noneOf(Endpoint.class);
        for (RequestType r : g) {
            methods.add(r.method);
            endpoints.add(r.endpoint);
        }
        check("Grammar is exactly the RL arm's surface (24 = 6 methods x 4 endpoints)",
                g.size() == 24 && methods.size() == 6 && endpoints.size() == 4
                        && methods.equals(new TreeSet<>(List.of(
                        "GET", "GET_ALL", "POST", "PUT", "PATCH", "DELETE"))),
                "size=" + g.size() + " methods=" + methods + " endpoints=" + endpoints);
    }

    private static void grammarHasNoCollectionDelete() {
        boolean cleanDefault = ApiGrammar.matched().stream()
                .noneMatch(r -> r.method.equals("DELETE_ALL"));
        boolean optInWorks = ApiGrammar.withDeleteAll().stream()
                .filter(r -> r.method.equals("DELETE_ALL")).count() == 4;
        check("Collection DELETE is excluded by default and available on request",
                cleanDefault && optInWorks, "default-clean=" + cleanDefault + " opt-in=" + optInWorks);
    }

    private static void dependencyEdgesFormTheChain() {
        Map<String, RequestType> byKey = new HashMap<>();
        for (RequestType r : ApiGrammar.matched()) byKey.put(r.method + " " + r.endpoint, r);

        boolean ok =
                byKey.get("POST ITEMS").consumes().isEmpty()
                && byKey.get("POST PRICES").consumes().equals(Set.of("items.id"))
                && byKey.get("POST DISCOUNTS").consumes().equals(Set.of("prices.id"))
                && byKey.get("POST POINTS").consumes().equals(Set.of("discounts.id"))
                && byKey.get("PATCH PRICES").consumes().equals(Set.of("prices.id", "items.id"))
                && byKey.get("DELETE POINTS").consumes().equals(Set.of("points.id"))
                && "items.id".equals(byKey.get("POST ITEMS").produces())
                && byKey.get("GET_ALL ITEMS").produces() == null
                && "items.id".equals(byKey.get("GET_ALL ITEMS").producesWithGet());
        check("Producer-consumer edges reproduce the Item->Price->Discount->Point chain",
                ok, "POST PRICES consumes=" + byKey.get("POST PRICES").consumes()
                        + " POST POINTS consumes=" + byKey.get("POST POINTS").consumes());
    }

    private static void onlyIndependentRequestsStartASequence() {
        // From the empty sequence only requests with no consumers are satisfiable:
        // the four GET-alls and POST /items. Everything else needs an id first, which is
        // exactly the dependency structure the RL arm has to discover for itself.
        long startable = ApiGrammar.matched().stream()
                .filter(r -> Sequence.empty().satisfies(r, false))
                .count();
        check("Only the 5 dependency-free requests can open a sequence",
                startable == 5, "startable=" + startable);
    }

    // ==================== Search strategies ====================

    /** Renders nothing; just exercises extend() through a fuzzer with a zero budget. */
    private static List<Sequence> extendOnce(RestlerFuzzer.Mode mode, List<Sequence> pool) {
        RestlerFuzzer.Config cfg = new RestlerFuzzer.Config();
        cfg.mode = mode;
        HttpTransport http = new HttpTransport("http://localhost:1/api/", 1);
        RestlerFuzzer f = new RestlerFuzzer(cfg, http, new RunRecorder(http));
        return f.extendForTest(pool);
    }

    private static void bfsKeepsEverySequence() {
        // Two sequences that both produce items.id: every request consuming it should be
        // appended to BOTH, so bfs's pool grows multiplicatively.
        RequestType postItems = findRequest("POST", Endpoint.ITEMS);
        List<Sequence> pool = List.of(
                Sequence.empty().plus(postItems),
                Sequence.empty().plus(postItems));
        List<Sequence> out = extendOnce(RestlerFuzzer.Mode.BFS, pool);
        long perType = out.stream().filter(s -> s.last().method.equals("POST")
                && s.last().endpoint == Endpoint.PRICES).count();
        check("bfs extends every sequence by every satisfiable request", perType == 2,
                "POST /prices appended " + perType + " times (expected 2)");
    }

    private static void bfsFastKeepsOnePerRequestType() {
        RequestType postItems = findRequest("POST", Endpoint.ITEMS);
        List<Sequence> pool = List.of(
                Sequence.empty().plus(postItems),
                Sequence.empty().plus(postItems));
        List<Sequence> out = extendOnce(RestlerFuzzer.Mode.BFS_FAST, pool);
        Map<String, Integer> perType = new HashMap<>();
        for (Sequence s : out) perType.merge(s.last().key(), 1, Integer::sum);
        boolean allOnes = perType.values().stream().allMatch(v -> v == 1);
        check("bfs-fast appends each request type to exactly one sequence",
                allOnes && !perType.isEmpty(), "counts=" + perType);
    }

    private static void randomWalkKeepsExactlyOne() {
        RequestType postItems = findRequest("POST", Endpoint.ITEMS);
        List<Sequence> out = extendOnce(RestlerFuzzer.Mode.RANDOM_WALK,
                List.of(Sequence.empty().plus(postItems)));
        check("random-walk keeps exactly one extended sequence", out.size() == 1,
                "size=" + out.size());
    }

    private static void validityIsTwoXxOnly() {
        // response.py:8 VALID_CODES = {200,201,202,204,304}; 5xx is a bug, 4xx is just
        // an invalid rendering. Asserted here because the whole search is built on it.
        boolean ok = RestlerFuzzer.isValidCodeForTest(new HttpResult(200, ""))
                && RestlerFuzzer.isValidCodeForTest(new HttpResult(201, ""))
                && RestlerFuzzer.isValidCodeForTest(new HttpResult(204, ""))
                && RestlerFuzzer.isValidCodeForTest(new HttpResult(304, ""))
                && !RestlerFuzzer.isValidCodeForTest(new HttpResult(400, ""))
                && !RestlerFuzzer.isValidCodeForTest(new HttpResult(404, ""))
                && !RestlerFuzzer.isValidCodeForTest(new HttpResult(500, ""));
        check("Sequence validity is 2xx/304 only, never 4xx or 5xx", ok, "");
    }

    private static RequestType findRequest(String method, Endpoint e) {
        return ApiGrammar.matched().stream()
                .filter(r -> r.method.equals(method) && r.endpoint == e)
                .findFirst().orElseThrow();
    }

    // ==================== Value sources ====================

    private static final RequestType.Block PRICE_BLOCK =
            new RequestType.Block("price", RequestType.Primitive.NUMBER);
    private static final RequestType.Block QTY_BLOCK =
            new RequestType.Block("quantity", RequestType.Primitive.INT);
    private static final RequestType.Block NAME_BLOCK =
            new RequestType.Block("name", RequestType.Primitive.STRING);

    private static void defaultDictionaryHasNoNegative() {
        // THE load-bearing fact: Grammar.fs:537 / Dictionary.fs:226 give one value per
        // primitive and none is negative, so the main driver cannot arm either defect.
        ValueSource d = DefaultDictionary.restlerDefaults();
        List<CandidateValue> all = new ArrayList<>();
        all.addAll(d.values(Endpoint.PRICES, PRICE_BLOCK, 20));
        all.addAll(d.values(Endpoint.ITEMS, QTY_BLOCK, 20));
        all.addAll(d.values(Endpoint.ITEMS, NAME_BLOCK, 20));
        boolean anyNegative = all.stream().anyMatch(v -> isNegativeNumber(v.json()));
        check("RESTler's default dictionary contains no negative value",
                !anyNegative && all.size() == 3, "values=" + json(all));
    }

    private static void negativeDictionaryHasOne() {
        ValueSource d = DefaultDictionary.withNegatives();
        boolean hasNeg = d.values(Endpoint.PRICES, PRICE_BLOCK, 20).stream()
                .anyMatch(v -> isNegativeNumber(v.json()));
        check("The negatives dictionary variant does contain one", hasNeg,
                "values=" + json(d.values(Endpoint.PRICES, PRICE_BLOCK, 20)));
    }

    private static void restlerGeneratorProducesNegatives() {
        // value_gen.py:55 yields randint(-10,10) and uniform(-10000,10000). This is the
        // only source of negative numbers anywhere in the tool, so the checker-enabled
        // configurations live or die by it.
        RestlerValueGenerator g = new RestlerValueGenerator(7);
        long negatives = g.values(Endpoint.PRICES, PRICE_BLOCK, 100).stream()
                .filter(v -> isNegativeNumber(v.json())).count();
        check("RESTler's invalid-value generator does produce negative numbers",
                negatives > 0, "negatives=" + negatives + " of 100");
    }

    private static void restlerGeneratorReproducesBoundaryValues() {
        // value_gen.py:22 - the first five values are fixed, and they come first.
        List<CandidateValue> vs = new RestlerValueGenerator(7)
                .values(Endpoint.PRICES, PRICE_BLOCK, 5);
        boolean ok = vs.size() == 5
                && vs.get(0).json().length() == 10000
                && vs.get(1).json().length() == 10000
                && vs.get(2).json().isEmpty()
                && vs.get(3).json().equals("{}")
                && vs.get(4).json().equals("[]");
        check("Boundary values come first, as in get_boundary_values()", ok,
                "sizes=" + vs.stream().map(v -> v.json().length()).toList());
    }

    private static void matchedSourceDrawsOnlyRlValues() {
        // Every value must be liftable from a body the RL generator actually emits -
        // this is what makes arm C6 a like-for-like comparison rather than a new tool.
        MatchedValueSource m = new MatchedValueSource(99);
        m.setIds(1L, 1L, 1L);
        List<CandidateValue> vs = m.values(Endpoint.PRICES, PRICE_BLOCK, 40);
        boolean allParse = vs.stream().allMatch(v -> {
            try {
                BugOracle.mapper().readTree(v.json());
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        check("Matched source yields only well-formed values lifted from RL payloads",
                !vs.isEmpty() && allParse, "n=" + vs.size());
    }

    private static void matchedSourceOffersANegativePrice() {
        // If this ever fails, arm C6 cannot arm Defect 1 and the headline comparison is
        // measuring the dictionary rather than the search.
        MatchedValueSource m = new MatchedValueSource(99);
        m.setIds(1L, 1L, 1L);
        long negatives = m.values(Endpoint.PRICES, PRICE_BLOCK, 60).stream()
                .filter(v -> isNegativeNumber(v.json())).count();
        check("Matched source offers a negative price (so arm C6 can arm Defect 1)",
                negatives > 0, "negatives=" + negatives);
    }

    /**
     * The main driver only ever sees the first {@code max_combinations} values of a leaf
     * (20 by default), so a negative must appear inside that window or arm C6 cannot arm
     * Defect 1 at all - and the failure would look like a search-strategy result rather
     * than a dictionary-ordering artefact. This caught exactly that bug once; it exists
     * so it cannot come back.
     */
    private static void matchedSourceOffersNegativesWithinMaxCombinations() {
        int cap = new RestlerFuzzer.Config().maxCombinations;
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        for (Object[] probe : new Object[][]{
                {Endpoint.PRICES, new RequestType.Block("price", RequestType.Primitive.NUMBER)},
                {Endpoint.DISCOUNTS, new RequestType.Block("discount", RequestType.Primitive.NUMBER)},
                {Endpoint.ITEMS, new RequestType.Block("quantity", RequestType.Primitive.INT)},
                {Endpoint.POINTS, new RequestType.Block("points", RequestType.Primitive.INT)}}) {
            MatchedValueSource m = new MatchedValueSource(99);
            m.setIds(1L, 1L, 1L);
            long neg = m.values((Endpoint) probe[0], (RequestType.Block) probe[1], cap).stream()
                    .filter(v -> isNegativeNumber(v.json())).count();
            detail.append(((RequestType.Block) probe[1]).field()).append("=").append(neg).append(" ");
            if (neg == 0) ok = false;
        }
        check("Matched dictionary offers a negative for every numeric leaf within max_combinations",
                ok, "negatives in first " + cap + ": " + detail);
    }

    private static void matchedSourceLabelsLandInRlShapeSpace() {
        MatchedValueSource m = new MatchedValueSource(99);
        m.setIds(1L, 1L, 1L);
        List<CandidateValue> vs = m.values(Endpoint.DISCOUNTS,
                new RequestType.Block("discount", RequestType.Primitive.NUMBER), 20);
        Set<String> strategies = new HashSet<>();
        Set<String> fields = new HashSet<>();
        for (CandidateValue v : vs) {
            strategies.add(v.strategyLabel());
            fields.add(v.fieldLabel());
        }
        boolean validStrategies = strategies.stream().allMatch(s -> {
            try {
                com.example.sarsa.strategy.Strategy.valueOf(s);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        boolean validFields = fields.stream().allMatch(f -> {
            try {
                com.example.sarsa.strategy.Field.valueOf(f);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        check("Matched-source shape labels are RL strategy/field names",
                validStrategies && validFields && !vs.isEmpty(),
                "strategies=" + strategies + " fields=" + fields);
    }

    /**
     * Every shape key the vocabulary-matched arm can emit must be one the RL arm could
     * also emit, or the two arms' coverage numbers are measured against different
     * spaces and "1,068 shapes" stops meaning the same thing in both columns.
     *
     * The RL arm folds NONE into VALID/ALL before keying, so its space is
     * 3 body methods x 4 endpoints x 8 strategies x 11 fields + 3 bodyless x 4 = 1,068.
     */
    private static void matchedShapeKeysStayInsideTheRlShapeSpace() {
        Set<String> rlSpace = new HashSet<>();
        for (String method : List.of("POST", "PUT", "PATCH")) {
            for (Endpoint e : Endpoint.values()) {
                for (com.example.sarsa.strategy.Strategy st : com.example.sarsa.strategy.Strategy.values()) {
                    if (st == com.example.sarsa.strategy.Strategy.NONE) continue;
                    for (com.example.sarsa.strategy.Field fd : com.example.sarsa.strategy.Field.values()) {
                        if (fd == com.example.sarsa.strategy.Field.NONE) continue;
                        rlSpace.add(BugOracle.requestShapeKey(method, e.name(), st.name(), fd.name()));
                    }
                }
            }
        }
        for (String method : List.of("GET", "GET_ALL", "DELETE")) {
            for (Endpoint e : Endpoint.values()) {
                rlSpace.add(BugOracle.requestShapeKey(method, e.name(), "X", "Y"));
            }
        }

        List<String> escaped = new ArrayList<>();
        for (Endpoint e : Endpoint.values()) {
            MatchedValueSource m = new MatchedValueSource(5);
            m.setIds(1L, 1L, 1L);
            RequestType.Block block = switch (e) {
                case ITEMS -> new RequestType.Block("quantity", RequestType.Primitive.INT);
                case PRICES -> new RequestType.Block("price", RequestType.Primitive.NUMBER);
                case DISCOUNTS -> new RequestType.Block("discount", RequestType.Primitive.NUMBER);
                case POINTS -> new RequestType.Block("points", RequestType.Primitive.INT);
            };
            for (CandidateValue v : m.values(e, block, 200)) {
                for (String method : List.of("POST", "PUT", "PATCH")) {
                    String key = BugOracle.requestShapeKey(method, e.name(),
                            v.strategyLabel(), v.fieldLabel());
                    if (!rlSpace.contains(key)) escaped.add(key);
                }
            }
        }
        check("Matched arm's shape keys are a subset of the RL arm's 1,068-shape space",
                rlSpace.size() == BugOracle.ADDRESSABLE_SHAPES && escaped.isEmpty(),
                "rlSpace=" + rlSpace.size() + " escaped=" + escaped.stream().distinct().limit(5).toList());
    }

    /**
     * Index 0 of every leaf must be a VALID-strategy value.
     *
     * Index 0 is not an ordinary dictionary entry. When {@code max_combinations} is
     * smaller than the product of the leaves' dictionary sizes - which it is for
     * {@code /items}, 20 < 20x20x20 - the renderer freezes all but one leaf at index 0
     * in every combination. If a frozen leaf holds {@code null}, every rendering of that
     * request fails and everything downstream of it in the chain becomes unreachable.
     *
     * This failed once, with a shared cursor putting {@code null} at index 0 of
     * {@code quantity}: every POST /items 500'd, {@code items.id} was never produced,
     * and the whole vocabulary-matched arm scored zero for a reason that had nothing to
     * do with search strategy.
     */
    private static void everyLeafStartsWithAValidValue() {
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        // Ask in the same order and from the same instance the fuzzer's pool does.
        MatchedValueSource m = new MatchedValueSource(3001);
        m.setIds(1L, 1L, 1L);
        for (RequestType req : ApiGrammar.matched()) {
            if (!req.hasBody) continue;
            for (RequestType.Block b : req.blocks) {
                List<CandidateValue> vs = m.values(req.endpoint, b, 20);
                if (vs.isEmpty() || !vs.get(0).strategyLabel().equals("VALID")) {
                    ok = false;
                    detail.append(req.key()).append('/').append(b.field()).append("=")
                          .append(vs.isEmpty() ? "empty" : vs.get(0).strategyLabel()).append(' ');
                }
            }
        }
        check("Index 0 of every matched leaf is a VALID value (frozen-leaf safety)",
                ok, detail.length() == 0 ? "" : detail.toString());
    }

    /**
     * The stronger form of the same property: for every body-carrying request type,
     * combination 0 must render with a VALID value in *every* leaf - that is the
     * rendering the search depends on to build its dependency chain.
     */
    private static void combinationZeroIsFullyValidForEveryRequestType() {
        RestlerFuzzer.Config cfg = new RestlerFuzzer.Config();
        cfg.dictionary = new MatchedValueSource(3001);
        HttpTransport http = new HttpTransport("http://localhost:1/api/", 1);
        RestlerFuzzer f = new RestlerFuzzer(cfg, http, new RunRecorder(http));
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        for (RequestType req : ApiGrammar.matched()) {
            if (!req.hasBody) continue;
            for (CandidateValue v : f.combinationValuesForTest(req, 0)) {
                if (!v.strategyLabel().equals("VALID")) {
                    ok = false;
                    detail.append(req.key()).append("->").append(v.strategyLabel()).append(' ');
                }
            }
        }
        check("Combination 0 renders every leaf with a VALID value", ok, detail.toString());
    }

    // ==================== Budget and oracle ====================

    private static void combinationsAreCappedAtMaxCombinations() {
        RestlerFuzzer.Config cfg = new RestlerFuzzer.Config();
        cfg.maxCombinations = 7;
        cfg.dictionary = new MatchedValueSource(5);   // many values per leaf
        HttpTransport http = new HttpTransport("http://localhost:1/api/", 1);
        RestlerFuzzer f = new RestlerFuzzer(cfg, http, new RunRecorder(http));
        int total = f.totalCombinationsForTest(findRequest("POST", Endpoint.ITEMS));
        check("Rendering count is capped at max_combinations", total <= 7 && total > 0,
                "total=" + total + " cap=7");
    }

    private static void budgetIsNeverExceeded() {
        // The budget is the stopping rule for every arm, so it must hold exactly even
        // when a checker is mid-parameter.
        int budget = 137;
        RestlerFuzzer.Config cfg = new RestlerFuzzer.Config();
        cfg.mode = RestlerFuzzer.Mode.BFS;
        cfg.invalidValueChecker = true;
        cfg.payloadBodyChecker = true;
        cfg.resetEveryRequests = 50;
        HttpTransport http = new HttpTransport("http://localhost:1/api/", budget);
        RunRecorder rec = new RunRecorder(http);
        new RestlerFuzzer(cfg, http, rec).run();
        check("Request budget is honoured exactly", http.fuzzRequests() <= budget,
                "sent=" + http.fuzzRequests() + " budget=" + budget);
    }

    private static void checkerRequestsAreCharged() {
        // Audit item B5: a checker that fuzzes 100 values per parameter off-budget would
        // be a large hidden subsidy. Same transport, same counter, no exceptions.
        RestlerFuzzer.Config a = new RestlerFuzzer.Config();
        a.mode = RestlerFuzzer.Mode.BFS_FAST;
        HttpTransport h1 = new HttpTransport("http://localhost:1/api/", 500);
        new RestlerFuzzer(a, h1, new RunRecorder(h1)).run();

        RestlerFuzzer.Config b = new RestlerFuzzer.Config();
        b.mode = RestlerFuzzer.Mode.BFS_FAST;
        b.invalidValueChecker = true;
        HttpTransport h2 = new HttpTransport("http://localhost:1/api/", 500);
        new RestlerFuzzer(b, h2, new RunRecorder(h2)).run();

        check("Both arms stop at the same budget whether or not checkers run",
                h1.fuzzRequests() <= 500 && h2.fuzzRequests() <= 500,
                "noCheckers=" + h1.fuzzRequests() + " withCheckers=" + h2.fuzzRequests());
    }

    private static void oracleIsSharedWithTheRlArm() {
        // "Found Defect 1" must mean one thing in every column of the results table.
        HttpResult d1 = new HttpResult(500,
                "{\"exception\":\"java.lang.IllegalStateException\",\"message\":\"x\"}");
        HttpResult guard = new HttpResult(500, "");
        String sigFromRestler = BugOracle.bugSignature("DELETE", Endpoint.POINTS.name(), d1);
        boolean ok = sigFromRestler.equals("DELETE+POINTS+CRASH:IllegalStateException")
                && BugOracle.bugSignature("DELETE", Endpoint.POINTS.name(), guard)
                        .equals("DELETE+POINTS+GUARD")
                && BugOracle.requestShapeKey("DELETE", "POINTS", "ANYTHING", "ANYTHING")
                        .equals("DELETE+POINTS");
        check("Planted-defect signatures are the shared oracle's, bodyless shapes dial-free",
                ok, "sig=" + sigFromRestler);
    }

    // ==================== Helpers ====================

    private static boolean isNegativeNumber(String json) {
        try {
            return Double.parseDouble(json.replace("\"", "")) < 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String json(List<CandidateValue> vs) {
        List<String> out = new ArrayList<>();
        for (CandidateValue v : vs) {
            out.add(v.json().length() > 20 ? v.json().substring(0, 20) + "..." : v.json());
        }
        return out.toString();
    }
}
