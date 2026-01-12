package com.example.sarsa.strategy;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StrategyState {
    

    private HttpType httpType = HttpType.NONE;
    private Endpoint endpoint = Endpoint.ITEMS;
    private Field field = Field.ALL;
    private Strategy strategy = Strategy.VALID;
    private Intensity intensity = Intensity.MILD;

    public void reset() {
        this.httpType = HttpType.NONE;
        this.endpoint = Endpoint.ITEMS;
        this.field = Field.ALL;
        this.strategy = Strategy.VALID;
        this.intensity = Intensity.MILD;
    }


    public boolean isReadyToExecute() {
        return httpType != HttpType.NONE;
    }

    public Field getEffectiveField() {
        return field == Field.NONE ? Field.ALL : field;
    }

    public Strategy getEffectiveStrategy() {
        return strategy == Strategy.NONE ? Strategy.VALID : strategy;
    }

    @Override
    public String toString() {
        return String.format("StrategyState{endpoint=%s, field=%s, strategy=%s, intensity=%s}",
                httpType, field, strategy, intensity);
    }
}
