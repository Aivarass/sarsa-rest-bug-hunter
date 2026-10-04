package com.example.restler;

import com.example.harness.BugOracle;
import com.example.harness.HttpResult;
import com.example.harness.HttpTransport;
import com.example.harness.RunRecorder;
import com.example.sarsa.strategy.Endpoint;

import java.util.*;

/**
 * The RESTler arm: grammar-based stateful fuzzing by breadth-first search over request
 * sequences.
 *
 * A port of {@code restler/engine/core/driver.py} and
 * {@code restler/engine/core/sequences.py}, cited inline. It shares this project's
 * transport, oracle, recorder and budget with the RL arm, so the two arms differ only in
 * how they choose what to send next. See docs/RESTLER-EXPERIMENT.md for the full design
 * and the deviations register.
 *
 * The loop, from {@code driver.py:644 generate_sequences}:
 *
 * <pre>
 *   pool = { [] }
 *   for generation = 1, 2, 3, ...
 *       pool = extend(pool)      // append every satisfiable request type
 *       pool = render(pool)      // keep sequences whose last request returned 2xx
 * </pre>
 *
 * Two properties of that loop matter more than anything else here, and neither is an
 * accident of this port:
 *
 *  - **The prefix is re-sent for every rendering** ({@code sequences.py:449}), so a
 *    length-n sequence costs n requests per value combination tried. Cost grows with
 *    depth, which is the price of a live dependency chain.
 *  - **Validity is 2xx** ({@code response.py:8}). A 4xx rendering is discarded and a 5xx
 *    is a bug. So the search only ever builds on top of requests the service accepted -
 *    which is what makes it stateful, and also what stops it from exploring past a
 *    rejected value.
 */
public class RestlerFuzzer {

    /** Search strategies, all from {@code driver.py:62 extend} / {@code :167 render_one}. */
    public enum Mode {
        /** Extend every sequence by every satisfiable request; exhaust all renderings. */
        BFS,
        /** One sequence per request type ({@code driver.py:116}); exhaust renderings. */
        BFS_FAST,
        /** Every sequence, but stop at the first valid rendering ({@code :258}). */
        BFS_CHEAP,
        /** One sequence per request type AND stop at the first valid rendering. */
        BFS_MINIMAL,
        /** Keep one randomly chosen extended sequence per generation ({@code :125}). */
        RANDOM_WALK
    }

    public static class Config {
        public Mode mode = Mode.BFS;
        public List<RequestType> grammar = ApiGrammar.matched();
        /** Main-driver dictionary. RESTler's own default unless overridden. */
        public ValueSource dictionary = DefaultDictionary.restlerDefaults();
        /**
         * Whole-body payloads instead of per-parameter values ({@code restler_custom_payload}).
         * When set, the combination id indexes the body list directly and
         * {@link #dictionary} is unused. See {@link BodySource}.
         */
        public BodySource bodySource = null;
        /** {@code restler_settings.py:318 MAX_COMBINATIONS_DEFAULT}. */
        public int maxCombinations = 20;
        /** {@code restler_settings.py:322 MAX_SEQUENCE_LENGTH_DEFAULT}. */
        public int maxSequenceLength = 100;
        public boolean invalidValueChecker = false;
        public boolean payloadBodyChecker = false;
        /** Per-parameter budget for the invalid-value checker; its documented default. */
        public int checkerMaxCombinations = 100;
        /** Value source for the invalid-value checker. */
        public ValueSource checkerValues = null;
        /** Audit item A8: let GET-all responses act as producers, as the RL arm does. */
        public boolean allowGetProducers = false;
        /**
         * {@code sequence_exploration_settings.create_prefix_once}
         * ({@code restler_settings.py:466,951}).
         *
         * Renders a sequence's prefix once and reuses its dynamic objects for every
         * combination of the last request, instead of re-sending the whole prefix per
         * rendering. A documented efficiency setting, and a consequential one under a
         * *request* budget: a length-6 sequence with 20 renderings costs 6x20 = 120
         * requests without it and 5+20 = 25 with it, so it buys roughly 5x more
         * renderings - which is exactly the currency the breadth/depth trade-off is
         * denominated in. Omitting it would have been a real disadvantage.
         */
        public boolean createPrefixOnce = false;
        /** Reset cadence in tester-issued requests; 0 disables the windowed reset. */
        public int resetEveryRequests = 0;
        public long seed = 1234;
    }

