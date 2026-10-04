package com.example.harness;

import com.example.restler.*;
import com.example.sarsa.SarsaRestTester;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One trial = one fresh API JVM + one arm run to a fixed request budget.
 *
 * Every arm goes through this runner so that all of them get the same budget rule, the
 * same reset cadence, the same oracle, and the same machine-readable RESULT line. A
 * results table assembled from these lines is comparing testers and nothing else.
 *
 * <pre>
 * usage: TrialRunner arm=&lt;name&gt; seed=N budget=N [reset=N] [k=v ...]
 *
 *   arm=sarsa      eps=0.3 alpha=&lt;-Dsarsa.alpha&gt; neurons=16 steps=35
 *   arm=random     the same agent at eps=1.0 (uniform over the valid mask)
 *   arm=restler    mode=bfs|bfs-fast|bfs-cheap|bfs-minimal|random-walk
 *                  dict=default|negatives|matched
 *                  checkers=none|invalid|body|all
 *                  maxcomb=20 checkercomb=100
 *                  getproducers=false deleteall=false
 *
 * example: TrialRunner arm=restler seed=2001 budget=250000 reset=50000 \
 *                      mode=bfs-fast checkers=all dict=default
 * </pre>
 *
 * The RESULT line reports discovery on the axis arms are compared on - {@code d1req} /
 * {@code d2req}, the count of tester-issued requests at the moment each planted defect
 * first fired, or {@code none}. {@code d1ph} / {@code d2ph} carry the arm's own progress
 * counter (episode for the RL arms, generation for RESTler) for continuity with earlier
 * runs, but they are not comparable across arms and the analysis does not use them.
 */
public class TrialRunner {

    public static void main(String[] args) {
        Map<String, String> a = parseArgs(args);
        String arm = a.getOrDefault("arm", "sarsa");
        int seed = intArg(a, "seed", 1001);
        int budget = intArg(a, "budget", 250_000);
        int reset = intArg(a, "reset", 50_000);

        long t0 = System.currentTimeMillis();
        Result r = switch (arm) {
            case "sarsa", "random" -> runSarsa(arm, a, seed, budget, reset);
            case "restler" -> runRestler(a, seed, budget, reset);
            default -> throw new IllegalArgumentException("unknown arm: " + arm);
        };
        double secs = (System.currentTimeMillis() - t0) / 1000.0;

        System.out.printf(
                "RESULT arm=%s config=%s seed=%d budget=%d req=%d "
                        + "d1req=%s d2req=%s guardreq=%s d1ph=%s d2ph=%s "
                        + "sigs=%d paths=%d shapes=%d fires=%d "
                        + "armed1=%d armed2=%d w1=%d w2=%d samples=%d "
                        + "negprice=%d negdisc=%d wnp=%d wnd=%d "
                        + "%s secs=%.0f%n",
                arm, r.config, seed, budget, r.requests,
                r.d1req, r.d2req, r.guardreq, r.d1ph, r.d2ph,
                r.signatures, r.bugPaths, r.shapes, r.fires,
                r.armed1, r.armed2, r.w1, r.w2, r.samples,
                r.negPrice, r.negDisc, r.wnp, r.wnd,
                r.extra, secs);
    }

    private record Result(String config, long requests, String d1req, String d2req,
                          String guardreq, String d1ph, String d2ph, int signatures,
                          int bugPaths, int shapes, long fires, int armed1, int armed2,
                          int w1, int w2, int samples, int negPrice, int negDisc,
                          int wnp, int wnd, String extra) {}

    private static final String D1 = "DELETE+POINTS+CRASH:IllegalStateException";
    private static final String D2 = "DELETE+POINTS+CRASH:ArithmeticException";
    private static final String GUARD = "DELETE+POINTS+GUARD";

    // ==================== Arms ====================

