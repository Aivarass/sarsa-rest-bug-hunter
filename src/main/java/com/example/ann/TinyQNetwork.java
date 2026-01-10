package com.example.ann;

import java.util.Random;

/**
 * Tiny shallow Q-Network for SARSA / Q-learning.
 *
 * Architecture:
 *   input[D] → tanh hidden[H] → Q-values[A] (linear output, one per action)
 *
 * Usage:
 *   TinyQNetwork q = new TinyQNetwork(inputDim, hiddenUnits, actionCount);
 *   double qValue = q.predict(state, action);
 *   double[] allQ  = q.predictAll(state);
 *   q.applySemiGradient(state, action, tdError, alpha);
 *
 * This is a generic value-function approximator:
 *   - State is double[] of any dimension
 *   - Actions are integers 0..actionCount-1
 *   - No domain-specific knowledge baked in
 */
public class TinyQNetwork {

    private final int inputDim;
    private final int hiddenUnits;
    private final int actionCount;

    // Trunk: hidden = tanh(Wih * x + bh)
    private final double[][] wInputHidden;  // [H][D]
    private final double[] bHidden;         // [H]

    // Q-head: Q[a] = Wq[a] · hidden + bq[a]
    private final double[][] wHiddenQ;      // [A][H]
    private final double[] bQ;              // [A]

    // Scratch buffers (avoid allocations)
    private final double[] hidden;          // [H]
    private final double[] qValues;         // [A]

    private final Random rng;

    // ========================== Constructors ==========================

    public TinyQNetwork(int inputDim, int hiddenUnits, int actionCount, long seed) {
        if (inputDim <= 0 || hiddenUnits <= 0 || actionCount <= 0) {
            throw new IllegalArgumentException("All dimensions must be > 0");
        }

        this.inputDim = inputDim;
        this.hiddenUnits = hiddenUnits;
        this.actionCount = actionCount;
        this.rng = new Random(seed);

        this.wInputHidden = new double[hiddenUnits][inputDim];
        this.bHidden = new double[hiddenUnits];

        this.wHiddenQ = new double[actionCount][hiddenUnits];
        this.bQ = new double[actionCount];

        this.hidden = new double[hiddenUnits];
        this.qValues = new double[actionCount];

        initWeightsXavier();
    }

    public TinyQNetwork(int inputDim, int hiddenUnits, int actionCount) {
        this(inputDim, hiddenUnits, actionCount, System.nanoTime());
    }

    // ========================== Initialization ==========================

    private void initWeightsXavier() {
        // Xavier init for tanh: variance = 2 / (fan_in + fan_out)
        double limitIH = Math.sqrt(6.0 / (inputDim + hiddenUnits));
        for (int h = 0; h < hiddenUnits; h++) {
            for (int d = 0; d < inputDim; d++) {
                wInputHidden[h][d] = uniform(-limitIH, limitIH);
            }
            bHidden[h] = 0.0;
        }

        // Q-head: linear output
        double limitHQ = Math.sqrt(6.0 / (hiddenUnits + actionCount));
        for (int a = 0; a < actionCount; a++) {
            for (int h = 0; h < hiddenUnits; h++) {
                wHiddenQ[a][h] = uniform(-limitHQ, limitHQ);
            }
            bQ[a] = 0.0;
        }
    }

    private double uniform(double lo, double hi) {
        return lo + (hi - lo) * rng.nextDouble();
    }

    // ========================== Inference ==========================

    /**
     * Returns Q(s, a) for a single action.
     */
    public double predict(double[] state, int action) {
        forwardHidden(state);
        return computeQ(action);
    }

    /**
     * Returns Q(s, ·) for all actions. Returns a fresh array.
     */
    public double[] predictAll(double[] state) {
        forward(state);
        double[] result = new double[actionCount];
        System.arraycopy(qValues, 0, result, 0, actionCount);
        return result;
    }

    /**
     * Returns the action with highest Q-value (greedy).
     */
    public int argmax(double[] state) {
        forward(state);
        int best = 0;
        for (int a = 1; a < actionCount; a++) {
            if (qValues[a] > qValues[best]) best = a;
        }
        return best;
    }

    /**
     * Returns max_a Q(s, a).
     */
    public double maxQ(double[] state) {
        forward(state);
        double max = qValues[0];
        for (int a = 1; a < actionCount; a++) {
            if (qValues[a] > max) max = qValues[a];
        }
        return max;
    }

    // ========================== Learning ==========================