    private final Config cfg;
    private final HttpTransport http;
    private final RunRecorder rec;
    private final Random rng;

    /** Cached dictionary per (request type, block) - RESTler's candidate values pool. */
    private final Map<String, List<List<CandidateValue>>> valuePool = new HashMap<>();
    /** Cached whole-body candidates, when a {@link BodySource} is configured. */
    private final Map<String, List<BodySource.BodyCandidate>> bodyPool = new HashMap<>();
    /** Per-generation checker dedup ({@code invalid_value_checker.py:215}). */
    private final Map<Integer, Set<String>> checkerSeen = new HashMap<>();

    // Diagnostics reported on the RESULT line.
    private int generation = 0;
    private int maxGeneration = 0;
    private int validSequences = 0;
    private int renderings = 0;
    private int prefixAborts = 0;
    /** Searches restarted because the pool died with budget left. */
    private int restarts = 0;
    /** Searches restarted because a window reset invalidated the pool. */
    private int windowRestarts = 0;
    private int nextBoundary = 0;

    public RestlerFuzzer(Config cfg, HttpTransport http, RunRecorder rec) {
        this.cfg = cfg;
        this.http = http;
        this.rec = rec;
        this.rng = new Random(cfg.seed);
        if (cfg.checkerValues == null) {
            cfg.checkerValues = new RestlerValueGenerator(cfg.seed);
        }
    }

    // ==================== Main loop ====================

    /**
     * Fuzzes until the request budget is spent.
     *
     * The outer loop is a deviation, declared: the real tool raises
     * {@code ExhaustSeqCollectionException} and exits when a generation yields no valid
     * sequence, which on a fixed request budget would leave most of the budget unspent
     * and score the arm on requests it never got to send. Restarting the search is what
     * an operator would do (re-run the tool), and it can only help this arm - so it is
     * the conservative choice. Restarts are counted and reported.
     */
    public void run() {
        rec.markRunStart();
        rec.setWindow(cfg.resetEveryRequests, "requests");
        boolean firstPass = true;
        try {
            while (!http.exhausted()) {
                boolean cleared;
                try {
                    searchOnce();
                    cleared = false;
                } catch (WindowReset e) {
                    cleared = true;
                }
                if (cleared) windowRestarts++;
                else if (!firstPass) restarts++;
                firstPass = false;
            }
        } catch (HttpTransport.BudgetExhausted e) {
            // Normal termination: the budget is the stopping rule for every arm.
        }
        rec.finalSample();
    }

    /**
     * Unwinds the search when the windowed reset fires.
     *
     * The reset clears the store, so every dynamic object the pool refers to is gone and
     * the pool is worthless - continuing would spend the budget on guaranteed 404s. The
     * search restarts from the empty sequence, which is the same thing the RL arm
     * experiences when its id handles stop resolving.
     */
    private static class WindowReset extends RuntimeException {
        WindowReset() {
            super(null, null, false, false);   // control flow, not an error: no stack trace
        }
    }

    /** One complete BFS from the empty sequence until the pool dies or the budget ends. */
    private void searchOnce() {
        List<Sequence> pool = new ArrayList<>(List.of(Sequence.empty()));
        generation = 0;
        checkerSeen.clear();

        while (!http.exhausted() && generation < cfg.maxSequenceLength) {
            generation++;
            rec.setPhase(generation);
            maxGeneration = Math.max(maxGeneration, generation);

            // The extension is streamed rather than materialised. In bfs mode the
            // extended generation is up to |grammar| x |pool| sequences - at a pool of
            // 10^5 that is millions of objects held only to be rendered one at a time,
            // and the budget would stop the run long before most were reached. Rendering
            // each extension as it is produced is the same order and the same semantics,
            // with memory bounded by the *valid* pool instead.
            List<Sequence> next = new ArrayList<>();
            boolean anyExtension = extendStreaming(pool, seq -> {
                maybeResetWindow();
                next.addAll(renderOne(seq));
            });
            if (!anyExtension || next.isEmpty()) return;
            pool = next;
            validSequences += pool.size();
        }
    }

