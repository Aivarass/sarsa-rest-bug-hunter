package com.example.sarsa.strategy;

import com.example.sarsa.model.State;

/**
 * SARSA action space for strategy-aware testing.
 * Actions modify StrategyState "dials", EXECUTE fires the API call.
 */
public enum Action {
    
    // ========================== HTTP Type (6) ==========================
    EXPLORE_GET,
    EXPLORE_GET_ALL,
    EXPLORE_POST,
    EXPLORE_PUT,
    EXPLORE_PATCH,
    EXPLORE_DELETE,

    // ========================== Endpoint (4) ==========================
    INSPECT_ITEMS,
    INSPECT_PRICES,
    INSPECT_DISCOUNTS,
    INSPECT_POINTS,

    // ========================== Field Targeting (11) ==========================
    FOCUS_NAME,         // ITEMS: name field
    FOCUS_QUANTITY,     // ITEMS: quantity field
    FOCUS_DESCRIPTION,  // ITEMS: description field
    FOCUS_PRICE,        // PRICES: price field
    FOCUS_ITEM_ID,      // PRICES: itemId field (needs valid item!)
    FOCUS_DISCOUNT_ID,
    FOCUS_DISCOUNT,
    FOCUS_POINTS_ID,
    FOCUS_POINTS,
    FOCUS_ALL,          // Apply to all fields
    FOCUS_UNKNOWN,      // Add unknown/extra fields

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
            // http type
            case EXPLORE_GET -> {
                state.setIsReadyToExecute(1);
                state.setHttpType(HttpType.GET.ordinal());
                strategy.setHttpType(HttpType.GET);
            }
            case EXPLORE_GET_ALL -> {
                state.setIsReadyToExecute(1);
                state.setHttpType(HttpType.GET_ALL.ordinal());
                strategy.setHttpType(HttpType.GET_ALL);
            }
            case EXPLORE_POST -> {
                state.setIsReadyToExecute(1);
                state.setHttpType(HttpType.POST.ordinal());
                strategy.setHttpType(HttpType.POST);
            }
            case EXPLORE_PUT -> {
                state.setIsReadyToExecute(1);
                state.setHttpType(HttpType.PUT.ordinal());
                strategy.setHttpType(HttpType.PUT);
            }
            case EXPLORE_PATCH -> {
                state.setIsReadyToExecute(1);
                state.setHttpType(HttpType.PATCH.ordinal());
                strategy.setHttpType(HttpType.PATCH);
            }
            case EXPLORE_DELETE -> {
                state.setIsReadyToExecute(1);
                state.setHttpType(HttpType.DELETE.ordinal());
                strategy.setHttpType(HttpType.DELETE);
            }

            // Endpoints
            case INSPECT_ITEMS -> {
                state.setEndpoint(Endpoint.ITEMS.ordinal());
                strategy.setEndpoint(Endpoint.ITEMS);
            }
            case INSPECT_PRICES -> {
                state.setEndpoint(Endpoint.PRICES.ordinal());
                strategy.setEndpoint(Endpoint.PRICES);
            }
            case INSPECT_DISCOUNTS -> {
                state.setEndpoint(Endpoint.DISCOUNTS.ordinal());
                strategy.setEndpoint(Endpoint.DISCOUNTS);
            }
            case INSPECT_POINTS -> {
                state.setEndpoint(Endpoint.POINTS.ordinal());
                strategy.setEndpoint(Endpoint.POINTS);
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
            case FOCUS_PRICE -> {
                state.setCurrentField(Field.PRICE.ordinal());
                strategy.setField(Field.PRICE);
            }
            case FOCUS_ITEM_ID -> {
                state.setCurrentField(Field.ITEM_ID.ordinal());
                strategy.setField(Field.ITEM_ID);
            }
            case FOCUS_DISCOUNT_ID -> {
                state.setCurrentField(Field.DISCOUNT_ID.ordinal());
                strategy.setField(Field.DISCOUNT_ID);
            }
            case FOCUS_DISCOUNT -> {
                state.setCurrentField(Field.DISCOUNT.ordinal());
                strategy.setField(Field.DISCOUNT);
            }
            case FOCUS_POINTS_ID -> {
                state.setCurrentField(Field.POINTS_ID.ordinal());
                strategy.setField(Field.POINTS_ID);
            }
            case FOCUS_POINTS -> {
                state.setCurrentField(Field.POINTS.ordinal());
                strategy.setField(Field.POINTS);
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

            // Execute - doesn't modify state
            case EXECUTE -> { /* handled in SarsaRestTester */ }
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
