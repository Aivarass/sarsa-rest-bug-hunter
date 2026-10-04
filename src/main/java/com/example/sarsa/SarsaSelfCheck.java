package com.example.sarsa;

import com.example.ann.TinyQNetwork;
import com.example.sarsa.generator.PayloadGenerator;
import com.example.sarsa.model.State;
import com.example.sarsa.strategy.Action;
import com.example.sarsa.strategy.Endpoint;
import com.example.sarsa.strategy.HttpType;
import com.example.sarsa.strategy.StrategyBuilder;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;

/**
 * Invariant suite for the SARSA/ANN core. Runs without a live API: with the server
 * down every task reward is 0, which is exactly the condition the shaping-invariance
 * check needs. Run it after any change to the learning core.
 *
 *   mvn -q compile exec:java -Dexec.mainClass=com.example.sarsa.SarsaSelfCheck
 *
 * Exits non-zero if any invariant fails.
 */
public class SarsaSelfCheck {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("SARSA / ANN invariant suite");
        System.out.println("=".repeat(64));

        annInputDimMatchesFeatureCount();
        annGradientMatchesFiniteDifference();
        annTrunkUsesPreUpdateHeadWeights();
        annInitialStateIsNotATotalTie();
        annBreaksTiesUniformly();
        annRejectsWrongFeatureLength();

        stateAndStrategyStayInLockStep();
        executeMaskRequiresIdForIdMethods();
        maskNeverStarvesTheAgent();

        scoreTransitionIsPure();
        errorKindSeparatesBugPathsOnOneShape();
        shapeBonusPaysOnceAndIsPure();
        repeatingAKnownPathEarnsNothing();
        pathKeyExcludesNoOpDials();
        errorKindSeparatesGuardFromCrash();
        shapingIsPolicyInvariant();
        timeFeatureAdvancesWithinAStep();
        nStepUpdatesEveryStepExactlyOnce();
        nStepOneEqualsOneStepSarsa();
        nStepReturnIsDiscountedByDistance();
        repeatedRunsAreIndependent();

        System.out.println("=".repeat(64));
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ========================== ANN ==========================