    /**
     * The windowed server reset, identical in cadence to the RL arm's.
     *
     * Clearing the store invalidates every dynamic object the pool refers to, so the
     * search restarts from the empty sequence - the honest consequence of the
     * environment policy, applied to both arms alike.
     */
    private void maybeResetWindow() {
        if (cfg.resetEveryRequests <= 0) {
            // No windowing: one reset at the very start, so the run begins clean.
            if (nextBoundary == 0) {
                rec.resetServerState();
                nextBoundary = Integer.MAX_VALUE;
            }
            return;
        }
        if (http.fuzzRequests() < nextBoundary) return;
        boolean first = nextBoundary == 0;
        rec.resetServerState();
        rec.clearNovelty();
        // Advance past the current request index rather than by one window, so a
        // generation that overshot does not trigger a burst of back-to-back resets.
        while (nextBoundary <= http.fuzzRequests()) nextBoundary += cfg.resetEveryRequests;
        // The opening reset happens before anything was built, so there is no pool to
        // throw away and no restart to count.
        if (!first) throw new WindowReset();
    }

    // ==================== extend ====================

    /**
     * Streaming form of {@link #extend}: calls {@code sink} for each extended sequence
     * in exactly the order {@link #extend} would have produced it.
     *
     * @return whether any extension existed at all (the pool-is-dead signal)
     */
    private boolean extendStreaming(List<Sequence> pool, java.util.function.Consumer<Sequence> sink) {
        // Random walk needs the whole candidate set before it can choose one, and its
        // pool is a single sequence, so materialising it costs nothing.
        if (cfg.mode == Mode.RANDOM_WALK) {
            List<Sequence> extended = extend(pool);
            if (extended.isEmpty()) return false;
            for (Sequence s : extended) {
                if (http.exhausted()) return true;
                sink.accept(s);
            }
            return true;
        }
        boolean any = false;
        for (RequestType req : cfg.grammar) {
            for (Sequence seq : pool) {
                if (!seq.satisfies(req, cfg.allowGetProducers)) continue;
                any = true;
                if (http.exhausted()) return true;
                sink.accept(seq.plus(req));
                if (cfg.mode == Mode.BFS_FAST || cfg.mode == Mode.BFS_MINIMAL) break;
            }
        }
        return any;
    }

    /**
     * {@code driver.py:62 extend} - append every request type whose consumed dynamic
     * objects are all produced by the sequence.
     *
     * Iteration order is request-type-major, exactly as in the source, because that is
     * what makes the {@code break} in the quick modes keep one sequence *per request
     * type* rather than one per sequence.
     */
    private List<Sequence> extend(List<Sequence> pool) {
        List<Sequence> extended = new ArrayList<>();
        for (RequestType req : cfg.grammar) {
            for (Sequence seq : pool) {
                if (!seq.satisfies(req, cfg.allowGetProducers)) continue;
                extended.add(seq.plus(req));
                // driver.py:116 - in the quick modes, one sequence per request type.
                if (cfg.mode == Mode.BFS_FAST || cfg.mode == Mode.BFS_MINIMAL) break;
            }
        }
        // driver.py:125 - random walk keeps exactly one of the new generation.
        if (cfg.mode == Mode.RANDOM_WALK && !extended.isEmpty()) {
            return new ArrayList<>(List.of(extended.get(rng.nextInt(extended.size()))));
        }
        return extended;
    }

    // ==================== render ====================

    private boolean stopsAtFirstValid() {
        return cfg.mode == Mode.BFS_CHEAP
                || cfg.mode == Mode.BFS_MINIMAL
                || cfg.mode == Mode.RANDOM_WALK;
    }

