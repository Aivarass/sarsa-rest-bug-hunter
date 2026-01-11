package com.example.sarsa.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class State {

    // response related
    private int hasValidItemId;
    private int hasValidPriceId;
    private int hasAnyItems;    // 0 or 1
    private int lastStatusCall; // e.g., 200, 201, 400, 404, 500
    private int lastMethod;     // 0=GET, 1=POST, 2=PUT, 3=DELETE, 4=PATCH

    //strategy related
    private int httpType;   // 0=NONE, 1=GET, 2=POST, etc.
    private int endpoint;   // 0=ITEMS, 1=PRICES
    private int currentField;      // 0=NONE, 1=NAME, 2=QTY, etc.
    private int currentStrategy;   // 0=NONE, 1=VALID, 2=NULL_INJECT, etc.
    private int currentIntensity;  // 0=MILD, 1=MOD, 2=AGGRESSIVE
    private int stepsSinceExecute; // 0-10 (capped)

    private int isReadyToExecute;

    public static final int FEATURE_COUNT = 12;

    /**
     * Scales and normalizes state into double[] for neural network input.
     * All outputs are in range [0, 1] for stable training.
     */
    public double[] scale() {
        double[] features = new double[FEATURE_COUNT];

        // Binary features - already 0 or 1
        features[0] = hasValidItemId;
        features[1] = hasValidPriceId;
        features[2] = hasAnyItems;

        // Status code: group by category (2xx, 4xx, 5xx matter most)
        features[3] = normalizeStatusCode(lastStatusCall);

        // Method: 5 buckets (0-4) → normalized to [0, 1]
        features[4] = lastMethod / 4.0;

        // Strategy
        features[5] = httpType / 6.0;           // 7 HttpTypes (0-6 including NONE)
        features[6] = endpoint / 1.0;           // 2 endpoints: ITEMS=0, PRICES=1
        features[7] = currentField / 7.0;       // 8 fields (0-7: NONE, NAME, QUANTITY, DESCRIPTION, PRICE, ITEM_ID, ALL, UNKNOWN)
        features[8] = currentStrategy / 8.0;    // 9 strategies (0-8 including NONE)
        features[9] = currentIntensity / 2.0;   // 3 intensities (0-2)
        features[10] = Math.min(stepsSinceExecute, 10) / 10.0;  // Capped at 10
        features[11] = isReadyToExecute;        // Binary 0 or 1


        return features;
    }

    /**
     * Normalizes HTTP status codes to [0, 1] range.
     * Groups by response category for meaningful signal.
     */
    private double normalizeStatusCode(int code) {
        if (code == 0) return 0.0;  // no call yet

        return switch (code / 100) {
            case 2 -> 0.25;  // 2xx success
            case 3 -> 0.50;  // 3xx redirect
            case 4 -> 0.75;  // 4xx client error
            case 5 -> 1.00;  // 5xx server error (bugs!)
            default -> 0.0;
        };
    }

    public void resetAfterExecute() {
        this.httpType = 0;    // NONE
        this.endpoint = 0;
        this.currentField = 0;       // NONE
        this.currentStrategy = 0;    // NONE
        this.currentIntensity = 0;   // MILD
        this.stepsSinceExecute = 0;
        this.isReadyToExecute = 0;
    }
}