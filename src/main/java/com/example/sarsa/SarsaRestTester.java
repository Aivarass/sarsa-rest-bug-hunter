package com.example.sarsa;

import com.example.ann.TinyQNetwork;
import com.example.harness.BugOracle;
import com.example.harness.HttpResult;
import com.example.harness.HttpTransport;
import com.example.harness.RunRecorder;
import com.example.sarsa.generator.PayloadGenerator;
import com.example.sarsa.model.State;
import com.example.sarsa.strategy.*;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * The RL arm: SARSA over a dial-turning action space, with a 5xx response as its only
 * extrinsic signal.
 *
 * Transport, oracle and all measurement live in {@code com.example.harness} and are
 * shared with the RESTler arm, so the two differ only in how they choose requests.
 * See docs/RESTLER-EXPERIMENT.md.
 *
 * Two budget modes:
 *   - legacy: run EPISODES episodes, reset the server every LOG_EVERY episodes. This is
 *     what every result in the README was measured under.
 *   - request-denominated: run until MAX_REQUESTS tester-issued requests have been sent,
 *     resetting every RESET_EVERY_REQUESTS. This is the mode in which arms are compared,
 *     because RESTler has no notion of an episode and because an episode budget rewards
 *     not executing (README, "Against random search").
 */
public class SarsaRestTester {

    int EPISODES = 50_000;
    private int LOG_EVERY = 10_000;
    private int SEED = 1234;
    int STEP_LIMIT = 35;              // package-private: read by SarsaSelfCheck

    /** Request-denominated budget. 0 = legacy episode budget. */
    int MAX_REQUESTS = 0;
    /** Reset cadence in requests; only consulted when MAX_REQUESTS > 0. */
    int RESET_EVERY_REQUESTS = 0;

    //HYPER PARAMS
    double EPSILON = 0.3;            // package-private: set by SarsaSelfCheck
    static final double GAMMA = 0.99;
    /** Overridable so alpha arms can run in parallel against separate API JVMs. */
    static final double ALPHA = Double.parseDouble(System.getProperty("sarsa.alpha", "0.01"));

    //REWARDS
    static final double NOVEL_BUG_REWARD = 1.0;

    static final double REPEAT_BUG_REWARD = 0.0;
    /** Potential-based shaping weight: Phi(s) = -BETA * dialsSinceExecute. */
    static final double BETA = 0.015;

    static final double EXPLORE_BONUS = 0.1;

    //ANN
    private int ANN_INPUTS = State.FEATURE_COUNT;
    private int ANN_ACTIONS = StrategyBuilder.getActionCount();
    private int ANN_NEURONS = 16;

    //URL
    /** Overridable so independent trials can run in parallel against separate API JVMs. */
    private static final String BASE_URL =
            System.getProperty("sarsa.baseUrl", "http://localhost:8080/api/");

    //HELPERS
    public PayloadGenerator pbt;
    private HttpTransport http;
    private RunRecorder rec;
    private String lastItemId;
    private String lastPriceId;
    private String lastDiscountId;
    private String lastPointsId;

    // Tracking - raw actions
    private final LinkedHashMap<Integer, Integer> allActionCounts = new LinkedHashMap<>();

    // Tracking - strategy-level (what actually got executed)
    private final LinkedHashMap<HttpType, Integer> httpTypeCounts = new LinkedHashMap<>();
    private final LinkedHashMap<Endpoint, Integer> endpointCounts = new LinkedHashMap<>();
    private final LinkedHashMap<Strategy, Integer> strategyCounts = new LinkedHashMap<>();
    private final LinkedHashMap<Field, Integer> fieldCounts = new LinkedHashMap<>();
    private final LinkedHashMap<Intensity, Integer> intensityCounts = new LinkedHashMap<>();

    // Tracking - counters
    private int executeCount = 0;
    private int dialTurnerCount = 0;
    private int skippedExecuteCount = 0;

