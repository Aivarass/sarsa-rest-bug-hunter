package com.example.sarsa.strategy;

/**
 * Holds current strategy configuration (the "dials").
 * SARSA actions modify these settings, EXECUTE uses them.
 */
public class StrategyState {
    
    private Endpoint endpoint = Endpoint.NONE;
    private Field field = Field.ALL;
    private Strategy strategy = Strategy.VALID;
    private Intensity intensity = Intensity.MILD;

    // ========================== Getters ==========================

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public Field getField() {
        return field;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public Intensity getIntensity() {
        return intensity;
    }

    // ========================== Setters ==========================

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public void setField(Field field) {
        this.field = field;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public void setIntensity(Intensity intensity) {
        this.intensity = intensity;
    }

    // ========================== Convenience ==========================

    /**
     * Reset to defaults (call at episode start).
     */
    public void reset() {
        this.endpoint = Endpoint.NONE;
        this.field = Field.ALL;
        this.strategy = Strategy.VALID;
        this.intensity = Intensity.MILD;
    }

    /**
     * Check if ready to execute (at minimum needs endpoint).
     */
    public boolean isReadyToExecute() {
        return endpoint != Endpoint.NONE;
    }

    /**
     * Get effective field (defaults to ALL if NONE).
     */
    public Field getEffectiveField() {
        return field == Field.NONE ? Field.ALL : field;
    }

    /**
     * Get effective strategy (defaults to VALID if NONE).
     */
    public Strategy getEffectiveStrategy() {
        return strategy == Strategy.NONE ? Strategy.VALID : strategy;
    }

    @Override
    public String toString() {
        return String.format("StrategyState{endpoint=%s, field=%s, strategy=%s, intensity=%s}",
                endpoint, field, strategy, intensity);
    }
}