    /**
     * {@code driver.py:167 render_one} - try value combinations for the last request and
     * return the sequences that ended in a 2xx.
     *
     * Phase 1 renders until the first valid combination or exhaustion. The quick modes
     * stop there; {@code bfs} and {@code bfs-fast} then continue through every remaining
     * combination ("bfs needs to be exhaustive to provide full grammar coverage",
     * {@code driver.py:269}), each valid one becoming its own sequence next generation.
     */
    private List<Sequence> renderOne(Sequence seq) {
        int total = totalCombinations(seq.last());
        List<Sequence> valid = new ArrayList<>();
        int combo = 0;
        boolean lastValid = false;
        int invalidCount = 0;

        // create_prefix_once: stand the prefix up a single time and reuse its dynamic
        // objects across every rendering of the last request.
        Map<String, String> cachedPrefix = null;
        if (cfg.createPrefixOnce && seq.length() > 1) {
            cachedPrefix = replayPrefix(seq);
            if (cachedPrefix == null) {
                prefixAborts++;
                return valid;
            }
        }

        while (combo < total && !http.exhausted()) {
            RenderResult rr = render(seq, combo, cachedPrefix);
            // driver.py:224 - the cheap modes apply checkers only on the first invalid
            // rendering, to avoid re-running them over near-identical 404s.
            boolean applyCheckers = !(cfg.mode == Mode.BFS_CHEAP || cfg.mode == Mode.BFS_MINIMAL)
                    || rr.valid || invalidCount < 1;
            if (applyCheckers) applyCheckers(seq, rr, combo);
            if (rr.valid) {
                lastValid = true;
                break;
            }
            invalidCount++;
            combo++;
        }

        if (stopsAtFirstValid()) {
            if (lastValid) valid.add(seq.withLastCombination(combo));
            return valid;
        }

        while (combo < total && !http.exhausted()) {
            if (lastValid) valid.add(seq.withLastCombination(combo));
            combo++;
            if (combo >= total) break;
            RenderResult rr = render(seq, combo, cachedPrefix);
            applyCheckers(seq, rr, combo);
            lastValid = rr.valid;
        }
        return valid;
    }

    /** Outcome of one end-to-end rendering attempt. */
    private record RenderResult(boolean valid, boolean prefixFailed, HttpResult response,
                                Map<String, String> dynamicObjects, List<String> renderedValues) {}

    /**
     * {@code sequences.py:420 render} - replay the prefix with its cached valid
     * combinations, then send the final request with combination {@code lastCombo}.
     *
     * A 5xx anywhere, prefix included, is recorded as a bug and fails the sequence
     * ({@code sequences.py} prefix loop, {@code has_bug_code}).
     */
    private RenderResult render(Sequence seq, int lastCombo) {
        return render(seq, lastCombo, null);
    }

    /**
     * @param cachedPrefix dynamic objects from an already-standing prefix
     *                     ({@code create_prefix_once}); null re-sends the prefix.
     */
    private RenderResult render(Sequence seq, int lastCombo, Map<String, String> cachedPrefix) {
        renderings++;
        if (cachedPrefix != null) {
            RequestType lastReq = seq.last();
            Outcome o = execute(lastReq, lastCombo, cachedPrefix, seq.length() - 1);
            if (!o.sent) return new RenderResult(false, false, null, cachedPrefix, o.values);
            boolean ok = o.response != null && isValidCode(o.response);
            if (ok) recordProduced(lastReq, o.response, cachedPrefix);
            return new RenderResult(ok, false, o.response, cachedPrefix, o.values);
        }
        Map<String, String> dyn = new HashMap<>();

        for (int i = 0; i < seq.length() - 1; i++) {
            RequestType req = seq.requests.get(i);
            Outcome o = execute(req, seq.combinations.get(i), dyn, i);
            if (!o.sent) {
                prefixAborts++;
                return new RenderResult(false, true, null, dyn, List.of());
            }
            if (o.response == null || BugOracle.isBug(o.response) || !isValidCode(o.response)) {
                prefixAborts++;
                return new RenderResult(false, true, o.response, dyn, List.of());
            }
            recordProduced(req, o.response, dyn);
        }

        RequestType last = seq.last();
        Outcome o = execute(last, lastCombo, dyn, seq.length() - 1);
        if (!o.sent) return new RenderResult(false, false, null, dyn, o.values);
        if (o.response != null && isValidCode(o.response)) {
            recordProduced(last, o.response, dyn);
            return new RenderResult(true, false, o.response, dyn, o.values);
        }
        return new RenderResult(false, false, o.response, dyn, o.values);
    }

    /** {@code response.py:8} - VALID_CODES = {200, 201, 202, 204, 304}. */
    private static boolean isValidCode(HttpResult r) {
        int s = r.status();
        return s == 200 || s == 201 || s == 202 || s == 204 || s == 304;
    }