    private int episodesRun = 0;
    private double totalExploreReward = 0;
    private int shapesPaid = 0;
    private long totalSteps = 0;
    /** EXECUTE actions taken. Differs from requests sent only by skipped executes. */
    private long totalExecutes = 0;

    TinyQNetwork ann;

    @Test
    public void executeSarsaTester() {
        pbt = new PayloadGenerator(SEED);
        ann = new TinyQNetwork(ANN_INPUTS, ANN_NEURONS, ANN_ACTIONS, SEED);
        executeSarsa(EPISODES);
    }

    /**
     * Explicit configuration for a trial, so a runner does not have to reach into
     * private fields by reflection. Any argument <= 0 leaves the current value.
     */
    public void configure(int seed, int episodes, double epsilon, int neurons,
                          int stepLimit, int logEvery, int maxRequests, int resetEveryRequests) {
        if (seed > 0) this.SEED = seed;
        if (episodes > 0) this.EPISODES = episodes;
        if (epsilon >= 0) this.EPSILON = epsilon;
        if (neurons > 0) this.ANN_NEURONS = neurons;
        if (stepLimit > 0) this.STEP_LIMIT = stepLimit;
        if (logEvery > 0) this.LOG_EVERY = logEvery;
        if (maxRequests > 0) this.MAX_REQUESTS = maxRequests;
        if (resetEveryRequests > 0) this.RESET_EVERY_REQUESTS = resetEveryRequests;
    }

    public int neurons() {
        return ANN_NEURONS;
    }

    public int stepLimit() {
        return STEP_LIMIT;
    }

    public double epsilon() {
        return EPSILON;
    }

    /** The transport, so a runner can read the request count and budget state. */
    public HttpTransport transport() {
        return http;
    }

    public RunRecorder recorder() {
        return rec;
    }

    public long totalExecutes() {
        return totalExecutes;
    }

    public int episodesRun() {
        return episodesRun;
    }

    /**
     * Creates the transport and recorder if they do not exist yet.
     *
     * The invariant suite exercises reward methods on a bare tester that never starts a
     * run, so these cannot be created only in {@link #executeSarsa}.
     */
    private void ensureHarness() {
        if (http == null) http = new HttpTransport(BASE_URL, MAX_REQUESTS);
        if (rec == null) rec = new RunRecorder(http);
    }

    /** Clears all run-scoped state so repeated runs on one instance are independent. */
    void resetRunState() {
        ensureHarness();
        rec.resetRunState();
        allActionCounts.clear();
        httpTypeCounts.clear();
        endpointCounts.clear();
        strategyCounts.clear();
        fieldCounts.clear();
        intensityCounts.clear();
        executeCount = 0;
        dialTurnerCount = 0;
        skippedExecuteCount = 0;
        episodesRun = 0;
        totalSteps = 0;
        totalExecutes = 0;
        totalExploreReward = 0;
        shapesPaid = 0;
    }

