package com.example.sarsa;

import com.example.ann.TinyQNetwork;
import com.example.sarsa.generator.PayloadGenerator;
import com.example.sarsa.model.State;
import com.example.sarsa.strategy.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class SarsaRestTester {

    int EPISODES = 50_000;
    private int LOG_EVERY = 10_000;
    private int SEED = 1234;
    int STEP_LIMIT = 35;              // package-private: read by SarsaSelfCheck

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

    //HTTP - one pooled client for the whole JVM; per-request clients exhaust ephemeral ports
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //HELPERS
    PayloadGenerator pbt;
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

    /** Measurement only; cleared each log window. Keyed by reachability path. */
    private final LinkedHashMap<String, Integer> firesByPath = new LinkedHashMap<>();
    /** Reward-owned novelty memory, keyed by reachability path. Run lifetime. */
    final Set<String> seenPaths = new HashSet<>();
    /** Real bugs: signature -> total fires. Run lifetime. */
    final LinkedHashMap<String, Integer> firesBySignature = new LinkedHashMap<>();
    /** Real bugs: signature -> distinct paths that reach it. Run lifetime. */
    final LinkedHashMap<String, Set<String>> pathsBySignature = new LinkedHashMap<>();

    // Tracking - counters
    private int executeCount = 0;
    private int dialTurnerCount = 0;
    private int transportFailureCount = 0;
    private int skippedExecuteCount = 0;

    // ==================== Hidden-defect bookkeeping ====================

    /**
     * The deliberately planted defects, hardcoded by {@link #bugSignature}. Knowing
     * what we planted is what lets the run report *status* ("did it find them?")
     * instead of only "here is a pile of 500s".
     *
     * blindEpisodes = expected episodes to the first hit for a uniform-random policy
     * over the valid action mask, at STEP_LIMIT=35 and a fresh server. Measured by
     * step-aware multilevel splitting over a faithful model of the API + generator
     * + mask (7 seeds, 12M trials per level): defect 1 1-in-7.2e14 (4.5e14..1.1e15),
     * defect 2 1-in-4.3e18 (2.7e18..9.7e18). The ratio, ~6000x, is the stable part;
     * the absolute scale depends on the policy you call "blind", so if you prefer
     * your own defect-1 figure, multiply it by ~6e3 to get defect 2.
     */
    record PlantedDefect(String signature, String label, String chain, double blindEpisodes) {}

    private static final List<PlantedDefect> PLANTED = List.of(
            new PlantedDefect(
                    "DELETE+POINTS+CRASH:IllegalStateException",
                    "DEFECT 1 - one dependency (negative linked price)",
                    "POST items -> POST prices -> PATCH prices NEGATIVE -> POST discounts "
                            + "-> POST points -> DELETE points",
                    7.2e14),
            new PlantedDefect(
                    "DELETE+POINTS+CRASH:ArithmeticException",
                    "DEFECT 2 - two dependencies (negative price AND negative discount)",
                    "POST items -> POST prices -> PATCH prices NEGATIVE -> POST discounts "
                            + "-> PATCH discounts NEGATIVE -> POST points -> DELETE points",
                    4.3e18));

    /**
     * Size of the reachability-path space {@link #bugPathKey} can emit:
     * 3 bodyless methods x 4 endpoints + 3 body methods x 4 endpoints x 8 strategies x 11 fields.
     */
    private static final int ADDRESSABLE_SHAPES = 3 * 4 + 3 * 4 * 8 * 11;

    /** First sighting of a signature. Run lifetime. */
    record Discovery(int order, int episode, int step, long executeIndex, String path) {}

    private final LinkedHashMap<String, Discovery> firstSeen = new LinkedHashMap<>();
    /** Every request shape actually sent, bug or not - exploration coverage. Run lifetime. */
    private final Set<String> executedPaths = new HashSet<>();
    /** Path -> fires over the whole run (firesByPath is window-scoped). */
    private final LinkedHashMap<String, Integer> firesByPathTotal = new LinkedHashMap<>();

    /** Request shapes that produced a 500 at least once - keyed by shape, not bug path. */
    private final Set<String> shapesThat500 = new HashSet<>();

    private int currentEpisode = 0;
    private int episodesRun = 0;
    private double totalExploreReward = 0;
    private int shapesPaid = 0;
    private int serverResets = 0;
    // Chain-depth diagnostics (measurement only; see sampleChainDepth).
    private int maxD1Armed = 0, maxD2Armed = 0, windowsD1Armed = 0, windowsD2Armed = 0, chainSamples = 0;
    private long totalSteps = 0;
    private long totalExecutes = 0;
    private long totalBugFires = 0;
    /** Run-lifetime novel-path count; seenPaths is window-scoped and cannot serve this. */
    private long totalNovelPaths = 0;
    private long runStartMillis = 0;

    TinyQNetwork ann;

    @Test
    public void executeSarsaTester() {
        pbt = new PayloadGenerator(SEED);
        ann = new TinyQNetwork(ANN_INPUTS, ANN_NEURONS, ANN_ACTIONS, SEED);
        executeSarsa(EPISODES);
    }

    /** Clears all run-scoped state so repeated runs on one instance are independent. */
    void resetRunState() {
        allActionCounts.clear();
        httpTypeCounts.clear();
        endpointCounts.clear();
        strategyCounts.clear();
        fieldCounts.clear();
        intensityCounts.clear();
        firesByPath.clear();
        seenPaths.clear();
        firesBySignature.clear();
        pathsBySignature.clear();
        firstSeen.clear();
        executedPaths.clear();
        shapesThat500.clear();
        firesByPathTotal.clear();
        executeCount = 0;
        dialTurnerCount = 0;
        transportFailureCount = 0;
        skippedExecuteCount = 0;
        currentEpisode = 0;
        episodesRun = 0;
        totalSteps = 0;
        totalExecutes = 0;
        totalBugFires = 0;
        totalNovelPaths = 0;
        totalExploreReward = 0;
        shapesPaid = 0;
        serverResets = 0;
        maxD1Armed = 0;
        maxD2Armed = 0;
        windowsD1Armed = 0;
        windowsD2Armed = 0;
        chainSamples = 0;
        runStartMillis = System.currentTimeMillis();
    }

    private void executeSarsa(int episodes) {
        resetRunState();
        Random rng = new Random(SEED);
        double totalReward = 0;
        double totalBaseReward = 0;
        double totalBugs = 0;
        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= episodes; i++) {
            // Windowed reset. Two reasons: GET_ALL serialises whole collections, so an
            // unbounded store makes late episodes arbitrarily slow; and unbounded
            // accumulation lets chain assembly amortise across the entire run, which
            // quietly changes the task the agent is being measured on.
            if ((i - 1) % LOG_EVERY == 0) {
                resetServerState();
                // Bug novelty is scoped to the window, because the world it refers to was
                // just cleared. Without this, 223 of 232 paths are found in window 1 and
                // task reward is measured at exactly 0.0000 for the final 10k episodes -
                // which is precisely the reward-starved regime that produced the original
                // policy collapse. Total reward stays bounded at windows x paths, so it is
                // still not farmable. Exploration memory (executedPaths) stays global:
                // shape coverage is already saturated, so re-paying it would only add noise.
                seenPaths.clear();
            }
            currentEpisode = i;
            double[] result = executeEpisode(rng);
            totalReward += result[0];
            totalBugs += result[1];
            totalBaseReward += result[2];

            if (i % LOG_EVERY == 0) {
                double avgReward = totalReward / LOG_EVERY;
                double avgBaseReward = totalBaseReward / LOG_EVERY;
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                double executeRatio = executeCount > 0
                        ? (double) executeCount / (executeCount + dialTurnerCount) * 100 : 0;

                System.out.println("\n" + "=".repeat(60));
                System.out.printf("Episode %,d | Avg Task Reward: %.4f | Time: %.1fs%n",
                        i, avgBaseReward, elapsed);
                System.out.printf("REAL BUGS (distinct signatures): %d | Reachability paths: %d | New paths this window: %.0f%n",
                        firesBySignature.size(), seenPaths.size(), totalBugs);
                System.out.printf("Avg shaped reward (learner's signal): %.4f%n", avgReward);
                System.out.printf("Execute ratio: %.1f%% (%d executes, %d dial-turners)%n",
                        executeRatio, executeCount, dialTurnerCount);
                System.out.printf("Transport failures: %d | Skipped executes: %d%n",
                        transportFailureCount, skippedExecuteCount);
                System.out.println(hiddenDefectStatusLine());

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

                if (!firesBySignature.isEmpty()) {
                    System.out.println("\n--- Real Bugs (cumulative) ---");
                    System.out.printf("  %-44s %8s %7s%n", "signature", "fires", "paths");
                    firesBySignature.entrySet().stream()
                            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                            .forEach(e -> System.out.printf("  %-44s %8d %7d%n", e.getKey(), e.getValue(),
                                    pathsBySignature.getOrDefault(e.getKey(), Set.of()).size()));
                    long guards = firesBySignature.keySet().stream().filter(k -> k.endsWith("GUARD")).count();
                    System.out.printf("  %d planted guard(s), %d uncaught crash(es)%n",
                            guards, firesBySignature.size() - guards);
                }

                if (!firesByPath.isEmpty()) {
                    System.out.println("\n--- Top Reachability Paths (this window) ---");
                    firesByPath.entrySet().stream()
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

                // Window reset. seenPaths / firesBySignature / pathsBySignature are
                // deliberately NOT cleared: novelty and the real-bug tally span the run.
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
                firesByPath.clear();
                executeCount = 0;
                dialTurnerCount = 0;
                transportFailureCount = 0;
                skippedExecuteCount = 0;
            }
        }
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
                recordBug(executedPath, bugSignature(strategy, response), outcome.novelBug, step);
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
//            ann.qLearningUpdate(sFeat, currentAction, reward, sNextFeat, terminal, ALPHA, GAMMA);

            currentState = nextState;
            currentAction = nextAction;
        }

        return new double[]{episodeReward, bugsFound, episodeBaseReward};
    }

    record RewardOutcome(double reward, boolean novelBug, boolean wasBug) {}

    /**
     * Pure: reads exploration memory but never mutates it. Recording is done by
     * recordShape, after scoring, exactly as recordBug does for seenPaths.
     *
     * Paid only when the server actually answered - a request that died in transport
     * taught us nothing about that shape, and must not mark it explored.
     */
    double shapeBonus(HttpResult response, String requestShape, boolean wasExecute) {
        if (!wasExecute || response == null || requestShape == null) return 0.0;
        return executedPaths.contains(requestShape) ? 0.0 : EXPLORE_BONUS;
    }

    /** Pure: reads novelty memory but never mutates it. Recording is done by recordBug. */
    RewardOutcome scoreTransition(HttpResult response, String path, boolean wasExecute) {
        if (!wasExecute || response == null || response.status() != 500 || path == null) {
            return new RewardOutcome(0, false, false);
        }
        boolean novel = !seenPaths.contains(path);
        return new RewardOutcome(novel ? NOVEL_BUG_REWARD : REPEAT_BUG_REWARD, novel, true);
    }

    /** Exploration memory. Mirrors recordBug: mutate only after scoring. */
    private void recordShape(String requestShape, boolean paid) {
        executedPaths.add(requestShape);
        if (paid) {
            totalExploreReward += EXPLORE_BONUS;
            shapesPaid++;
        }
    }

    private void recordBug(String path, String signature, boolean novel, int step) {
        firesByPath.merge(path, 1, Integer::sum);
        firesByPathTotal.merge(path, 1, Integer::sum);
        totalBugFires++;
        boolean newSignature = !firesBySignature.containsKey(signature);
        firesBySignature.merge(signature, 1, Integer::sum);
        pathsBySignature.computeIfAbsent(signature, k -> new HashSet<>()).add(path);
        if (novel) {
            seenPaths.add(path);
            totalNovelPaths++;
        }
        shapesThat500.add(requestShapeOf(path));
        if (newSignature) {
            firstSeen.put(signature,
                    new Discovery(firstSeen.size() + 1, currentEpisode, step, totalExecutes, path));
            logDiscovery(signature, path, step);
        }
    }

    /**
     * Clears all four collections on the server, bounding both GET_ALL cost and
     * cross-episode state carry-over.
     *
     * Note: the controllers' id counters are NOT reset by deleteAll, so after the first
     * window the generator's fabricated ids (1-1000) can no longer collide with a live
     * object. That is consistent for the rest of the run.
     */
    private void resetServerState() {
        sampleChainDepth();
        for (Endpoint e : Endpoint.values()) {
            send("DELETE", e.name().toLowerCase(), null);
        }
        serverResets++;
    }

    /**
     * MEASUREMENT ONLY - never feeds reward or state.
     *
     * Answers one question the discovery counters cannot: when a hidden defect is not
     * found, did the agent fail to *arm* the chain, or arm it and never fire the DELETE?
     * GET /api/points already returns the whole nested chain, so this needs no change
     * to the system under test and no privileged access - it reads a response any
     * client could have.
     *
     * Sampled at window boundaries only, so a chain armed and destroyed inside a window
     * is missed. It is therefore a lower bound on arming.
     */
    private void sampleChainDepth() {
        HttpResult r = send("GET", "points", null);
        if (r == null || r.status() != 200 || r.body() == null) return;
        try {
            JsonNode arr = MAPPER.readTree(r.body());
            if (!arr.isArray()) return;
            int d1 = 0, d2 = 0;
            for (JsonNode pt : arr) {
                JsonNode disc = pt.path("discount");
                if (disc.isMissingNode() || disc.isNull()) continue;
                JsonNode price = disc.path("price");
                if (price.isMissingNode() || price.isNull()) continue;
                if (price.path("price").asDouble(0) < 0) {
                    d1++;
                    if (disc.path("discount").asDouble(0) < 0) d2++;
                }
            }
            maxD1Armed = Math.max(maxD1Armed, d1);
            maxD2Armed = Math.max(maxD2Armed, d2);
            if (d1 > 0) windowsD1Armed++;
            if (d2 > 0) windowsD2Armed++;
            chainSamples++;
        } catch (Exception ignored) {
            // diagnostic only; never let it affect a run
        }
    }

    /** Only POST/PUT/PATCH carry a payload; for the rest, strategy and field are no-ops. */
    private static boolean sendsBody(HttpType httpType) {
        return httpType == HttpType.POST || httpType == HttpType.PUT || httpType == HttpType.PATCH;
    }

    /**
     * Request shape: the dial settings that can actually change the bytes we send.
     * Including strategy/field for bodyless methods aliased one DELETE bug into 88
     * "discoveries" per endpoint, since those requests are byte-identical.
     *
     * This is the key for exploration coverage - what we tried - and the base of the
     * bug path key below.
     */
    String requestShapeKey(StrategyBuilder s) {
        HttpType h = s.getHttpType();
        if (!sendsBody(h)) return h + "+" + s.getEndpoint();
        return h + "+" + s.getEndpoint() + "+" + s.getStrategy() + "+" + s.getField();
    }

    /**
     * Bug path: the request shape PLUS what the server did with it.
     *
     * Shape alone is too coarse. DELETE /points has three distinct outcomes behind one
     * shape - the deliberate guard, and the two planted crashes - and keying novelty on
     * shape alone let the trivial guard (reachable with five valid requests) collect the
     * reward that both hidden defects would otherwise earn. errorKind separates them
     * without naming any particular defect: a different server-side outcome is a
     * different finding, on every endpoint.
     */
    String bugPathKey(StrategyBuilder s, HttpResult r) {
        return requestShapeKey(s) + "+" + errorKind(r);
    }

    /** Strips the trailing errorKind off a bug path, recovering the request shape. */
    private static String requestShapeOf(String bugPath) {
        int i = bugPath.lastIndexOf('+');
        return i < 0 ? bugPath : bugPath.substring(0, i);
    }

    /** Real-bug identity: what the server actually did, not how we got there. */
    String bugSignature(StrategyBuilder s, HttpResult r) {
        return s.getHttpType() + "+" + s.getEndpoint() + "+" + errorKind(r);
    }

    /**
     * GUARD  = deliberate internalServerError() with an empty body.
     * CRASH  = unhandled exception; class name needs
     *          server.error.include-exception=true in application.properties.
     */
    String errorKind(HttpResult r) {
        String body = r.body();
        if (body == null || body.isBlank()) return "GUARD";
        try {
            String ex = MAPPER.readTree(body).path("exception").asText("");
            if (!ex.isEmpty()) return "CRASH:" + ex.substring(ex.lastIndexOf('.') + 1);
        } catch (Exception ignored) {
            // fall through
        }
        return "CRASH:unknown";
    }

    private void trackStrategyExecution(StrategyBuilder strategy) {
        httpTypeCounts.merge(strategy.getHttpType(), 1, Integer::sum);
        endpointCounts.merge(strategy.getEndpoint(), 1, Integer::sum);
        strategyCounts.merge(strategy.getStrategy(), 1, Integer::sum);
        fieldCounts.merge(strategy.getField(), 1, Integer::sum);
        intensityCounts.merge(strategy.getIntensity(), 1, Integer::sum);
    }

    // ========================== Discovery logging ==========================

    /**
     * First-sighting banner, printed inline in the run log so a discovery is visible
     * the moment it happens instead of only in the end-of-run summary.
     * The planted defects are hardcoded in {@link #PLANTED}; anything else gets a
     * one-liner, since an unplanted 500 is still worth seeing.
     */
    private void logDiscovery(String signature, String path, int step) {
        PlantedDefect planted = plantedFor(signature);
        if (planted == null) {
            System.out.printf("%n  [new bug] ep %,d step %2d  %-44s via %s%n",
                    currentEpisode, step, signature, path);
            return;
        }
        String bar = "  " + "*".repeat(86);
        System.out.println();
        System.out.println(bar);
        System.out.printf("  *** HIDDEN DEFECT FOUND -> %s%n", planted.label());
        System.out.printf("  *** signature : %s%n", signature);
        System.out.printf("  *** found at  : episode %,d  step %d  execute #%,d%n",
                currentEpisode, step, totalExecutes);
        System.out.printf("  *** via path  : %s%n", path);
        System.out.printf("  *** chain     : %s%n", planted.chain());
        System.out.printf("  *** blind baseline 1-in-%.2e episodes => found %.2e x sooner%n",
                planted.blindEpisodes(), planted.blindEpisodes() / Math.max(1, currentEpisode));
        System.out.println(bar);
        System.out.println();
    }

    private static PlantedDefect plantedFor(String signature) {
        return PLANTED.stream()
                .filter(d -> d.signature().equals(signature))
                .findFirst()
                .orElse(null);
    }

    /** One-line hidden-defect status for the per-window log. */
    private String hiddenDefectStatusLine() {
        StringBuilder sb = new StringBuilder("Hidden defects: ");
        long found = PLANTED.stream().filter(d -> firstSeen.containsKey(d.signature())).count();
        sb.append(found).append('/').append(PLANTED.size()).append(" found");
        for (int i = 0; i < PLANTED.size(); i++) {
            Discovery d = firstSeen.get(PLANTED.get(i).signature());
            sb.append(" | D").append(i + 1).append(": ")
              .append(d == null ? "-" : String.format("ep %,d (%,d fires)",
                      d.episode(), firesBySignature.get(PLANTED.get(i).signature())));
        }
        return sb.toString();
    }

    // ========================== End-of-run summary ==========================

    /**
     * Run scorecard. Everything here is run-lifetime and deliberately comparable
     * between runs: same table, same order, same units.
     */
    private void printRunSummary() {
        double wallSec = (System.currentTimeMillis() - runStartMillis) / 1000.0;
        long guards = firesBySignature.keySet().stream().filter(k -> k.endsWith("GUARD")).count();
        long crashes = firesBySignature.size() - guards;

        System.out.println("\n\n" + "#".repeat(96));
        System.out.println("RUN SUMMARY");
        System.out.println("#".repeat(96));

        System.out.println("\n--- Throughput ---");
        System.out.printf("  episodes            : %,d  (%d steps each, %,d steps total)%n",
                episodesRun, STEP_LIMIT, totalSteps);
        System.out.printf("  requests sent       : %,d  (%.1f per episode, %.0f req/s)%n",
                totalExecutes, totalExecutes / (double) Math.max(1, episodesRun),
                totalExecutes / Math.max(1e-9, wallSec));
        System.out.printf("  execute ratio       : %.1f%% of steps were requests, the rest turned dials%n",
                100.0 * totalExecutes / Math.max(1, totalSteps));
        System.out.printf("  wasted              : %,d skipped executes, %,d transport failures%n",
                skippedExecuteCount, transportFailureCount);
        System.out.printf("  wall clock          : %.1fs%n", wallSec);

        System.out.println("\n--- Coverage ---");
        System.out.printf("  request shapes hit  : %,d / %,d addressable (%.1f%%)%n",
                executedPaths.size(), ADDRESSABLE_SHAPES,
                100.0 * executedPaths.size() / ADDRESSABLE_SHAPES);
        System.out.printf("  shapes that 500'd   : %,d (%.1f%% of shapes hit)%n",
                shapesThat500.size(),
                100.0 * shapesThat500.size() / Math.max(1, executedPaths.size()));
        System.out.printf("  distinct bug paths  : %,d (shape + errorKind; one shape can hold several)%n",
                firesByPathTotal.size());
        System.out.printf("  bug fires           : %,d total, %,d paid as novel (%.1f%% repeats)%n",
                totalBugFires, totalNovelPaths,
                100.0 * (totalBugFires - totalNovelPaths) / Math.max(1, totalBugFires));
        System.out.printf("  novelty budget      : %,d novel-path payouts over %,d windows%n",
                totalNovelPaths, Math.max(1, serverResets));
        System.out.printf("  exploration reward  : %.1f paid over %,d first visits (cap %.1f)%n",
                totalExploreReward, shapesPaid, ADDRESSABLE_SHAPES * EXPLORE_BONUS);
        System.out.printf("  server resets       : %,d (every %,d episodes)%n",
                serverResets, LOG_EVERY);
        System.out.printf("  chain armed at window boundaries (sampled, lower bound):%n");
        System.out.printf("    D1-armed points: max %d, seen in %d/%d samples%n",
                maxD1Armed, windowsD1Armed, chainSamples);
        System.out.printf("    D2-armed points: max %d, seen in %d/%d samples%n",
                maxD2Armed, windowsD2Armed, chainSamples);
        System.out.printf("  hit rate            : %.2f%% of requests returned 500%n",
                100.0 * totalBugFires / Math.max(1, totalExecutes));

        System.out.println("\n--- Unique defects (" + firesBySignature.size() + " signatures: "
                + guards + " guard, " + crashes + " crash) ---");
        System.out.printf("  %-44s %8s %6s %8s %9s %8s%n",
                "signature", "fires", "paths", "first ep", "first req", "/1k req");
        firesBySignature.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> firstSeen.get(e.getKey()).order()))
                .forEach(e -> {
                    Discovery d = firstSeen.get(e.getKey());
                    System.out.printf("  %-44s %8d %6d %8d %9d %8.2f%n",
                            e.getKey(), e.getValue(),
                            pathsBySignature.getOrDefault(e.getKey(), Set.of()).size(),
                            d.episode(), d.executeIndex(),
                            1000.0 * e.getValue() / Math.max(1, totalExecutes));
                });

        System.out.println("\n--- Discovery timeline (episode of each first sighting) ---");
        firstSeen.forEach((sig, d) -> System.out.printf("  #%-2d ep %,9d  step %2d  %-44s via %s%n",
                d.order(), d.episode(), d.step(), sig, d.path()));
        if (!firstSeen.isEmpty()) {
            int last = firstSeen.values().stream().mapToInt(Discovery::episode).max().orElse(0);
            System.out.printf("  mean episodes between discoveries: %.0f | plateau since last: %,d episodes%n",
                    last / (double) firstSeen.size(), episodesRun - last);
        }

        int shown = Math.min(25, firesByPathTotal.size());
        System.out.printf("%n--- Reachability paths that produced a 500 (%d, top %d) ---%n",
                firesByPathTotal.size(), shown);
        firesByPathTotal.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(shown)
                .forEach(e -> System.out.printf("  %-56s %,10d fires%n", e.getKey(), e.getValue()));
        if (firesByPathTotal.size() > shown) {
            System.out.printf("  ... and %d more%n", firesByPathTotal.size() - shown);
        }

        System.out.println("\n--- HIDDEN DEFECT STATUS ---");
        System.out.printf("  %-68s %-9s %10s %10s %8s%n",
                "planted defect", "status", "first ep", "first req", "fires");
        for (PlantedDefect d : PLANTED) {
            Discovery hit = firstSeen.get(d.signature());
            System.out.printf("  %-68s %-9s %10s %10s %8s%n",
                    d.label(), hit == null ? "NOT FOUND" : "FOUND",
                    hit == null ? "-" : String.format("%,d", hit.episode()),
                    hit == null ? "-" : String.format("%,d", hit.executeIndex()),
                    hit == null ? "-" : String.format("%,d", firesBySignature.get(d.signature())));
        }
        for (PlantedDefect d : PLANTED) {
            Discovery hit = firstSeen.get(d.signature());
            System.out.printf("    %s%n      chain: %s%n      blind baseline: 1 in %.2e episodes -> %s%n",
                    d.signature(), d.chain(), d.blindEpisodes(),
                    hit == null
                            ? String.format("not found in %,d episodes", episodesRun)
                            : String.format("found at ep %,d, i.e. %.2e x sooner than blind search",
                                    hit.episode(), d.blindEpisodes() / Math.max(1, hit.episode())));
        }

        warnOnSharedPaths();

        long foundHidden = PLANTED.stream().filter(d -> firstSeen.containsKey(d.signature())).count();
        System.out.printf("%nSCORECARD eps=%d req=%d sigs=%d bugpaths=%d shapes=%d/%d hidden=%d/%d%s reqps=%.0f%n",
                episodesRun, totalExecutes, firesBySignature.size(), seenPaths.size(),
                executedPaths.size(), ADDRESSABLE_SHAPES, foundHidden, PLANTED.size(),
                plantedEpisodeTags(), totalExecutes / Math.max(1e-9, wallSec));
        System.out.println("#".repeat(96));
    }

    /**
     * bugPathKey() drops strategy/field for bodyless methods, so every planted defect
     * behind DELETE /points shares one path. Novelty reward is path-keyed, so only the
     * first of them pays: the rest are discoverable but reward-invisible. Worth saying
     * out loud, because it is invisible in the fires/paths columns above.
     */
    private void warnOnSharedPaths() {
        LinkedHashMap<String, List<String>> byPath = new LinkedHashMap<>();
        for (PlantedDefect d : PLANTED) {
            Set<String> paths = pathsBySignature.get(d.signature());
            if (paths == null) continue;
            for (String path : paths) {
                byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(d.label());
            }
        }
        byPath.forEach((path, labels) -> {
            if (labels.size() > 1) {
                System.out.printf("%n  NOTE: %d planted defects share the path %s, so NOVEL_BUG_REWARD%n"
                                + "        paid for the first one only - the others carry no learning signal:%n",
                        labels.size(), path);
                labels.forEach(l -> System.out.println("          - " + l));
            }
        });
    }

    private String plantedEpisodeTags() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PLANTED.size(); i++) {
            Discovery d = firstSeen.get(PLANTED.get(i).signature());
            sb.append(" d").append(i + 1).append("@ep=").append(d == null ? "none" : d.episode());
        }
        return sb.toString();
    }

    // ========================== HTTP ==========================

    record HttpResult(int status, String body) {}

    private HttpResult send(String method, String path, String json) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            HttpRequest.BodyPublisher body = json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json);
            HttpRequest req = switch (method) {
                case "POST" -> b.POST(body).build();
                case "PUT" -> b.PUT(body).build();
                case "PATCH" -> b.method("PATCH", body).build();
                case "DELETE" -> b.DELETE().build();
                default -> b.GET().build();
            };
            HttpResponse<String> rp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(rp.statusCode(), rp.body());
        } catch (Exception e) {
            transportFailureCount++;
            return null;
        }
    }

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
            case POST -> send("POST", path, payload);
            case PUT -> send("PUT", path + "/" + id, payload);
            case PATCH -> send("PATCH", path + "/" + id, payload);
            case DELETE -> send("DELETE", path + "/" + id, null);
            case GET -> send("GET", path + "/" + id, null);
            case GET_ALL -> send("GET", path, null);
            default -> null;
        };

        if (httpType == HttpType.POST && r != null && r.status() == 201) {
            setIdFor(endpoint, extractId(r.body()));
        }
        return r;
    }

    private String extractId(String body) {
        try {
            JsonNode id = MAPPER.readTree(body).path("id");
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFirstId(String body) {
        try {
            JsonNode arr = MAPPER.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) return null;
            JsonNode id = arr.get(0).path("id");
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
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
            setIdFor(endpoint, extractFirstId(response.body()));
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