    private void recordProduced(RequestType req, HttpResult r, Map<String, String> dyn) {
        String produced = cfg.allowGetProducers ? req.producesWithGet() : req.produces();
        if (produced == null) return;
        String id = "GET_ALL".equals(req.method)
                ? BugOracle.extractFirstId(r.body())
                : BugOracle.extractId(r.body());
        if (id != null) dyn.put(produced, id);
    }

    /** One sent request plus the values it was rendered with. */
    private record Outcome(boolean sent, HttpResult response, List<String> values) {}

    /**
     * Sends one request of a sequence: resolves its dynamic objects, renders its leaves
     * from the combination id, and records the outcome through the shared oracle.
     *
     * A request whose dynamic object is missing is *not sent*: the dependency it needs
     * does not exist, exactly as an unresolved {@code reader} aborts a rendering in the
     * real engine. This is the analogue of the RL arm's action mask - neither arm
     * deliberately fires requests it knows will 404.
     */
    private Outcome execute(RequestType req, int combo, Map<String, String> dyn, int position) {
        String pathId = null;
        if (req.pathId) {
            pathId = dyn.get(RequestType.dynName(req.endpoint));
            if (pathId == null) return new Outcome(false, null, List.of());
        }
        String parentId = null;
        if (req.hasBody && req.parent != null) {
            parentId = dyn.get(RequestType.dynName(req.parent));
            if (parentId == null) return new Outcome(false, null, List.of());
        }

        String path = req.pathId
                ? req.endpoint.name().toLowerCase() + "/" + pathId
                : req.endpoint.name().toLowerCase();

        if (req.hasBody && cfg.bodySource != null) {
            // Whole-body mode: the candidate is a complete payload the RL generator
            // produced, with only the parent id re-pointed at this prefix's dynamic
            // object. Shape attribution uses the candidate's own (strategy, field),
            // so these keys land in the RL arm's 1,068-shape space exactly.
            List<BodySource.BodyCandidate> pool = bodyPoolFor(req);
            BodySource.BodyCandidate cand = pool.get(combo % pool.size());
            String body = MatchedBodySource.withParentId(req.endpoint, cand.json(), parentId);
            send(req, path, body, cand.strategyLabel(), cand.fieldLabel(), position);
            return new Outcome(true, lastResponse, List.of(cand.json()));
        }

        List<CandidateValue> chosen = combinationValues(req, combo);
        List<String> values = new ArrayList<>(chosen.size());
        for (CandidateValue v : chosen) values.add(v.json());

        String body = req.hasBody ? ApiGrammar.body(req.endpoint, parentId, values) : null;

        // Shape attribution: the main driver renders every leaf at once, so the shape is
        // keyed on the dictionary rather than a single field - ALL, exactly as the RL
        // arm keys a body that targets every field. Checker mutations, which do fuzz one
        // field at a time, pass that field instead.
        String strategyLabel = chosen.isEmpty() ? cfg.dictionary.label() : chosen.get(0).strategyLabel();
        send(req, path, body, strategyLabel, "ALL", position);
        return new Outcome(true, lastResponse, values);
    }

    /** Set by {@link #send}; the response of the most recent request. */
    private HttpResult lastResponse;

    /**
     * The one place this arm touches the network, so every request is counted against
     * the budget and scored by the shared oracle - including the checkers' requests,
     * which would otherwise be a hidden subsidy (audit item B5).
     */
    private void send(RequestType req, String path, String body,
                      String strategyLabel, String fieldLabel, int position) {
        String shape = BugOracle.requestShapeKey(req.method, req.endpoint.name(),
                strategyLabel, fieldLabel);
        HttpResult r = http.fuzz(req.httpVerb(), path, body);
        lastResponse = r;
        if (r == null) return;        // transport failure; taught us nothing about the shape
        rec.recordShape(shape);
        if (BugOracle.isBug(r)) {
            String bugPath = BugOracle.bugPathKey(shape, r);
            String signature = BugOracle.bugSignature(req.method, req.endpoint.name(), r);
            boolean novel = rec.isNovelPath(bugPath);
            rec.recordBug(bugPath, signature, novel, position);
        }
    }