    private static Result runSarsa(String arm, Map<String, String> a,
                                   int seed, int budget, int reset) {
        double eps = arm.equals("random") ? 1.0 : dblArg(a, "eps", 0.3);
        int neurons = intArg(a, "neurons", 16);
        int steps = intArg(a, "steps", 35);
        SarsaRestTester t = new SarsaRestTester();
        // Episode cap is irrelevant under a request budget, but keep it finite so a
        // pathological run cannot spin forever without sending anything.
        t.configure(seed, 100_000_000, eps, neurons, steps, intArg(a, "logevery", 10_000),
                budget, reset);
        t.executeSarsaTester();

        RunRecorder rec = t.recorder();
        String config = String.format("eps=%.2f/n=%d/steps=%d", eps, neurons, steps);
        return collect(config, t.transport(), rec,
                String.format("eps=%d exec=%d", t.episodesRun(), t.totalExecutes()));
    }

    private static Result runRestler(Map<String, String> a, int seed, int budget, int reset) {
        RestlerFuzzer.Config cfg = new RestlerFuzzer.Config();
        cfg.seed = seed;
        cfg.resetEveryRequests = reset;
        cfg.mode = mode(a.getOrDefault("mode", "bfs"));
        cfg.maxCombinations = intArg(a, "maxcomb", 20);
        cfg.checkerMaxCombinations = intArg(a, "checkercomb", 100);
        cfg.maxSequenceLength = intArg(a, "maxseq", 100);
        cfg.allowGetProducers = boolArg(a, "getproducers", false);
        cfg.grammar = boolArg(a, "deleteall", false)
                ? ApiGrammar.withDeleteAll() : ApiGrammar.matched();

        String dict = a.getOrDefault("dict", "default");
        cfg.dictionary = switch (dict) {
            case "default" -> DefaultDictionary.restlerDefaults();
            case "negatives" -> DefaultDictionary.withNegatives();
            case "matched" -> new MatchedValueSource(seed);
            case "matched-negfirst" -> new MatchedValueSource(seed, MatchedValueSource.Order.NEGATIVE_FIRST);
            case "matched-negfirst-pe" -> new MatchedValueSource(seed, MatchedValueSource.Order.PER_ENDPOINT);
            // Whole-body mode: the dictionary is unused, bodySource takes over.
            case "matched-body" -> DefaultDictionary.restlerDefaults();
            default -> throw new IllegalArgumentException("unknown dict: " + dict);
        };

        String checkers = a.getOrDefault("checkers", "none");
        if (dict.equals("matched-body")) cfg.bodySource = new MatchedBodySource(seed);
        cfg.invalidValueChecker = checkers.equals("invalid") || checkers.equals("all");
        cfg.payloadBodyChecker = checkers.equals("body") || checkers.equals("all");
        // The checker's value source is the axis that decides whether this arm has a
        // negative number at all, so it is named explicitly rather than inherited.
        String cvals = a.getOrDefault("checkervals", dict.startsWith("matched") ? "matched" : "restler");
        cfg.checkerValues = cvals.equals("matched")
                ? new MatchedValueSource(seed + 1, orderOf(dict))
                : new RestlerValueGenerator(seed);

        HttpTransport http = new HttpTransport(
                System.getProperty("sarsa.baseUrl", "http://localhost:8080/api/"), budget);
        RunRecorder rec = new RunRecorder(http);
        RestlerFuzzer fuzzer = new RestlerFuzzer(cfg, http, rec);
        fuzzer.run();

        rec.printSummaryHeader();
        fuzzer.printThroughput();
        rec.printCoverageOnward();
        printScorecard("restler", http, rec);

        String config = String.format("mode=%s/dict=%s/checkers=%s/vals=%s/maxcomb=%d/ccomb=%d%s%s%s",
                a.getOrDefault("mode", "bfs"), dict, checkers, cvals,
                cfg.maxCombinations, cfg.checkerMaxCombinations,
                cfg.allowGetProducers ? "/getprod" : "",
                cfg.createPrefixOnce ? "/prefixonce" : "",
                boolArg(a, "deleteall", false) ? "/delall" : "");
        return collect(config, http, rec,
                String.format("gen=%d seqs=%d rend=%d aborts=%d restarts=%d",
                        fuzzer.maxGeneration(), fuzzer.validSequences(),
                        fuzzer.renderings(), fuzzer.prefixAborts(), fuzzer.restarts())
                        + " wrestarts=" + fuzzer.windowRestarts());
    }