    private void executeSarsa(int episodes) {
        http = new HttpTransport(BASE_URL, MAX_REQUESTS);
        rec = new RunRecorder(http);
        resetRunState();
        boolean byRequests = MAX_REQUESTS > 0;
        rec.setWindow(byRequests ? RESET_EVERY_REQUESTS : LOG_EVERY,
                byRequests ? "requests" : "episodes");
        Random rng = new Random(SEED);
        double totalReward = 0;
        double totalBaseReward = 0;
        double totalBugs = 0;
        long startTime = System.currentTimeMillis();
        int nextBoundary = 0;   // request index of the next reset, in request mode
        int episodeCap = byRequests ? Integer.MAX_VALUE : episodes;

        for (int i = 1; i <= episodeCap; i++) {
            // Windowed reset. Two reasons: GET_ALL serialises whole collections, so an
            // unbounded store makes late episodes arbitrarily slow; and unbounded
            // accumulation lets chain assembly amortise across the entire run, which
            // quietly changes the task the agent is being measured on.
            // A request-denominated cadence of 0 must mean "reset once, at the start",
            // not "reset whenever fuzzRequests >= 0" - which is every episode, and is a
            // trap: it silently turns an intended no-windowing run into the most
            // aggressive windowing possible, and reset cadence is one of the strongest
            // levers in this harness.
            boolean boundary = byRequests
                    ? (RESET_EVERY_REQUESTS > 0
                            ? http.fuzzRequests() >= nextBoundary
                            : i == 1)
                    : (i - 1) % LOG_EVERY == 0;
            if (boundary) {
                rec.resetServerState();
                // Bug novelty is scoped to the window, because the world it refers to was
                // just cleared. Without this, 223 of 232 paths are found in window 1 and
                // task reward is measured at exactly 0.0000 for the final 10k episodes -
                // precisely the reward-starved regime that produced the original policy
                // collapse. Total reward stays bounded at windows x paths, so it is still
                // not farmable.
                rec.clearNovelty();
                if (byRequests) nextBoundary += RESET_EVERY_REQUESTS;
            }
            rec.setPhase(i);
            double[] result;
            try {
                result = executeEpisode(rng);
            } catch (HttpTransport.BudgetExhausted e) {
                episodesRun++;   // the partial episode still happened
                break;
            }
            totalReward += result[0];
            totalBugs += result[1];
            totalBaseReward += result[2];

            boolean logNow = byRequests
                    ? http.fuzzRequests() >= nextBoundary || http.exhausted()
                    : i % LOG_EVERY == 0;
            if (logNow) {
                int windowEpisodes = byRequests ? Math.max(1, i) : LOG_EVERY;
                double avgReward = totalReward / windowEpisodes;
                double avgBaseReward = totalBaseReward / windowEpisodes;
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                double executeRatio = executeCount > 0
                        ? (double) executeCount / (executeCount + dialTurnerCount) * 100 : 0;

                System.out.println("\n" + "=".repeat(60));
                System.out.printf("Episode %,d | Avg Task Reward: %.4f | Time: %.1fs%n",
                        i, avgBaseReward, elapsed);
                System.out.printf("REAL BUGS (distinct signatures): %d | Reachability paths: %d | New paths this window: %.0f%n",
                        rec.firesBySignature().size(), rec.seenPaths().size(), totalBugs);
                System.out.printf("Avg shaped reward (learner's signal): %.4f%n", avgReward);
                System.out.printf("Execute ratio: %.1f%% (%d executes, %d dial-turners)%n",
                        executeRatio, executeCount, dialTurnerCount);
                System.out.printf("Transport failures: %d | Skipped executes: %d%n",
                        http.transportFailures(), skippedExecuteCount);
                System.out.println(rec.hiddenDefectStatusLine());

                System.out.println("\n--- HttpType Distribution ---");
                for (Map.Entry<HttpType, Integer> e : httpTypeCounts.entrySet()) {
                    System.out.printf("  %-10s: %d%n", e.getKey(), e.getValue());
                }

                System.out.println("\n--- Resource Distribution ---");
                for (Map.Entry<Endpoint, Integer> e : endpointCounts.entrySet()) {
                    System.out.printf("  %-10s: %d%n", e.getKey(), e.getValue());
                }

                System.out.println("\n--- Strategy Distribution ---");
                for (Map.Entry<Strategy, Integer> e : strategyCounts.entrySet()) {
                    System.out.printf("  %-15s: %d%n", e.getKey(), e.getValue());
                }

                System.out.println("\n--- Field Distribution ---");
                for (Map.Entry<Field, Integer> e : fieldCounts.entrySet()) {
                    System.out.printf("  %-12s: %d%n", e.getKey(), e.getValue());
                }

                System.out.println("\n--- Intensity Distribution ---");
                for (Map.Entry<Intensity, Integer> e : intensityCounts.entrySet()) {
                    System.out.printf("  %-12s: %d%n", e.getKey(), e.getValue());
                }

                if (!rec.firesBySignature().isEmpty()) {
                    System.out.println("\n--- Real Bugs (cumulative) ---");
                    System.out.printf("  %-44s %8s %7s%n", "signature", "fires", "paths");
                    rec.firesBySignature().entrySet().stream()
                            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                            .forEach(e -> System.out.printf("  %-44s %8d %7d%n", e.getKey(), e.getValue(),
                                    rec.pathsBySignature().getOrDefault(e.getKey(), Set.of()).size()));
                    long guards = rec.firesBySignature().keySet().stream().filter(k -> k.endsWith("GUARD")).count();
                    System.out.printf("  %d planted guard(s), %d uncaught crash(es)%n",
                            guards, rec.firesBySignature().size() - guards);
                }

                if (!rec.firesByPath().isEmpty()) {
                    System.out.println("\n--- Top Reachability Paths (this window) ---");
                    rec.firesByPath().entrySet().stream()
                            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                            .limit(5)
                            .forEach(e -> System.out.printf("  %s: %d times%n", e.getKey(), e.getValue()));
                }

                System.out.println("\n--- Raw Action Distribution ---");
                System.out.print("  ");
                allActionCounts.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> System.out.printf("[%d]:%d ", e.getKey(), e.getValue()));
                System.out.println();

                // Window reset. The recorder's run-lifetime tallies are deliberately
                // untouched: novelty and the real-bug table span the run.
                totalReward = 0;
                totalBaseReward = 0;
                totalBugs = 0;
                startTime = System.currentTimeMillis();
                allActionCounts.clear();
                httpTypeCounts.clear();
                endpointCounts.clear();
                strategyCounts.clear();
                fieldCounts.clear();
                intensityCounts.clear();
                rec.clearWindow();
                executeCount = 0;
                dialTurnerCount = 0;
                skippedExecuteCount = 0;
            }
            if (http.exhausted()) break;
        }
        // Under the comparison protocol every arm takes a closing arming sample, so the
        // final window is not invisible. Skipped in legacy episode mode to keep older
        // runs byte-comparable.
        if (byRequests) rec.finalSample();
        printRunSummary();
    }

    /** @return {shapedReward, novelBugs, taskReward} */
    double[] executeEpisode(Random rng) {
        StrategyBuilder strategy = new StrategyBuilder();
        episodesRun++;
        totalSteps += STEP_LIMIT;
        lastItemId = null;
        lastPriceId = null;
        lastDiscountId = null;
        lastPointsId = null;
        State currentState = initState();
        strategy.reset();
        boolean[] mask = getValidMask(strategy);
        int currentAction = ann.epsilonGreedyMasked(currentState.scale(), EPSILON, mask, rng);

        double episodeReward = 0;
        double episodeBaseReward = 0;
        int bugsFound = 0;
        // Uncapped; State.stepsSinceExecute caps at 10 for feature scaling, which would
        // break the shaping telescope.
        int dialsSinceExecute = 0;

        for (int step = 0; step < STEP_LIMIT; step++) {
            allActionCounts.merge(currentAction, 1, Integer::sum);
            currentState.setStepsRemainingFrac((double) (STEP_LIMIT - step) / STEP_LIMIT);

            // Snapshot S_t before anything mutates the shared State instance.
            double[] sFeat = currentState.scale();
            int kBefore = dialsSinceExecute;

            HttpResult response = null;
            String requestShape = null;
            String executedPath = null;
            boolean wasExecute = strategy.isExecute(currentAction);

            if (wasExecute) {
                trackStrategyExecution(strategy);
                // Shape is known before the call; the bug path needs the response, so it
                // is built after. Both must be read before resetMethodOnly() clears the dials.
                requestShape = requestShapeKey(strategy);
                pbt.setLastItemId(parseId(lastItemId));
                pbt.setLastPriceId(parseId(lastPriceId));
                pbt.setLastDiscountId(parseId(lastDiscountId));
                response = executeWithStrategy(strategy);
                executeCount++;
                totalExecutes++;
                if (response != null) executedPath = bugPathKey(strategy, response);
            } else {
                currentState = strategy.applyAction(currentAction, currentState);
                dialsSinceExecute++;
                currentState.setStepsSinceExecute(Math.min(dialsSinceExecute, 10));
                dialTurnerCount++;
            }

            State nextState = updateStateFromResponse(currentState, strategy, response);

            // Score first, then record: both memories must be read before they are written.
            double explore = shapeBonus(response, requestShape, wasExecute);
            RewardOutcome outcome = scoreTransition(response, executedPath, wasExecute);
            if (wasExecute && response != null) recordShape(requestShape, explore > 0);
            if (outcome.wasBug) {
                rec.recordBug(executedPath, bugSignature(strategy, response), outcome.novelBug, step);
                if (outcome.novelBug) bugsFound++;
            }

            // Must precede A_{t+1} so a', the mask and s' all agree.
            if (wasExecute) {
                strategy.resetMethodOnly();
                nextState.resetMethodOnly();
                dialsSinceExecute = 0;
            }

            boolean terminal = (step == STEP_LIMIT - 1);
            nextState.setStepsRemainingFrac(
                    terminal ? 0.0 : (double) (STEP_LIMIT - step - 1) / STEP_LIMIT);

            boolean[] nextMask = getValidMask(strategy);
            double[] sNextFeat = nextState.scale();
            int nextAction = ann.epsilonGreedyMasked(sNextFeat, EPSILON, nextMask, rng);

            // F = GAMMA*Phi(s') - Phi(s). Phi(terminal) must be 0 or the telescope leaks.
            double phiBefore = -BETA * kBefore;
            double phiAfter = terminal ? 0.0 : -BETA * dialsSinceExecute;
            double shaping = GAMMA * phiAfter - phiBefore;

            // episodeBaseReward stays bug-only so "Avg Task Reward" remains comparable
            // with runs made before the exploration bonus existed.
            double reward = outcome.reward + explore + shaping;
            episodeReward += reward;
            episodeBaseReward += outcome.reward;

            ann.sarsaUpdate(sFeat, currentAction, reward, sNextFeat, nextAction, terminal, ALPHA, GAMMA);

            currentState = nextState;
            currentAction = nextAction;
        }

        return new double[]{episodeReward, bugsFound, episodeBaseReward};
    }

    record RewardOutcome(double reward, boolean novelBug, boolean wasBug) {}

    /**
     * Pure: reads exploration memory but never mutates it. Recording is done by
     * recordShape, after scoring, exactly as recordBug does for novelty.
     *
     * Paid only when the server actually answered - a request that died in transport
     * taught us nothing about that shape, and must not mark it explored.
     */
    double shapeBonus(HttpResult response, String requestShape, boolean wasExecute) {
        ensureHarness();
        if (!wasExecute || response == null || requestShape == null) return 0.0;
        return rec.isNovelShape(requestShape) ? EXPLORE_BONUS : 0.0;
    }

    /** Pure: reads novelty memory but never mutates it. Recording is done by recordBug. */
    RewardOutcome scoreTransition(HttpResult response, String path, boolean wasExecute) {
        ensureHarness();
        if (!wasExecute || response == null || response.status() != 500 || path == null) {
            return new RewardOutcome(0, false, false);
        }
        boolean novel = rec.isNovelPath(path);
        return new RewardOutcome(novel ? NOVEL_BUG_REWARD : REPEAT_BUG_REWARD, novel, true);
    }

    /** Exploration memory. Mirrors recordBug: mutate only after scoring. */
    private void recordShape(String requestShape, boolean paid) {
        rec.recordShape(requestShape);
        if (paid) {
            totalExploreReward += EXPLORE_BONUS;
            shapesPaid++;
        }
    }

    // ========================== Oracle delegation ==========================
    // Thin wrappers over the shared oracle, kept as instance methods because the
    // invariant suite asserts on them directly.

    String requestShapeKey(StrategyBuilder s) {
        return BugOracle.requestShapeKey(s.getHttpType().name(), s.getEndpoint().name(),
                s.getStrategy().name(), s.getField().name());
    }

    String bugPathKey(StrategyBuilder s, HttpResult r) {
        return BugOracle.bugPathKey(requestShapeKey(s), r);
    }

    String bugSignature(StrategyBuilder s, HttpResult r) {
        return BugOracle.bugSignature(s.getHttpType().name(), s.getEndpoint().name(), r);
    }

    String errorKind(HttpResult r) {
        return BugOracle.errorKind(r);
    }

    /** The recorder's novelty memory, exposed for the invariant suite. */
    Set<String> seenPaths() {
        ensureHarness();
        return rec.seenPaths();
    }

    private void trackStrategyExecution(StrategyBuilder strategy) {
        httpTypeCounts.merge(strategy.getHttpType(), 1, Integer::sum);
        endpointCounts.merge(strategy.getEndpoint(), 1, Integer::sum);
        strategyCounts.merge(strategy.getStrategy(), 1, Integer::sum);
        fieldCounts.merge(strategy.getField(), 1, Integer::sum);
        intensityCounts.merge(strategy.getIntensity(), 1, Integer::sum);
    }

    // ========================== End-of-run summary ==========================

    /**
     * Run scorecard. The Throughput block is arm-specific - "episodes" is not a unit the
     * RESTler arm has - and everything from Coverage onward comes from the shared
     * recorder so the two arms are scored identically.
     */
    private void printRunSummary() {
        double wallSec = rec.wallSeconds();

        rec.setExploreReward(totalExploreReward, shapesPaid);
        rec.printSummaryHeader();

        System.out.println("\n--- Throughput ---");
        System.out.printf("  episodes            : %,d  (%d steps each, %,d steps total)%n",
                episodesRun, STEP_LIMIT, totalSteps);
        System.out.printf("  requests sent       : %,d  (%.1f per episode, %.0f req/s)%n",
                totalExecutes, totalExecutes / (double) Math.max(1, episodesRun),
                totalExecutes / Math.max(1e-9, wallSec));
        System.out.printf("  execute ratio       : %.1f%% of steps were requests, the rest turned dials%n",
                100.0 * totalExecutes / Math.max(1, totalSteps));
        System.out.printf("  wasted              : %,d skipped executes, %,d transport failures%n",
                skippedExecuteCount, http.transportFailures());
        System.out.printf("  wall clock          : %.1fs%n", wallSec);

        rec.printCoverageOnward();

        long foundHidden = RunRecorder.PLANTED.stream()
                .filter(d -> rec.firstSeen().containsKey(d.signature())).count();
        System.out.printf("%nSCORECARD eps=%d req=%d sigs=%d bugpaths=%d shapes=%d/%d hidden=%d/%d%s reqps=%.0f%n",
                episodesRun, totalExecutes, rec.firesBySignature().size(), rec.seenPaths().size(),
                rec.executedPaths().size(), BugOracle.ADDRESSABLE_SHAPES, foundHidden,
                RunRecorder.PLANTED.size(),
                rec.plantedPhaseTags(), totalExecutes / Math.max(1e-9, wallSec));
        rec.printSummaryFooter();
    }

    // ========================== HTTP ==========================

    private HttpResult executeWithStrategy(StrategyBuilder s) {
        HttpType httpType = s.getHttpType();
        Endpoint endpoint = s.getEndpoint();
        String payload = pbt.generate(endpoint, s.getField(), s.getStrategy(), s.getIntensity());
        String path = endpoint.name().toLowerCase();
        String id = currentIdFor(endpoint);

        if (httpTypeRequiresId(httpType) && id == null) {
            skippedExecuteCount++;
            return null;
        }

        HttpResult r = switch (httpType) {
            case POST -> http.fuzz("POST", path, payload);
            case PUT -> http.fuzz("PUT", path + "/" + id, payload);
            case PATCH -> http.fuzz("PATCH", path + "/" + id, payload);
            case DELETE -> http.fuzz("DELETE", path + "/" + id, null);
            case GET -> http.fuzz("GET", path + "/" + id, null);
            case GET_ALL -> http.fuzz("GET", path, null);
            default -> null;
        };

        if (httpType == HttpType.POST && r != null && r.status() == 201) {
            setIdFor(endpoint, BugOracle.extractId(r.body()));
        }
        return r;
    }

    // ========================== State ==========================

    private String currentIdFor(Endpoint endpoint) {
        return switch (endpoint) {
            case ITEMS -> lastItemId;
            case PRICES -> lastPriceId;
            case DISCOUNTS -> lastDiscountId;
            case POINTS -> lastPointsId;
        };
    }

    private void setIdFor(Endpoint endpoint, String id) {
        switch (endpoint) {
            case ITEMS -> lastItemId = id;
            case PRICES -> lastPriceId = id;
            case DISCOUNTS -> lastDiscountId = id;
            case POINTS -> lastPointsId = id;
        }
    }

    /**
     * Derives the four id flags from the id fields, so a feature can never claim an id
     * the request layer does not hold.
     */
    private void syncIdFlags(State state) {
        state.setHasValidItemId(lastItemId != null ? 1 : 0);
        state.setHasValidPriceId(lastPriceId != null ? 1 : 0);
        state.setHasValidDiscountId(lastDiscountId != null ? 1 : 0);
        state.setHasValidPointsId(lastPointsId != null ? 1 : 0);
    }

    private State updateStateFromResponse(State state, StrategyBuilder strategy, HttpResult response) {
        if (response == null) return state;

        HttpType httpType = strategy.getHttpType();
        Endpoint endpoint = strategy.getEndpoint();
        int sc = response.status();

        state.setLastStatusCall(sc);
        state.setLastMethod(methodCode(httpType));
        state.setEndpoint(endpoint.ordinal());

        if (httpType == HttpType.DELETE && (sc == 200 || sc == 204)) {
            setIdFor(endpoint, null);
        }
        if (httpType == HttpType.GET_ALL && sc == 200) {
            setIdFor(endpoint, BugOracle.extractFirstId(response.body()));
            if (endpoint == Endpoint.ITEMS) {
                state.setHasAnyItems(lastItemId != null ? 1 : 0);
            }
        }

        syncIdFlags(state);
        return state;
    }

    private int methodCode(HttpType httpType) {
        return switch (httpType) {
            case GET, GET_ALL, NONE -> 0;
            case POST -> 1;
            case PUT -> 2;
            case DELETE -> 3;
            case PATCH -> 4;
        };
    }

    boolean[] getValidMask(StrategyBuilder strategy) {
        boolean[] mask = new boolean[ANN_ACTIONS];
        boolean hasId = currentIdFor(strategy.getEndpoint()) != null;

        for (int i = 0; i < ANN_ACTIONS; i++) {
            if (StrategyBuilder.actionRequiresId(i)) {
                mask[i] = hasId;
            } else if (i == StrategyBuilder.getExecuteIndex()) {
                mask[i] = strategy.isReady()
                        && (!httpTypeRequiresId(strategy.getHttpType()) || hasId);
            } else {
                mask[i] = true;
            }
        }
        return mask;
    }

    /** Methods addressing a single resource, which therefore need a known id. */
    private static boolean httpTypeRequiresId(HttpType httpType) {
        return httpType == HttpType.GET
                || httpType == HttpType.PUT
                || httpType == HttpType.PATCH
                || httpType == HttpType.DELETE;
    }

    private static Long parseId(String id) {
        if (id == null) return null;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private State initState() {
        return new State(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.0);
    }
}