    // ==================== Combinations ====================

    /** Whole-body candidates for a request type, cached for the whole run. */
    private List<BodySource.BodyCandidate> bodyPoolFor(RequestType req) {
        return bodyPool.computeIfAbsent(req.key(),
                k -> cfg.bodySource.bodies(req.endpoint, cfg.maxCombinations));
    }

    /** Per-leaf dictionary for a request type, cached for the whole run. */
    private List<List<CandidateValue>> poolFor(RequestType req) {
        return valuePool.computeIfAbsent(req.key(), k -> {
            List<List<CandidateValue>> out = new ArrayList<>();
            for (RequestType.Block b : req.blocks) {
                List<CandidateValue> vs = cfg.dictionary.values(req.endpoint, b, cfg.maxCombinations);
                out.add(vs.isEmpty()
                        ? List.of(new CandidateValue("null", cfg.dictionary.label(), b.field().toUpperCase()))
                        : vs);
            }
            return out;
        });
    }

    /**
     * Number of renderings for a request: the product of its leaves' dictionary sizes,
     * capped at {@code max_combinations} ({@code restler_settings.py:318}).
     */
    private int totalCombinations(RequestType req) {
        if (!req.hasBody) return 1;
        if (cfg.bodySource != null) {
            // The combination id indexes the body list directly, so there is no
            // mixed-radix product and no frozen leaf.
            return Math.max(1, Math.min(cfg.maxCombinations, bodyPoolFor(req).size()));
        }
        if (req.blocks.isEmpty()) return 1;
        long total = 1;
        for (List<CandidateValue> vs : poolFor(req)) {
            total *= vs.size();
            if (total >= cfg.maxCombinations) return cfg.maxCombinations;
        }
        return (int) Math.max(1, total);
    }

    /** Mixed-radix decode of a combination id into one value per leaf. */
    private List<CandidateValue> combinationValues(RequestType req, int combo) {
        if (!req.hasBody || req.blocks.isEmpty()) return List.of();
        List<List<CandidateValue>> pools = poolFor(req);
        List<CandidateValue> out = new ArrayList<>(pools.size());
        int rest = combo;
        for (List<CandidateValue> vs : pools) {
            out.add(vs.get(rest % vs.size()));
            rest /= vs.size();
        }
        return out;
    }

    // ==================== Checkers ====================

    private void applyCheckers(Sequence seq, RenderResult rr, int combo) {
        if (rr.prefixFailed()) return;   // invalid_value_checker.py:197
        if (cfg.invalidValueChecker) invalidValueChecker(seq, rr, combo);
        if (cfg.payloadBodyChecker) payloadBodyChecker(seq, rr, combo);
    }

    /**
     * {@code checkers/invalid_value_checker.py:184 apply}.
     *
     * For each fuzzable leaf of the last request, in turn, substitute values from the
     * invalid-value source while holding every other leaf at its original rendering, and
     * report any 5xx. Fires once per (generation, request type)
     * ({@code :215-223}), re-executes the sequence prefix ({@code :244}), and spends
     * {@code max_combinations / numBlocks} values per leaf ({@code :266-269}).
     *
     * On this API this is the checker that matters: its value generator is the only
     * source of negative numbers in the whole tool, and because its requests are real,
     * a negative value it PATCHes into a stored price *persists* - arming a defect that
     * a later main-driver sequence then triggers, even though the mutated rendering
     * never entered the sequence pool.
     */
    private void invalidValueChecker(Sequence seq, RenderResult rr, int combo) {
        RequestType last = seq.last();
        if (!last.hasBody) return;
        if (cfg.bodySource != null) {
            invalidBodyChecker(seq, last);
            return;
        }
        if (last.blocks.isEmpty()) return;
        if (!firstTimeThisGeneration("IV|" + last.key())) return;

        Map<String, String> dyn = replayPrefix(seq);
        if (dyn == null) return;

        String pathId = last.pathId ? dyn.get(RequestType.dynName(last.endpoint)) : null;
        if (last.pathId && pathId == null) return;
        String parentId = last.parent != null ? dyn.get(RequestType.dynName(last.parent)) : null;
        if (last.parent != null && parentId == null) return;

        List<String> original = new ArrayList<>();
        for (CandidateValue v : combinationValues(last, combo)) original.add(v.json());

        int paramBudget = Math.max(1, cfg.checkerMaxCombinations / last.blocks.size());
        String path = last.pathId
                ? last.endpoint.name().toLowerCase() + "/" + pathId
                : last.endpoint.name().toLowerCase();

        for (int idx = 0; idx < last.blocks.size(); idx++) {
            RequestType.Block block = last.blocks.get(idx);
            for (CandidateValue cand : cfg.checkerValues.values(last.endpoint, block, paramBudget)) {
                if (http.exhausted()) return;
                String body = ApiGrammar.bodyWithReplacement(
                        last.endpoint, parentId, original, idx, cand.json());
                send(last, path, body, cand.strategyLabel(), cand.fieldLabel(), seq.length() - 1);
            }
        }
    }