    // ==================== Shared reporting ====================

    private static Result collect(String config, HttpTransport http, RunRecorder rec, String extra) {
        return new Result(
                config,
                http.fuzzRequests(),
                req(rec, D1), req(rec, D2), req(rec, GUARD),
                phase(rec, D1), phase(rec, D2),
                rec.firesBySignature().size(),
                rec.seenPaths().size(),
                rec.executedPaths().size(),
                rec.totalBugFires(),
                rec.maxD1Armed(), rec.maxD2Armed(),
                rec.windowsD1Armed(), rec.windowsD2Armed(), rec.chainSamples(),
                rec.maxNegPrices(), rec.maxNegDiscounts(),
                rec.windowsNegPrice(), rec.windowsNegDiscount(),
                extra);
    }

    private static String req(RunRecorder rec, String signature) {
        RunRecorder.Discovery d = rec.discovery(signature);
        return d == null ? "none" : String.valueOf(d.httpIndex());
    }

    private static String phase(RunRecorder rec, String signature) {
        RunRecorder.Discovery d = rec.discovery(signature);
        return d == null ? "none" : String.valueOf(d.phase());
    }

    private static void printScorecard(String arm, HttpTransport http, RunRecorder rec) {
        long found = RunRecorder.PLANTED.stream()
                .filter(d -> rec.firstSeen().containsKey(d.signature())).count();
        System.out.printf("%nSCORECARD arm=%s req=%d sigs=%d bugpaths=%d shapes=%d/%d hidden=%d/%d%s reqps=%.0f%n",
                arm, http.fuzzRequests(), rec.firesBySignature().size(), rec.seenPaths().size(),
                rec.executedPaths().size(), BugOracle.ADDRESSABLE_SHAPES,
                found, RunRecorder.PLANTED.size(), rec.plantedPhaseTags(),
                http.fuzzRequests() / Math.max(1e-9, rec.wallSeconds()));
        rec.printSummaryFooter();
    }

    // ==================== Args ====================

    /** Dictionary ordering implied by the dict name, for the checker's own dictionary. */
    private static MatchedValueSource.Order orderOf(String dict) {
        return switch (dict) {
            case "matched-negfirst" -> MatchedValueSource.Order.NEGATIVE_FIRST;
            case "matched-negfirst-pe" -> MatchedValueSource.Order.PER_ENDPOINT;
            default -> MatchedValueSource.Order.VALID_FIRST;
        };
    }

    private static RestlerFuzzer.Mode mode(String s) {
        return switch (s) {
            case "bfs" -> RestlerFuzzer.Mode.BFS;
            case "bfs-fast" -> RestlerFuzzer.Mode.BFS_FAST;
            case "bfs-cheap" -> RestlerFuzzer.Mode.BFS_CHEAP;
            case "bfs-minimal" -> RestlerFuzzer.Mode.BFS_MINIMAL;
            case "random-walk" -> RestlerFuzzer.Mode.RANDOM_WALK;
            default -> throw new IllegalArgumentException("unknown mode: " + s);
        };
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String s : args) {
            int i = s.indexOf('=');
            if (i < 0) throw new IllegalArgumentException("expected key=value, got: " + s);
            out.put(s.substring(0, i), s.substring(i + 1));
        }
        return out;
    }

    private static int intArg(Map<String, String> a, String k, int dflt) {
        return a.containsKey(k) ? Integer.parseInt(a.get(k)) : dflt;
    }

    private static double dblArg(Map<String, String> a, String k, double dflt) {
        return a.containsKey(k) ? Double.parseDouble(a.get(k)) : dflt;
    }

    private static boolean boolArg(Map<String, String> a, String k, boolean dflt) {
        return a.containsKey(k) ? Boolean.parseBoolean(a.get(k)) : dflt;
    }
}
