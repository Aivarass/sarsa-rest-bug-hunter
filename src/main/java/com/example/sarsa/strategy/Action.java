package com.example.sarsa.strategy;

import com.example.sarsa.model.State;

/**
 * SARSA action space for strategy-aware testing.
 * Actions modify StrategyState "dials", EXECUTE fires the API call.
 */
public enum Action {
    
    // ========================== Endpoint Selection (5) ==========================
    EXPLORE_GET,
    EXPLORE_GET_ALL,
    EXPLORE_POST,
    EXPLORE_PUT,
    EXPLORE_PATCH,
    EXPLORE_DELETE,

    // ========================== Field Targeting (5) ==========================
    FOCUS_NAME,
    FOCUS_QUANTITY,
    FOCUS_DESCRIPTION,
    FOCUS_ALL,
    FOCUS_UNKNOWN,

    // ========================== Mutation Strategy (8) ==========================
    STRATEGY_VALID,
    STRATEGY_NULL_INJECT,
    STRATEGY_NEGATIVE,
    STRATEGY_BOUNDARY,
    STRATEGY_STRUCTURE,
    STRATEGY_INJECTION,
    STRATEGY_TYPE_CONFUSE,
    STRATEGY_ENCODING,

    // ========================== Intensity (2) ==========================
    INTENSIFY,
    CONSERVE,

    // ========================== Execute (1) ==========================
    EXECUTE;

    /**
     * Total number of actions.
     */
    public static final int COUNT = values().length;

    /**
     * Apply this action to modify the strategy state.
     * Returns true if state was modified, false if this is EXECUTE.
     */
    public State applyTo(StrategyState strategy, State state) {
        switch (this) {
            // Endpoints
            case EXPLORE_GET -> {
                state.setIsReadyToExecute(1);
                state.setCurrentEndpoint(Endpoint.GET.ordinal());
                strategy.setEndpoint(Endpoint.GET);
            }
            case EXPLORE_GET_ALL -> {
                state.setIsReadyToExecute(1);
                state.setCurrentEndpoint(Endpoint.GET_ALL.ordinal());
                strategy.setEndpoint(Endpoint.GET_ALL);
            }
            case EXPLORE_POST -> {
                state.setIsReadyToExecute(1);
                state.setCurrentEndpoint(Endpoint.POST.ordinal());
                strategy.setEndpoint(Endpoint.POST);
            }
            case EXPLORE_PUT -> {
                state.setIsReadyToExecute(1);
                state.setCurrentEndpoint(Endpoint.PUT.ordinal());
                strategy.setEndpoint(Endpoint.PUT);
            }
            case EXPLORE_PATCH -> {
                state.setIsReadyToExecute(1);
                state.setCurrentEndpoint(Endpoint.PATCH.ordinal());
                strategy.setEndpoint(Endpoint.PATCH);
            }
            case EXPLORE_DELETE -> {
                state.setIsReadyToExecute(1);
                state.setCurrentEndpoint(Endpoint.DELETE.ordinal());
                strategy.setEndpoint(Endpoint.DELETE);
            }

            // Fields
            case FOCUS_NAME -> {
                state.setCurrentField(Field.NAME.ordinal());
                strategy.setField(Field.NAME);
            }
            case FOCUS_QUANTITY -> {
                state.setCurrentField(Field.QUANTITY.ordinal());
                strategy.setField(Field.QUANTITY);
            }
            case FOCUS_DESCRIPTION -> {
                state.setCurrentField(Field.DESCRIPTION.ordinal());
                strategy.setField(Field.DESCRIPTION);
            }
            case FOCUS_ALL -> {
                state.setCurrentField(Field.ALL.ordinal());
                strategy.setField(Field.ALL);
            }
            case FOCUS_UNKNOWN -> {
                state.setCurrentField(Field.UNKNOWN.ordinal());
                strategy.setField(Field.UNKNOWN);
            }

            // Strategies
            case STRATEGY_VALID -> {
                state.setCurrentStrategy(Strategy.VALID.ordinal());
                strategy.setStrategy(Strategy.VALID);
            }
            case STRATEGY_NULL_INJECT -> {
                state.setCurrentStrategy(Strategy.NULL_INJECT.ordinal());
                strategy.setStrategy(Strategy.NULL_INJECT);
            }
            case STRATEGY_NEGATIVE -> {
                state.setCurrentStrategy(Strategy.NEGATIVE.ordinal());
                strategy.setStrategy(Strategy.NEGATIVE);
            }
            case STRATEGY_BOUNDARY -> {
                state.setCurrentStrategy(Strategy.BOUNDARY.ordinal());
                strategy.setStrategy(Strategy.BOUNDARY);
            }
            case STRATEGY_STRUCTURE -> {
                state.setCurrentStrategy(Strategy.STRUCTURE.ordinal());
                strategy.setStrategy(Strategy.STRUCTURE);
            }
            case STRATEGY_INJECTION -> {
                state.setCurrentStrategy(Strategy.INJECTION.ordinal());
                strategy.setStrategy(Strategy.INJECTION);
            }
            case STRATEGY_TYPE_CONFUSE -> {
                state.setCurrentStrategy(Strategy.TYPE_CONFUSE.ordinal());
                strategy.setStrategy(Strategy.TYPE_CONFUSE);
            }
            case STRATEGY_ENCODING -> {
                state.setCurrentStrategy(Strategy.ENCODING.ordinal());
                strategy.setStrategy(Strategy.ENCODING);
            }

            // Intensity
            case INTENSIFY -> {
                state.setCurrentIntensity(Intensity.AGGRESSIVE.ordinal());
                strategy.setIntensity(Intensity.AGGRESSIVE);
            }
            case CONSERVE -> {
                state.setCurrentIntensity(Intensity.MILD.ordinal());
                strategy.setIntensity(Intensity.MILD);
            }

        }
        return state;
    }

    /**
     * Check if this action requires a valid ID to be available.
     */
    public boolean requiresId() {
        return this == EXPLORE_GET || this == EXPLORE_PUT || 
               this == EXPLORE_PATCH || this == EXPLORE_DELETE;
    }
}