    private static void annInputDimMatchesFeatureCount() {
        SarsaRestTester t = new SarsaRestTester();
        TinyQNetwork n = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 1L);
        check("ANN input dim == State.FEATURE_COUNT",
                n.getInputDim() == State.FEATURE_COUNT,
                "inputDim=" + n.getInputDim() + " featureCount=" + State.FEATURE_COUNT);
        check("State.scale() length == FEATURE_COUNT",
                freshState().scale().length == State.FEATURE_COUNT, "");
    }

    /**
     * For small alpha, Q(s,a) must move by alpha*err*||grad Q||^2. Computed from
     * scratch, so it does not depend on the implementation being "as intended".
     */
    private static void annGradientMatchesFiniteDifference() throws Exception {
        int D = 4, H = 3, A = 2;
        double[] s = {0.3, 1.0, 0.75, 0.1};
        int a = 1;
        double err = 7.0, alpha = 1e-7;

        TinyQNetwork n = new TinyQNetwork(D, H, A, 42L);
        double[][] wq = arr2(n, "wHiddenQ");
        double[] bh = arr1(n, "bHidden");
        double[][] wih = arr2(n, "wInputHidden");

        double[] hid = new double[H];
        for (int h = 0; h < H; h++) {
            double z = bh[h];
            for (int d = 0; d < D; d++) z += wih[h][d] * s[d];
            hid[h] = Math.tanh(z);
        }
        double xn = 1.0;
        for (double v : s) xn += v * v;
        double g2 = 1.0;
        for (int h = 0; h < H; h++) {
            g2 += hid[h] * hid[h];
            double tt = wq[a][h] * (1 - hid[h] * hid[h]);
            g2 += tt * tt * xn;
        }

        double qB = n.predict(s, a);
        n.applySemiGradient(s, a, err, alpha);
        double actual = n.predict(s, a) - qB;
        double predicted = alpha * err * g2;
        double ratio = actual / predicted;
        check("ANN gradient matches finite difference", Math.abs(ratio - 1.0) < 1e-5,
                String.format("ratio=%.9f", ratio));
    }

    private static void annTrunkUsesPreUpdateHeadWeights() throws Exception {
        int D = 4, H = 3, A = 2;
        double[] s = {0.3, 1.0, 0.75, 0.1};
        int a = 1;
        double err = 10.0, alpha = 0.5;   // large step: exposes the alpha^2 cross term

        TinyQNetwork n = new TinyQNetwork(D, H, A, 42L);
        double[][] wqB = arr2(n, "wHiddenQ");
        double[] bhB = arr1(n, "bHidden");
        double[][] wihB = arr2(n, "wInputHidden");
        double[] hid = new double[H];
        for (int h = 0; h < H; h++) {
            double z = bhB[h];
            for (int d = 0; d < D; d++) z += wihB[h][d] * s[d];
            hid[h] = Math.tanh(z);
        }
        n.applySemiGradient(s, a, err, alpha);
        double[] bhA = arr1(n, "bHidden");
        double[][] wihA = arr2(n, "wInputHidden");
        double[][] wqA = arr2(n, "wHiddenQ");

        double step = alpha * err, worst = 0;
        for (int h = 0; h < H; h++) {
            double chain = step * wqB[a][h] * (1 - hid[h] * hid[h]);
            worst = Math.max(worst, Math.abs((bhA[h] - bhB[h]) - chain));
            for (int d = 0; d < D; d++) {
                worst = Math.max(worst, Math.abs((wihA[h][d] - wihB[h][d]) - chain * s[d]));
            }
            worst = Math.max(worst, Math.abs((wqA[a][h] - wqB[a][h]) - step * hid[h]));
        }
        check("ANN trunk backprop uses pre-update head weights", worst < 1e-12,
                String.format("max deviation=%.3e", worst));
    }

    private static void annInitialStateIsNotATotalTie() {
        TinyQNetwork n = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 1234L);
        double[] q = n.predictAll(freshState().scale());
        int ties = 0;
        for (int i = 0; i < q.length; i++) {
            for (int j = i + 1; j < q.length; j++) if (q[i] == q[j]) ties++;
        }
        check("Initial state is not a total Q tie", ties == 0, "exact ties=" + ties);
    }

    private static void annBreaksTiesUniformly() throws Exception {
        TinyQNetwork n = new TinyQNetwork(2, 2, 4, 7L);
        Field f = TinyQNetwork.class.getDeclaredField("wHiddenQ");
        f.setAccessible(true);
        for (double[] row : (double[][]) f.get(n)) Arrays.fill(row, 0.0);

        boolean[] m = {true, true, true, true};
        int[] hist = new int[4];
        Random r = new Random(5);
        int N = 40000;
        for (int i = 0; i < N; i++) hist[n.epsilonGreedyMasked(new double[]{0.5, 0.5}, 0.0, m, r)]++;
        int lo = Arrays.stream(hist).min().orElse(0);
        check("Tie-breaking is uniform among maximizers", lo > N / 4 * 0.9,
                Arrays.toString(hist));
    }

    private static void annRejectsWrongFeatureLength() {
        TinyQNetwork n = new TinyQNetwork(State.FEATURE_COUNT, 8, 4, 1L);
        boolean threw = false;
        try {
            n.predict(new double[State.FEATURE_COUNT - 1], 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("ANN rejects a wrong-length feature vector", threw, "");
    }

    // ========================== Dials / mask ==========================

    private static void stateAndStrategyStayInLockStep() {
        StrategyBuilder sb = new StrategyBuilder();
        State st = freshState();
        sb.applyAction(Action.INSPECT_POINTS.ordinal(), st);
        sb.applyAction(Action.STRATEGY_NEGATIVE.ordinal(), st);
        sb.applyAction(Action.FOCUS_POINTS.ordinal(), st);
        sb.applyAction(Action.INTENSIFY.ordinal(), st);
        sb.applyAction(Action.EXPLORE_POST.ordinal(), st);

        sb.resetMethodOnly();
        st.resetMethodOnly();

        boolean ok = st.getEndpoint() == sb.getEndpoint().ordinal()
                && st.getCurrentStrategy() == sb.getStrategy().ordinal()
                && st.getCurrentField() == sb.getField().ordinal()
                && st.getCurrentIntensity() == sb.getIntensity().ordinal()
                && st.getHttpType() == HttpType.NONE.ordinal()
                && st.getIsReadyToExecute() == 0;
        check("State and StrategyState stay in lock-step after resetMethodOnly", ok,
                "state{ep=" + st.getEndpoint() + ",strat=" + st.getCurrentStrategy()
                        + ",field=" + st.getCurrentField() + "} builder=" + sb);
    }

    private static void executeMaskRequiresIdForIdMethods() {
        SarsaRestTester t = new SarsaRestTester();
        int EXEC = StrategyBuilder.getExecuteIndex();
        State st = freshState();

        // DELETE dialled on ITEMS with no id anywhere: EXECUTE must be blocked.
        StrategyBuilder sb = new StrategyBuilder();
        sb.applyAction(Action.EXPLORE_DELETE.ordinal(), st);
        boolean blocked = !t.getValidMask(sb)[EXEC];

        // Body-only methods must stay executable with no id at all.
        StrategyBuilder sb2 = new StrategyBuilder();
        State st2 = freshState();
        sb2.applyAction(Action.EXPLORE_POST.ordinal(), st2);
        boolean postOk = t.getValidMask(sb2)[EXEC];
        sb2.applyAction(Action.EXPLORE_GET_ALL.ordinal(), st2);
        boolean getAllOk = t.getValidMask(sb2)[EXEC];

        check("EXECUTE blocked for id-methods without an id", blocked, "");
        check("EXECUTE allowed for POST/GET_ALL without an id", postOk && getAllOk, "");
    }

    private static void maskNeverStarvesTheAgent() {
        SarsaRestTester t = new SarsaRestTester();
        StrategyBuilder sb = new StrategyBuilder();
        boolean ok = true;
        int worst = Integer.MAX_VALUE;
        for (Action a : Action.values()) {
            StrategyBuilder b = new StrategyBuilder();
            b.applyAction(a.ordinal(), freshState());
            int valid = 0;
            for (boolean v : t.getValidMask(b)) if (v) valid++;
            worst = Math.min(worst, valid);
            if (valid == 0) ok = false;
        }
        check("Mask always leaves at least one valid action", ok, "min valid=" + worst);
    }

    // ========================== Reward ==========================

    /**
     * The regression that motivated adding errorKind to the bug path key: the trivial
     * DELETE /points guard and both planted crashes share one request shape, so keying
     * novelty on shape alone let the guard absorb the reward for all three.
     */
    private static void errorKindSeparatesBugPathsOnOneShape() {
        SarsaRestTester t = new SarsaRestTester();
        StrategyBuilder s = new StrategyBuilder();
        State st = freshState();
        s.applyAction(Action.EXPLORE_DELETE.ordinal(), st);
        s.applyAction(Action.INSPECT_POINTS.ordinal(), st);

        SarsaRestTester.HttpResult guard = new SarsaRestTester.HttpResult(500, "");
        SarsaRestTester.HttpResult d1 = new SarsaRestTester.HttpResult(
                500, "{\"exception\":\"java.lang.IllegalStateException\"}");
        SarsaRestTester.HttpResult d2 = new SarsaRestTester.HttpResult(
                500, "{\"exception\":\"java.lang.ArithmeticException\"}");

        String kg = t.bugPathKey(s, guard), k1 = t.bugPathKey(s, d1), k2 = t.bugPathKey(s, d2);
        boolean distinct = !kg.equals(k1) && !kg.equals(k2) && !k1.equals(k2);
        boolean sameShape = requestShapePrefix(kg).equals(requestShapePrefix(k1))
                && requestShapePrefix(kg).equals(requestShapePrefix(k2));
        check("errorKind splits one request shape into distinct bug paths",
                distinct && sameShape, kg + " | " + k1 + " | " + k2);
    }

    private static String requestShapePrefix(String bugPath) {
        return bugPath.substring(0, bugPath.lastIndexOf('+'));
    }

    /** The exploration bonus must be first-visit only, and must not self-record. */
    private static void shapeBonusPaysOnceAndIsPure() {
        SarsaRestTester t = new SarsaRestTester();
        t.resetRunState();
        SarsaRestTester.HttpResult ok = new SarsaRestTester.HttpResult(200, "{}");
        String shape = "POST+ITEMS+VALID+ALL";

        double first = t.shapeBonus(ok, shape, true);
        double again = t.shapeBonus(ok, shape, true);
        check("shapeBonus does not mutate exploration memory",
                first == again && first == SarsaRestTester.EXPLORE_BONUS,
                "first=" + first + " again=" + again);

        check("shapeBonus pays nothing for a dial-turn or a dead request",
                t.shapeBonus(ok, shape, false) == 0.0 && t.shapeBonus(null, shape, true) == 0.0,
                "dialTurn=" + t.shapeBonus(ok, shape, false)
                        + " transportFail=" + t.shapeBonus(null, shape, true));
    }

    private static void scoreTransitionIsPure() {
        SarsaRestTester t = new SarsaRestTester();
        SarsaRestTester.HttpResult bug = new SarsaRestTester.HttpResult(500, "{}");
        String combo = "POST+ITEMS+NEGATIVE+ALL";

        SarsaRestTester.RewardOutcome a = t.scoreTransition(bug, combo, true);
        SarsaRestTester.RewardOutcome b = t.scoreTransition(bug, combo, true);
        check("scoreTransition is pure (same args -> same result)",
                a.reward() == b.reward() && a.novelBug() == b.novelBug(),
                "first=" + a.reward() + " second=" + b.reward());

        boolean empty = t.seenPaths.isEmpty();
        check("scoreTransition does not mutate novelty memory", empty,
                "seen=" + t.seenPaths);
    }

    /**
     * Behavioural, not arithmetic. The previous version asserted
     * REPEAT*maxFires < NOVEL, which passed while repeats were taking 98.8% of the
     * task reward: once discovery slows, farming only has to beat the MARGINAL
     * discovery rate, not one whole discovery.
     */
    private static void repeatingAKnownPathEarnsNothing() {
        SarsaRestTester t = new SarsaRestTester();
        SarsaRestTester.HttpResult bug = new SarsaRestTester.HttpResult(500, "");
        String path = "POST+ITEMS+NEGATIVE+ALL";

        double first = t.scoreTransition(bug, path, true).reward();
        t.seenPaths.add(path);
        double second = t.scoreTransition(bug, path, true).reward();

        check("First hit on a path pays NOVEL", first == SarsaRestTester.NOVEL_BUG_REWARD,
                "got " + first);
        check("Repeating a known path earns exactly nothing", second == 0.0,
                "got " + second + " per repeat; ~15 fires/episode makes any positive value farmable");
    }

    /** Bodyless requests carry no payload, so strategy/field must not enter the key. */
    private static void pathKeyExcludesNoOpDials() {
        SarsaRestTester t = new SarsaRestTester();
        StrategyBuilder a = new StrategyBuilder();
        State st = freshState();
        a.applyAction(Action.EXPLORE_DELETE.ordinal(), st);
        a.applyAction(Action.STRATEGY_NEGATIVE.ordinal(), st);
        a.applyAction(Action.FOCUS_QUANTITY.ordinal(), st);
        String k1 = t.requestShapeKey(a);

        StrategyBuilder b = new StrategyBuilder();
        State st2 = freshState();
        b.applyAction(Action.EXPLORE_DELETE.ordinal(), st2);
        b.applyAction(Action.STRATEGY_ENCODING.ordinal(), st2);
        b.applyAction(Action.FOCUS_UNKNOWN.ordinal(), st2);
        String k2 = t.requestShapeKey(b);

        check("DELETE paths ignore strategy/field (byte-identical requests)",
                k1.equals(k2), k1 + " vs " + k2);

        StrategyBuilder c = new StrategyBuilder();
        State st3 = freshState();
        c.applyAction(Action.EXPLORE_POST.ordinal(), st3);
        c.applyAction(Action.STRATEGY_NEGATIVE.ordinal(), st3);
        c.applyAction(Action.FOCUS_QUANTITY.ordinal(), st3);
        String k3 = t.requestShapeKey(c);
        check("POST paths DO include strategy/field (payload differs)",
                k3.contains("NEGATIVE") && k3.contains("QUANTITY"), k3);
    }

    /** GUARD (empty body) and CRASH:<class> must be separable. */
    private static void errorKindSeparatesGuardFromCrash() {
        SarsaRestTester t = new SarsaRestTester();
        String guard = t.errorKind(new SarsaRestTester.HttpResult(500, ""));
        String npe = t.errorKind(new SarsaRestTester.HttpResult(500,
                "{\"status\":500,\"exception\":\"java.lang.NullPointerException\",\"path\":\"/api/items\"}"));
        String unknown = t.errorKind(new SarsaRestTester.HttpResult(500,
                "{\"status\":500,\"error\":\"Internal Server Error\"}"));
        check("errorKind: empty body -> GUARD", guard.equals("GUARD"), guard);
        check("errorKind: exception -> CRASH:<class>", npe.equals("CRASH:NullPointerException"), npe);
        check("errorKind: error JSON without class -> CRASH:unknown",
                unknown.equals("CRASH:unknown"), unknown);
    }

    /**
     * Potential-based shaping must contribute exactly -Phi(s_0) = 0 to the discounted
     * return, for any policy. Measured on episodes that produced no bug at all, where
     * the task reward is identically zero.
     */
    private static void shapingIsPolicyInvariant() {
        double worst = 0;
        int tested = 0;
        for (double eps : new double[]{0.01, 0.25, 1.00}) {
            SarsaRestTester t = new SarsaRestTester();
            t.pbt = new PayloadGenerator(1234);
            t.ann = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 1234L);
            t.EPSILON = eps;
            t.resetRunState();
            Random rng = new Random(3);
            for (int e = 0; e < 25; e++) {
                int seenBefore = t.seenPaths.size();
                double[] res = t.executeEpisode(rng);
                boolean cleanEpisode = res[2] == 0.0 && t.seenPaths.size() == seenBefore;
                if (!cleanEpisode) continue;
                double disc = 0;
                for (int i = 0; i < t.lastEpisodeRewards.size(); i++) {
                    disc += Math.pow(SarsaRestTester.GAMMA, i) * t.lastEpisodeRewards.get(i);
                }
                worst = Math.max(worst, Math.abs(disc));
                tested++;
            }
        }
        check("Shaping is policy-invariant (discounted return 0 on bug-free episodes)",
                tested > 0 && worst < 1e-12,
                String.format("episodes tested=%d  max|discounted return|=%.3e", tested, worst));
    }

    private static void timeFeatureAdvancesWithinAStep() {
        SarsaRestTester t = new SarsaRestTester();
        t.pbt = new PayloadGenerator(1234);
        t.ann = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 1234L);
        t.EPSILON = 1.0;
        t.resetRunState();
        t.executeEpisode(new Random(11));

        boolean advances = true, differs = false;
        for (double[] p : t.lastEpisodeTimePairs) {
            if (p[0] <= p[1]) advances = false;          // remaining budget must decrease
            if (p[0] != p[1]) differs = true;
        }
        check("Time feature strictly decreases from s to s'", advances && differs,
                "pairs=" + t.lastEpisodeTimePairs.size());

        boolean lastIsZero = !t.lastEpisodeTimePairs.isEmpty()
                && t.lastEpisodeTimePairs.get(t.lastEpisodeTimePairs.size() - 1)[1] == 0.0;
        check("Time feature is 0 in the terminal s'", lastIsZero, "");
    }

    /**
     * Three classic n-step bugs, all of which would pass a smoke test:
     * dropping the tail flush, double-updating the overlap, and getting the
     * discounting or the bootstrap term wrong.
     */
    private static void nStepUpdatesEveryStepExactlyOnce() {
        // Swept over STEP_LIMIT because the tail flush bounds are written in terms of it,
        // and the long-episode arms run 70 and 140. A flush that is correct at 35 can
        // still drop or duplicate updates at another length.
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        for (int steps : new int[]{3, 35, 70, 140, 280, 560, 750, 1120}) {
            SarsaRestTester t = new SarsaRestTester();
            t.pbt = new PayloadGenerator(1234);
            t.ann = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 1234L);
            t.EPSILON = 1.0;
            t.STEP_LIMIT = steps;
            t.resetRunState();
            t.executeEpisode(new Random(5));
            if (t.lastEpisodeUpdates != steps) ok = false;
            detail.append(steps).append(":").append(t.lastEpisodeUpdates).append(' ');
        }
        check("n-step updates every state-action exactly once, at every STEP_LIMIT",
                ok, "steps:updates = " + detail + "(n=" + SarsaRestTester.N_STEP + ")");
    }

    /** n = 1 must be numerically identical to the one-step SARSA update. */
    private static void nStepOneEqualsOneStepSarsa() {
        double[] s0 = new double[State.FEATURE_COUNT];
        double[] s1 = new double[State.FEATURE_COUNT];
        for (int i = 0; i < s0.length; i++) { s0[i] = 0.1 * i; s1[i] = 0.05 * i + 0.2; }
        double r = 0.7, gamma = SarsaRestTester.GAMMA, alpha = 0.05;

        TinyQNetwork a = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 99L);
        TinyQNetwork b = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 99L);
        a.sarsaUpdate(s0, 3, r, s1, 7, false, alpha, gamma);
        // the n = 1 return: G = R + gamma^1 * Q(S', A')
        b.updateToward(s0, 3, r + gamma * b.predict(s1, 7), alpha);
        check("n=1 n-step return == one-step SARSA target",
                Math.abs(a.predict(s0, 3) - b.predict(s0, 3)) < 1e-12,
                "a=" + a.predict(s0, 3) + " b=" + b.predict(s0, 3));

        TinyQNetwork c = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 99L);
        TinyQNetwork d = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 99L);
        c.sarsaUpdate(s0, 3, r, s1, 7, true, alpha, gamma);
        d.updateToward(s0, 3, r, alpha);
        check("terminal n=1 target drops the bootstrap",
                Math.abs(c.predict(s0, 3) - d.predict(s0, 3)) < 1e-12, "");
    }

    /** The n-step return must be gamma-discounted by distance, not flat. */
    private static void nStepReturnIsDiscountedByDistance() {
        double g1 = 0, g2 = 0;
        double[] rewards = {0.0, 0.0, 0.0, 1.0};   // reward arrives 4 steps out
        double gamma = SarsaRestTester.GAMMA;
        for (int k = rewards.length - 1; k >= 0; k--) g1 = rewards[k] + gamma * g1;
        g2 = Math.pow(gamma, 3);                    // closed form: gamma^3 * 1.0
        check("n-step return discounts by distance (Horner == closed form)",
                Math.abs(g1 - g2) < 1e-12, "horner=" + g1 + " closed=" + g2);
        check("distant credit is attenuated, not equal",
                g1 < 1.0 && g1 > 0.9, "g=" + g1);
    }

    private static void repeatedRunsAreIndependent() {
        SarsaRestTester t = new SarsaRestTester();
        t.pbt = new PayloadGenerator(1234);
        t.ann = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 1234L);
        t.EPSILON = 0.5;

        int[] novel = new int[2];
        for (int run = 0; run < 2; run++) {
            t.resetRunState();
            t.ann = new TinyQNetwork(State.FEATURE_COUNT, 16, 32, 1234L);
            t.pbt = new PayloadGenerator(1234);
            Random rng = new Random(77);
            int n = 0;
            for (int e = 0; e < 30; e++) n += (int) t.executeEpisode(rng)[1];
            novel[run] = n;
        }
        check("Two runs on one instance are independent", novel[0] == novel[1],
                "run1=" + novel[0] + " run2=" + novel[1]);
    }

    // ========================== helpers ==========================

    private static State freshState() {
        return new State(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.0);
    }

    private static double[][] arr2(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        double[][] a = (double[][]) f.get(o);
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) c[i] = a[i].clone();
        return c;
    }

    private static double[] arr1(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return ((double[]) f.get(o)).clone();
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  PASS  %s%n", name);
        } else {
            failed++;
            System.out.printf("  FAIL  %s%n          %s%n", name, detail);
        }
    }
}