    /**
     * Semi-gradient update for SARSA/Q-learning.
     *
     * Applies:
     *   w += alpha * error * ∇_w Q(s, a)
     *
     * Where error = (target - Q(s,a)) for SARSA/Q-learning.
     *
     * @param state  current state features
     * @param action action taken
     * @param error  TD error: (r + γ·Q(s',a') - Q(s,a)) for SARSA
     *                     or (r + γ·max Q(s',·) - Q(s,a)) for Q-learning
     * @param alpha  learning rate
     */
    public void applySemiGradient(double[] state, int action, double error, double alpha) {
        // Forward pass to get hidden activations
        forwardHidden(state);

        // Optionally clip error for stability
        double errClip = clip(error, -10.0, 10.0);
        double step = alpha * errClip;

        // --- Update Q-head weights for the chosen action ---
        // Q[a] = Wq[a] · hidden + bq[a]
        // ∂Q[a]/∂Wq[a][h] = hidden[h]
        // ∂Q[a]/∂bq[a] = 1
        for (int h = 0; h < hiddenUnits; h++) {
            wHiddenQ[action][h] += step * hidden[h];
        }
        bQ[action] += step;

        // --- Backprop into trunk ---
        // ∂Q[a]/∂hidden[h] = Wq[a][h]
        // ∂hidden[h]/∂z[h] = 1 - hidden[h]^2 (tanh derivative)
        for (int h = 0; h < hiddenUnits; h++) {
            double dQ_dHidden = wHiddenQ[action][h];
            double dHidden_dZ = 1.0 - hidden[h] * hidden[h];
            double chain = step * dQ_dHidden * dHidden_dZ;

            for (int d = 0; d < inputDim; d++) {
                wInputHidden[h][d] += chain * state[d];
            }
            bHidden[h] += chain;
        }
    }

    /**
     * Convenience method: full SARSA update given (s, a, r, s', a', terminal).
     *
     * Computes: error = r + γ·Q(s',a') - Q(s,a)  (0 if terminal)
     * Then calls applySemiGradient.
     *
     * @return the TD error (useful for logging)
     */
    public double sarsaUpdate(double[] s, int a, double r,
                              double[] sNext, int aNext,
                              boolean terminal,
                              double alpha, double gamma) {
        double qSA = predict(s, a);
        double qNext = terminal ? 0.0 : predict(sNext, aNext);
        double target = r + gamma * qNext;
        double error = target - qSA;

        applySemiGradient(s, a, error, alpha);
        return error;
    }

    /**
     * Convenience method: Q-learning update given (s, a, r, s', terminal).
     *
     * Computes: error = r + γ·max_a' Q(s',a') - Q(s,a)
     * Then calls applySemiGradient.
     *
     * @return the TD error (useful for logging)
     */
    public double qLearningUpdate(double[] s, int a, double r,
                                  double[] sNext,
                                  boolean terminal,
                                  double alpha, double gamma) {
        double qSA = predict(s, a);
        double maxQNext = terminal ? 0.0 : maxQ(sNext);
        double target = r + gamma * maxQNext;
        double error = target - qSA;

        applySemiGradient(s, a, error, alpha);
        return error;
    }

    // ========================== Action Selection ==========================

    /**
     * Epsilon-greedy action selection.
     * With probability epsilon: random action.
     * Otherwise: argmax Q(s, ·).
     */
    public int epsilonGreedy(double[] state, double epsilon, Random rng) {
        if (rng.nextDouble() < epsilon) {
            return rng.nextInt(actionCount);
        }
        return argmax(state);
    }

    /**
     * Epsilon-greedy with action masking.
     * Invalid actions (mask[a] = false) are excluded from both random and greedy selection.
     */
    public int epsilonGreedyMasked(double[] state, double epsilon, boolean[] validMask, Random rng) {
        // Count valid actions
        int validCount = 0;
        for (int a = 0; a < actionCount; a++) {
            if (validMask[a]) validCount++;
        }
        if (validCount == 0) {
            throw new IllegalStateException("No valid actions available");
        }

        if (rng.nextDouble() < epsilon) {
            // Random among valid
            int pick = rng.nextInt(validCount);
            int count = 0;
            for (int a = 0; a < actionCount; a++) {
                if (validMask[a]) {
                    if (count == pick) return a;
                    count++;
                }
            }
        }

        // Greedy among valid
        forward(state);
        int best = -1;
        double bestQ = Double.NEGATIVE_INFINITY;
        for (int a = 0; a < actionCount; a++) {
            if (validMask[a] && qValues[a] > bestQ) {
                bestQ = qValues[a];
                best = a;
            }
        }
        return best;
    }

    // ========================== Forward Helpers ==========================

    private void forward(double[] x) {
        forwardHidden(x);
        for (int a = 0; a < actionCount; a++) {
            qValues[a] = computeQ(a);
        }
    }

    private void forwardHidden(double[] x) {
        for (int h = 0; h < hiddenUnits; h++) {
            double z = bHidden[h];
            for (int d = 0; d < inputDim; d++) {
                z += wInputHidden[h][d] * x[d];
            }
            hidden[h] = Math.tanh(z);
        }
    }

    private double computeQ(int action) {
        double q = bQ[action];
        for (int h = 0; h < hiddenUnits; h++) {
            q += wHiddenQ[action][h] * hidden[h];
        }
        return q;
    }

    private static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    // ========================== Getters ==========================

    public int getInputDim() { return inputDim; }
    public int getHiddenUnits() { return hiddenUnits; }
    public int getActionCount() { return actionCount; }
}