    /**
     * Whole-body form of the invalid-value checker, for the {@link BodySource}
     * configuration.
     *
     * A {@code restler_custom_payload} is a single request block, and
     * {@code invalid_value_checker.py:236 should_fuzz} fuzzes {@code _custom_} blocks
     * too - so with a custom body the checker substitutes whole payloads rather than
     * individual leaves. Same trigger, same dedup, same per-parameter budget.
     */
    private void invalidBodyChecker(Sequence seq, RequestType last) {
        if (!firstTimeThisGeneration("IB|" + last.key())) return;
        Map<String, String> dyn = replayPrefix(seq);
        if (dyn == null) return;
        String pathId = last.pathId ? dyn.get(RequestType.dynName(last.endpoint)) : null;
        if (last.pathId && pathId == null) return;
        String parentId = last.parent != null ? dyn.get(RequestType.dynName(last.parent)) : null;
        if (last.parent != null && parentId == null) return;
        String path = last.pathId
                ? last.endpoint.name().toLowerCase() + "/" + pathId
                : last.endpoint.name().toLowerCase();
        for (BodySource.BodyCandidate cand
                : cfg.bodySource.bodies(last.endpoint, cfg.checkerMaxCombinations)) {
            if (http.exhausted()) return;
            String body = MatchedBodySource.withParentId(last.endpoint, cand.json(), parentId);
            send(last, path, body, cand.strategyLabel(), cand.fieldLabel(), seq.length() - 1);
        }
    }

    /**
     * {@code checkers/payload_body_checker.py} - structural body fuzzing (FSE'2020).
     *
     * Declared approximation: the published checker runs a large schema-driven search,
     * and this reproduces its documented mutation classes - drop a field, change a
     * field's type, empty the body, add an unknown field - under the same trigger and
     * dedup rules. It cannot reach either planted defect, which need a negative *number*
     * rather than a malformed body, so the approximation does not affect the headline;
     * it is included because omitting a default checker would understate the tool.
     */
    private void payloadBodyChecker(Sequence seq, RenderResult rr, int combo) {
        RequestType last = seq.last();
        if (!last.hasBody) return;
        // With a whole-body vocabulary the structural mutations this checker makes -
        // dropped fields, wrong types, empty bodies - are already in the vocabulary as
        // the STRUCTURE and TYPE_CONFUSE strategies, so running it too would duplicate
        // them and spend budget twice on the same bytes.
        if (cfg.bodySource != null) return;
        if (!firstTimeThisGeneration("PB|" + last.key())) return;

        Map<String, String> dyn = replayPrefix(seq);
        if (dyn == null) return;

        String pathId = last.pathId ? dyn.get(RequestType.dynName(last.endpoint)) : null;
        if (last.pathId && pathId == null) return;
        String parentId = last.parent != null ? dyn.get(RequestType.dynName(last.parent)) : null;
        if (last.parent != null && parentId == null) return;

        List<String> original = new ArrayList<>();
        for (CandidateValue v : combinationValues(last, combo)) original.add(v.json());

        String path = last.pathId
                ? last.endpoint.name().toLowerCase() + "/" + pathId
                : last.endpoint.name().toLowerCase();

        List<String> mutations = new ArrayList<>();
        mutations.add("{}");
        for (int i = 0; i < last.blocks.size(); i++) {
            // wrong type, in both directions
            mutations.add(ApiGrammar.bodyWithReplacement(
                    last.endpoint, parentId, original, i, "\"wrongType\""));
            mutations.add(ApiGrammar.bodyWithReplacement(
                    last.endpoint, parentId, original, i, "[1,2,3]"));
            mutations.add(ApiGrammar.bodyWithReplacement(
                    last.endpoint, parentId, original, i, "{\"nested\":1}"));
            mutations.add(ApiGrammar.bodyWithReplacement(
                    last.endpoint, parentId, original, i, "null"));
        }
        String full = ApiGrammar.body(last.endpoint, parentId, original);
        mutations.add(full.substring(0, full.length() - 1) + ", \"unknownField\": \"x\"}");

        for (String body : mutations) {
            if (http.exhausted()) return;
            send(last, path, body, "PAYLOAD_BODY", "ALL", seq.length() - 1);
        }
    }

