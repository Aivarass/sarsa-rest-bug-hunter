package com.example.sarsa.strategy;

import com.example.sarsa.model.State;

/**
 * Bridges SARSA action indices to StrategyState modifications.
 * Maintains state across steps until EXECUTE is called.
 */
public class StrategyBuilder {

    private final StrategyState strategy;
    private static final Action[] ACTIONS = Action.values();

    public StrategyBuilder() {
        this.strategy = new StrategyState();
    }

    /**
     * Apply action by index (from SARSA agent).
     * Returns true if this was a dial-turner, false if EXECUTE.
     */
    public State applyAction(int actionIndex, State state) {
        if (actionIndex < 0 || actionIndex >= ACTIONS.length) {
            throw new IllegalArgumentException("Invalid action index: " + actionIndex);
        }
        Action action = ACTIONS[actionIndex];
        return action.applyTo(strategy, state);
    }

    /**
     * Check if the selected action is EXECUTE.
     */
    public boolean isExecute(int actionIndex) {
        return actionIndex == Action.EXECUTE.ordinal();
    }

    /**
     * Get current strategy state (for payload generation).
     */
    public StrategyState getState() {
        return strategy;
    }

    /**
     * Get current endpoint.
     */
    public Endpoint getEndpoint() {
        return strategy.getEndpoint();
    }

    /**
     * Get effective field (with NONE → ALL fallback).
     */
    public Field getField() {
        return strategy.getEffectiveField();
    }

    /**
     * Get effective strategy (with NONE → VALID fallback).
     */
    public Strategy getStrategy() {
        return strategy.getEffectiveStrategy();
    }

    /**
     * Get current intensity.
     */
    public Intensity getIntensity() {
        return strategy.getIntensity();
    }

    /**
     * Check if ready to execute (has endpoint set).
     */
    public boolean isReady() {
        return strategy.isReadyToExecute();
    }

    /**
     * Reset for new episode.
     */
    public void reset() {
        strategy.reset();
    }

    /**
     * Get action count (for ANN_ACTIONS).
     */
    public static int getActionCount() {
        return Action.COUNT;
    }

    /**
     * Get EXECUTE action index.
     */
    public static int getExecuteIndex() {
        return Action.EXECUTE.ordinal();
    }

    /**
     * Check if action at index requires a valid ID.
     */
    public static boolean actionRequiresId(int actionIndex) {
        if (actionIndex < 0 || actionIndex >= ACTIONS.length) return false;
        return ACTIONS[actionIndex].requiresId();
    }

    @Override
    public String toString() {
        return strategy.toString();
    }
}

