package com.example.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * All measurement state and all reporting, shared by every arm.
 *
 * Moved out of the RL tester so that the RL arm and the RESTler arm are scored by the
 * same code rather than by two implementations that agree today and drift tomorrow.
 * "Found Defect 1 at request 41,203" therefore means the same thing in every column of
 * the results table.
 *
 * The recorder is deliberately passive: it records what an arm did and never influences
 * what an arm does next. The one exception is {@link #isNovelPath}, which the RL arm
 * reads to compute its reward - a read, not a write, with recording still done by
 * {@link #recordBug}.
 */
public class RunRecorder {

    // ==================== Hidden-defect bookkeeping ====================

    /**
     * The deliberately planted defects, identified by {@link BugOracle#bugSignature}.
     * Knowing what was planted is what lets a run report *status* ("did it find them?")
     * instead of only "here is a pile of 500s".
     *
     * blindEpisodes = expected episodes to the first hit for a uniform-random policy
     * over the RL arm's valid action mask, at STEP_LIMIT=35 on a fresh server, measured
     * by step-aware multilevel splitting over a faithful model of the API + generator
     * + mask. It is an RL-arm reference point and is reported, not used, by other arms.
     */
    public record PlantedDefect(String signature, String label, String chain, double blindEpisodes) {}

    public static final List<PlantedDefect> PLANTED = List.of(
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
     * First sighting of a signature.
     *
     * {@code httpIndex} is the count of tester-issued HTTP requests at the moment of the
     * find - the budget-denominated discovery time, and the axis on which arms are
     * compared. {@code phase} is the arm's own progress counter (RL: episode;
     * RESTler: generation), kept because it is what earlier runs reported.
     */
    public record Discovery(int order, int phase, int step, long httpIndex, String path) {}

    // ==================== Run-lifetime state ====================

    /** Measurement only; cleared each log window. Keyed by reachability path. */
    private final LinkedHashMap<String, Integer> firesByPath = new LinkedHashMap<>();
    /** Novelty memory, keyed by reachability path. Window-scoped (see clearNovelty). */
    private final Set<String> seenPaths = new HashSet<>();
    /** Real bugs: signature -> total fires. Run lifetime. */
    private final LinkedHashMap<String, Integer> firesBySignature = new LinkedHashMap<>();
    /** Real bugs: signature -> distinct paths that reach it. Run lifetime. */
    private final LinkedHashMap<String, Set<String>> pathsBySignature = new LinkedHashMap<>();
    /** First sighting per signature. Run lifetime. */
    private final LinkedHashMap<String, Discovery> firstSeen = new LinkedHashMap<>();
    /** Every request shape actually sent, bug or not - exploration coverage. Run lifetime. */
    private final Set<String> executedPaths = new HashSet<>();
    /** Path -> fires over the whole run (firesByPath is window-scoped). */
    private final LinkedHashMap<String, Integer> firesByPathTotal = new LinkedHashMap<>();
    /** Request shapes that produced a 500 at least once - keyed by shape, not bug path. */
    private final Set<String> shapesThat500 = new HashSet<>();

    private long totalBugFires = 0;
    /** Run-lifetime novel-path count; seenPaths is window-scoped and cannot serve this. */
    private long totalNovelPaths = 0;
    private int serverResets = 0;
    private long runStartMillis = System.currentTimeMillis();

    // Chain-depth diagnostics (measurement only; see sampleChainDepth).
    private int maxD1Armed = 0, maxD2Armed = 0, windowsD1Armed = 0, windowsD2Armed = 0, chainSamples = 0;
    /**
     * Upstream arming: stored prices and discounts that are negative, whether or not a
     * Point was ever linked to them.
     *
     * Splits "never poisoned anything" from "poisoned a price but never built a Point on
     * it" - two completely different failures that {@link #maxD1Armed} alone reports
     * identically. Read from GET /api/prices and GET /api/discounts, responses any
     * client receives, and fed to nothing.
     */
    private int maxNegPrices = 0, maxNegDiscounts = 0, windowsNegPrice = 0, windowsNegDiscount = 0;

    /** The arm's own progress counter at the moment of a record (episode / generation). */
    private int phase = 0;

    private final HttpTransport http;
    /** Set by the arm; printed in the Coverage block only when the arm pays one. */
    private double exploreReward = 0;
    private int shapesPaid = 0;
    private boolean reportsExploreReward = false;
    private int windowSize = 0;
    private String windowUnit = "units";

    public RunRecorder(HttpTransport http) {
        this.http = http;
    }

    // ==================== Recording ====================

    public void setPhase(int phase) {
        this.phase = phase;
    }

    public int phase() {
        return phase;
    }

    /** Window cadence for the summary line; {@code unit} is the arm's own unit name. */
    public void setWindow(int windowSize, String unit) {
        this.windowSize = windowSize;
        this.windowUnit = unit;
    }

    public void setExploreReward(double total, int paid) {
        this.exploreReward = total;
        this.shapesPaid = paid;
        this.reportsExploreReward = true;
    }

    /** Pure read of novelty memory. Recording happens in {@link #recordBug}. */
    public boolean isNovelPath(String path) {
        return !seenPaths.contains(path);
    }

    /** Exploration memory: has this request shape ever been sent? Pure read. */
    public boolean isNovelShape(String requestShape) {
        return !executedPaths.contains(requestShape);
    }

    /** Records that a request shape was sent. Mutate only after scoring. */
    public void recordShape(String requestShape) {
        executedPaths.add(requestShape);
    }

    /**
     * Records a 500. {@code step} is the position within the arm's current unit of work
     * (RL: step in episode; RESTler: index in the rendered sequence).
     */
    public void recordBug(String path, String signature, boolean novel, int step) {
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
        shapesThat500.add(BugOracle.requestShapeOf(path));
        if (newSignature) {
            firstSeen.put(signature,
                    new Discovery(firstSeen.size() + 1, phase, step, http.fuzzRequests(), path));
            logDiscovery(signature, path, step);
        }
    }

    /**
     * Bug novelty is scoped to the window because the world it refers to was just
     * cleared. Total novelty reward stays bounded at windows x paths, so it is still
     * not farmable. Exploration memory ({@link #executedPaths}) is deliberately NOT
     * cleared: shape coverage saturates, so re-paying it would only add noise.
     */
    public void clearNovelty() {
        seenPaths.clear();
    }

    /** Per-window measurement reset. Run-lifetime tallies are untouched. */
    public void clearWindow() {
        firesByPath.clear();
    }

    // ==================== Environment ====================

    /**
     * Clears all four collections on the server, bounding both GET_ALL cost and
     * cross-run state carry-over. Identical for every arm, and charged to no arm:
     * these go out over {@link HttpTransport#harness}.
     *
     * Note: the controllers' id counters are NOT reset by deleteAll, so after the first
     * window the fabricated ids (1-1000) can no longer collide with a live object. That
     * is consistent for the rest of the run, and identical across arms.
     */
    public void resetServerState() {
        sampleChainDepth();
        for (String collection : new String[]{"items", "prices", "discounts", "points"}) {
            http.harness("DELETE", collection, null);
        }
        serverResets++;
    }

    /**
     * MEASUREMENT ONLY - never feeds reward, state, or any arm's search.
     *
     * Answers one question the discovery counters cannot: when a hidden defect is not
     * found, did the arm fail to *arm* the chain, or arm it and never fire the DELETE?
     * GET /api/points already returns the whole nested chain, so this needs no change to
     * the system under test and no privileged access - it reads a response any client
     * could have.
     *
     * Sampled at window boundaries only, so a chain armed and destroyed inside a window
     * is missed. It is therefore a lower bound on arming.
     */
    public void sampleChainDepth() {
        sampleNegativeValues("prices", "price", true);
        sampleNegativeValues("discounts", "discount", false);
        HttpResult r = http.harness("GET", "points", null);
        if (r == null || r.status() != 200 || r.body() == null) return;
        try {
            JsonNode arr = BugOracle.mapper().readTree(r.body());
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

    /** Counts stored objects whose own numeric field is negative. Measurement only. */
    private void sampleNegativeValues(String collection, String field, boolean isPrice) {
        HttpResult r = http.harness("GET", collection, null);
        if (r == null || r.status() != 200 || r.body() == null) return;
        try {
            JsonNode arr = BugOracle.mapper().readTree(r.body());
            if (!arr.isArray()) return;
            int n = 0;
            for (JsonNode o : arr) {
                if (o.path(field).asDouble(0) < 0) n++;
            }
            if (isPrice) {
                maxNegPrices = Math.max(maxNegPrices, n);
                if (n > 0) windowsNegPrice++;
            } else {
                maxNegDiscounts = Math.max(maxNegDiscounts, n);
                if (n > 0) windowsNegDiscount++;
            }
        } catch (Exception ignored) {
            // diagnostic only
        }
    }

    /**
     * A final arming sample at the end of a run.
     *
     * Window-boundary samples miss the last window entirely, and the first boundary
     * always sees an empty store, so without this the deepest state an arm reached can
     * go unrecorded. Called by every arm under the request-denominated protocol.
     */
    public void finalSample() {
        sampleChainDepth();
    }

    // ==================== Accessors ====================

    public Map<String, Integer> firesByPath() { return firesByPath; }
    public Map<String, Integer> firesBySignature() { return firesBySignature; }
    public Map<String, Set<String>> pathsBySignature() { return pathsBySignature; }
    public Map<String, Discovery> firstSeen() { return firstSeen; }
    public Set<String> seenPaths() { return seenPaths; }
    public Set<String> executedPaths() { return executedPaths; }
    public long totalBugFires() { return totalBugFires; }
    public long totalNovelPaths() { return totalNovelPaths; }
    public int serverResets() { return serverResets; }
    public int maxD1Armed() { return maxD1Armed; }
    public int maxD2Armed() { return maxD2Armed; }
    public int windowsD1Armed() { return windowsD1Armed; }
    public int windowsD2Armed() { return windowsD2Armed; }
    public int chainSamples() { return chainSamples; }
    public int maxNegPrices() { return maxNegPrices; }
    public int maxNegDiscounts() { return maxNegDiscounts; }
    public int windowsNegPrice() { return windowsNegPrice; }
    public int windowsNegDiscount() { return windowsNegDiscount; }
    public double wallSeconds() { return (System.currentTimeMillis() - runStartMillis) / 1000.0; }

    /** First-fire request index for a planted defect, or null if never found. */
    public Discovery discovery(String signature) {
        return firstSeen.get(signature);
    }

    public void markRunStart() {
        runStartMillis = System.currentTimeMillis();
    }

    /** Clears all run-scoped state so repeated runs on one instance are independent. */
    public void resetRunState() {
        firesByPath.clear();
        seenPaths.clear();
        firesBySignature.clear();
        pathsBySignature.clear();
        firstSeen.clear();
        executedPaths.clear();
        firesByPathTotal.clear();
        shapesThat500.clear();
        totalBugFires = 0;
        totalNovelPaths = 0;
        serverResets = 0;
        maxD1Armed = 0;
        maxD2Armed = 0;
        windowsD1Armed = 0;
        windowsD2Armed = 0;
        chainSamples = 0;
        maxNegPrices = 0;
        maxNegDiscounts = 0;
        windowsNegPrice = 0;
        windowsNegDiscount = 0;
        phase = 0;
        exploreReward = 0;
        shapesPaid = 0;
        reportsExploreReward = false;
        runStartMillis = System.currentTimeMillis();
    }

    // ==================== Discovery logging ====================

    /**
     * First-sighting banner, printed inline so a discovery is visible the moment it
     * happens instead of only in the end-of-run summary. Planted defects get the full
     * banner; anything else gets a one-liner, since an unplanted 500 is still worth
     * seeing.
     */
    private void logDiscovery(String signature, String path, int step) {
        PlantedDefect planted = plantedFor(signature);
        if (planted == null) {
            System.out.printf("%n  [new bug] ep %,d step %2d  %-44s via %s%n",
                    phase, step, signature, path);
            return;
        }
        String bar = "  " + "*".repeat(86);
        System.out.println();
        System.out.println(bar);
        System.out.printf("  *** HIDDEN DEFECT FOUND -> %s%n", planted.label());
        System.out.printf("  *** signature : %s%n", signature);
        System.out.printf("  *** found at  : episode %,d  step %d  execute #%,d%n",
                phase, step, http.fuzzRequests());
        System.out.printf("  *** via path  : %s%n", path);
        System.out.printf("  *** chain     : %s%n", planted.chain());
        System.out.printf("  *** blind baseline 1-in-%.2e episodes => found %.2e x sooner%n",
                planted.blindEpisodes(), planted.blindEpisodes() / Math.max(1, phase));
        System.out.println(bar);
        System.out.println();
    }

    public static PlantedDefect plantedFor(String signature) {
        return PLANTED.stream()
                .filter(d -> d.signature().equals(signature))
                .findFirst()
                .orElse(null);
    }

    /** One-line hidden-defect status for the per-window log. */
    public String hiddenDefectStatusLine() {
        StringBuilder sb = new StringBuilder("Hidden defects: ");
        long found = PLANTED.stream().filter(d -> firstSeen.containsKey(d.signature())).count();
        sb.append(found).append('/').append(PLANTED.size()).append(" found");
        for (int i = 0; i < PLANTED.size(); i++) {
            Discovery d = firstSeen.get(PLANTED.get(i).signature());
            sb.append(" | D").append(i + 1).append(": ")
              .append(d == null ? "-" : String.format("ep %,d (%,d fires)",
                      d.phase(), firesBySignature.get(PLANTED.get(i).signature())));
        }
        return sb.toString();
    }

    // ==================== End-of-run summary ====================

    public void printSummaryHeader() {
        System.out.println("\n\n" + "#".repeat(96));
        System.out.println("RUN SUMMARY");
        System.out.println("#".repeat(96));
    }

    /**
     * Everything from Coverage onward. The arm prints its own Throughput block before
     * calling this, because "episodes" and "generations" are not the same unit - but
     * every number below is arm-independent and must be produced by one code path.
     */
    public void printCoverageOnward() {
        long guards = firesBySignature.keySet().stream().filter(k -> k.endsWith("GUARD")).count();
        long crashes = firesBySignature.size() - guards;

        System.out.println("\n--- Coverage ---");
        System.out.printf("  request shapes hit  : %,d / %,d addressable (%.1f%%)%n",
                executedPaths.size(), BugOracle.ADDRESSABLE_SHAPES,
                100.0 * executedPaths.size() / BugOracle.ADDRESSABLE_SHAPES);
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
        if (reportsExploreReward) {
            System.out.printf("  exploration reward  : %.1f paid over %,d first visits (cap %.1f)%n",
                    exploreReward, shapesPaid, BugOracle.ADDRESSABLE_SHAPES * 0.1);
        }
        System.out.printf("  server resets       : %,d (every %,d %s)%n",
                serverResets, windowSize, windowUnit);
        System.out.printf("  chain armed at window boundaries (sampled, lower bound):%n");
        System.out.printf("    D1-armed points: max %d, seen in %d/%d samples%n",
                maxD1Armed, windowsD1Armed, chainSamples);
        System.out.printf("    D2-armed points: max %d, seen in %d/%d samples%n",
                maxD2Armed, windowsD2Armed, chainSamples);
        System.out.printf("    upstream: negative prices max %d (%d samples), "
                        + "negative discounts max %d (%d samples)%n",
                maxNegPrices, windowsNegPrice, maxNegDiscounts, windowsNegDiscount);
        System.out.printf("  hit rate            : %.2f%% of requests returned 500%n",
                100.0 * totalBugFires / Math.max(1, http.fuzzRequests()));

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
                            d.phase(), d.httpIndex(),
                            1000.0 * e.getValue() / Math.max(1, http.fuzzRequests()));
                });

        System.out.println("\n--- Discovery timeline (episode of each first sighting) ---");
        firstSeen.forEach((sig, d) -> System.out.printf("  #%-2d ep %,9d  step %2d  %-44s via %s%n",
                d.order(), d.phase(), d.step(), sig, d.path()));
        if (!firstSeen.isEmpty()) {
            int last = firstSeen.values().stream().mapToInt(Discovery::phase).max().orElse(0);
            System.out.printf("  mean episodes between discoveries: %.0f | plateau since last: %,d episodes%n",
                    last / (double) firstSeen.size(), phase - last);
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

        printHiddenDefectStatus();
        warnOnSharedPaths();
    }

    private void printHiddenDefectStatus() {
        System.out.println("\n--- HIDDEN DEFECT STATUS ---");
        System.out.printf("  %-68s %-9s %10s %10s %8s%n",
                "planted defect", "status", "first ep", "first req", "fires");
        for (PlantedDefect d : PLANTED) {
            Discovery hit = firstSeen.get(d.signature());
            System.out.printf("  %-68s %-9s %10s %10s %8s%n",
                    d.label(), hit == null ? "NOT FOUND" : "FOUND",
                    hit == null ? "-" : String.format("%,d", hit.phase()),
                    hit == null ? "-" : String.format("%,d", hit.httpIndex()),
                    hit == null ? "-" : String.format("%,d", firesBySignature.get(d.signature())));
        }
        for (PlantedDefect d : PLANTED) {
            Discovery hit = firstSeen.get(d.signature());
            // Prefixed, because a bare signature string here is printed whether or not
            // the defect was found - which makes a naive grep over the log report every
            // planted defect as discovered. Bare signatures now appear only in the
            // discoveries table and the first-sighting banner.
            System.out.printf("    expects-signature: %s%n      chain: %s%n      blind baseline: 1 in %.2e episodes -> %s%n",
                    d.signature(), d.chain(), d.blindEpisodes(),
                    hit == null
                            ? String.format("not found in %,d episodes", phase)
                            : String.format("found at ep %,d, i.e. %.2e x sooner than blind search",
                                    hit.phase(), d.blindEpisodes() / Math.max(1, hit.phase())));
        }
    }

    /**
     * {@link BugOracle#bugPathKey} drops strategy/field for bodyless methods, so every
     * planted defect behind DELETE /points shares one path. Novelty is path-keyed, so
     * only the first of them pays: the rest are discoverable but reward-invisible.
     * Worth saying out loud, because it is invisible in the fires/paths columns.
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

    /** Tags for the SCORECARD line: which planted defect was found when. */
    public String plantedPhaseTags() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PLANTED.size(); i++) {
            Discovery d = firstSeen.get(PLANTED.get(i).signature());
            sb.append(" d").append(i + 1).append("@ep=").append(d == null ? "none" : d.phase());
        }
        return sb.toString();
    }

    public void printSummaryFooter() {
        System.out.println("#".repeat(96));
    }
}