    /** {@code invalid_value_checker.py:215} - one trigger per request type per generation. */
    private boolean firstTimeThisGeneration(String key) {
        return checkerSeen.computeIfAbsent(generation, g -> new HashSet<>()).add(key);
    }

    /**
     * {@code checker_base.py _execute_start_of_sequence} - re-sends the prefix so the
     * checker's request has live dependencies. Returns null if the prefix did not stand
     * up this time.
     */
    private Map<String, String> replayPrefix(Sequence seq) {
        Map<String, String> dyn = new HashMap<>();
        for (int i = 0; i < seq.length() - 1; i++) {
            RequestType req = seq.requests.get(i);
            Outcome o = execute(req, seq.combinations.get(i), dyn, i);
            if (!o.sent || o.response == null || !isValidCode(o.response)) return null;
            recordProduced(req, o.response, dyn);
        }
        return dyn;
    }

    // ==================== Reporting ====================

    public int maxGeneration() { return maxGeneration; }
    public int validSequences() { return validSequences; }
    public int renderings() { return renderings; }
    public int prefixAborts() { return prefixAborts; }
    public int restarts() { return restarts; }
    public int windowRestarts() { return windowRestarts; }

    /** The arm-specific Throughput block; everything else comes from the recorder. */
    public void printThroughput() {
        double wall = rec.wallSeconds();
        System.out.println("\n--- Throughput ---");
        System.out.printf("  renderings          : %,d  (%,d valid sequences, max generation %d)%n",
                renderings, validSequences, maxGeneration);
        System.out.printf("  requests sent       : %,d  (%.1f per rendering, %.0f req/s)%n",
                http.fuzzRequests(), http.fuzzRequests() / (double) Math.max(1, renderings),
                http.fuzzRequests() / Math.max(1e-9, wall));
        System.out.printf("  search restarts     : %,d after pool exhaustion, %,d after a window reset%n",
                restarts, windowRestarts);
        System.out.printf("  wasted              : %,d prefix aborts, %,d transport failures%n",
                prefixAborts, http.transportFailures());
        System.out.printf("  wall clock          : %.1fs%n", wall);
    }

    // ---- test seams: used only by RestlerSelfCheck, which pins the facts the
    // ---- comparison rests on. Kept narrow on purpose.

    /** {@link #extend} - exposed so the strategy semantics can be asserted directly. */
    public List<Sequence> extendForTest(List<Sequence> pool) {
        return extend(pool);
    }

    /** {@link #combinationValues} - exposed to assert combination 0 is fully valid. */
    public List<CandidateValue> combinationValuesForTest(RequestType req, int combo) {
        return combinationValues(req, combo);
    }

    /** {@link #totalCombinations} - exposed to assert the max_combinations cap. */
    public int totalCombinationsForTest(RequestType req) {
        return totalCombinations(req);
    }

    /** {@link #isValidCode} - exposed to assert VALID_CODES. */
    public static boolean isValidCodeForTest(HttpResult r) {
        return isValidCode(r);
    }

    /** Endpoint set covered by the grammar, for the smoke test. */
    public static Set<Endpoint> grammarEndpoints(List<RequestType> grammar) {
        Set<Endpoint> out = EnumSet.noneOf(Endpoint.class);
        for (RequestType r : grammar) out.add(r.endpoint);
        return out;
    }
}
