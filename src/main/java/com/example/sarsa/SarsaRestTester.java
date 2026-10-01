package com.example.sarsa;

import com.example.ann.TinyQNetwork;
import com.example.sarsa.generator.PayloadGenerator;
import com.example.sarsa.model.State;
import com.example.sarsa.strategy.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.*;

public class SarsaRestTester {

    private int EPISODES = 50_000;
    private int LOG_EVERY = 10_000;
    private int SEED = 1234;
    private int STEP_LIMIT = 35;

    //HYPER PARAMS
    private double EPSILON = 0.01;
    // GAMMA must stay < 1: STEP_LIMIT is a truncation, not a true terminal state, so
    // the loop bootstraps through the cut. With GAMMA = 1.0 that continuing return is
    // unbounded and the value function is ill-defined. 0.99^35 ~= 0.70, so credit still
    // propagates comfortably across a 5-step dependency chain.
    static final double GAMMA = 0.99;
    static final double ALPHA = 0.01;

    //REWARDS
    // Scaled so the largest single-step reward is 1.0. The ANN clips |TD error| at 10
    // as a divergence safety net; at the old +10 bug reward that net truncated the
    // signal on the very transitions we care about. Ratios between the three are
    // unchanged from the original 10 / -0.15, so the induced ordering is the same.
    static final double NOVEL_BUG_REWARD = 1.0;
    static final double REPEAT_BUG_REWARD = 0.1;
    static final double DIAL_STEP_COST = -0.015;

    //ANN
    private int ANN_INPUTS = State.FEATURE_COUNT;
    private int ANN_ACTIONS = StrategyBuilder.getActionCount();  // 32 actions
    private int ANN_NEURONS = 16;


    //URL
    private static final String BASE_URL = "http://localhost:8080/api/";
    private static final String ITEMS = "items";

    //HELPERS
    PayloadGenerator pbt;
    private String lastItemId;
    private String lastPriceId;
    private String lastDiscountId;
    private String lastPointsId;
    
    // Tracking - raw actions
    private static LinkedHashMap<Integer, Integer> allActionCounts = new LinkedHashMap<>();
    
    // Tracking - strategy-level (what actually got executed)
    private static LinkedHashMap<HttpType, Integer> httpTypeCounts = new LinkedHashMap<>();
    private static LinkedHashMap<Endpoint, Integer> endpointCounts = new LinkedHashMap<>();
    private static LinkedHashMap<Strategy, Integer> strategyCounts = new LinkedHashMap<>();
    private static LinkedHashMap<Field, Integer> fieldCounts = new LinkedHashMap<>();
    private static LinkedHashMap<Intensity, Integer> intensityCounts = new LinkedHashMap<>();
    
    // Tracking - bugs by strategy combo
    private static LinkedHashMap<String, Integer> bugsByCombo = new LinkedHashMap<>();
    private static Set<String> uniqueBugCombos = new HashSet<>();
    
    // Tracking - execute vs dial-turner ratio
    private static int executeCount = 0;
    private static int dialTurnerCount = 0;
    private static int transportFailureCount = 0;


    TinyQNetwork ann;

    @Test
    public void executeSarsaTester(){
        pbt = new PayloadGenerator(SEED);
        ann = new TinyQNetwork(ANN_INPUTS, ANN_NEURONS, ANN_ACTIONS, SEED);
        executeSarsa(EPISODES);
    }

    private void executeSarsa(int episodes) {
        Random rng = new Random(SEED);
        // Tracking stats
        double totalReward = 0;
        double totalBugs = 0;
        long startTime = System.currentTimeMillis();
        
        for (int i = 1; i <= episodes; i++) {
            double[] result = executeEpisode(rng);
            totalReward += result[0];
            totalBugs += result[1];
            
            if (i % LOG_EVERY == 0) {
                double avgReward = totalReward / LOG_EVERY;
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                double executeRatio = executeCount > 0 ? (double) executeCount / (executeCount + dialTurnerCount) * 100 : 0;
                
                System.out.println("\n" + "=".repeat(60));
                System.out.printf("Episode %,d | Avg Reward: %.3f | Unique Bug Combos: %d | Time: %.1fs%n",
                        i, avgReward, uniqueBugCombos.size(), elapsed);
                System.out.printf("Execute ratio: %.1f%% (%d executes, %d dial-turners)%n",
                        executeRatio, executeCount, dialTurnerCount);
                System.out.printf("Novel bugs this window: %.0f | Transport failures: %d%n",
                        totalBugs, transportFailureCount);
                
                // HttpType distribution (GET, POST, PUT, etc.)
                System.out.println("\n--- HttpType Distribution ---");
                for (Map.Entry<HttpType, Integer> e : httpTypeCounts.entrySet()) {
                    System.out.printf("  %-10s: %d%n", e.getKey(), e.getValue());
                }
                
                // Resource/Endpoint distribution (ITEMS, PRICES)
                System.out.println("\n--- Resource Distribution ---");
                for (Map.Entry<Endpoint, Integer> e : endpointCounts.entrySet()) {
                    System.out.printf("  %-10s: %d%n", e.getKey(), e.getValue());
                }
                
                // Strategy distribution
                System.out.println("\n--- Strategy Distribution ---");
                for (Map.Entry<Strategy, Integer> e : strategyCounts.entrySet()) {
                    System.out.printf("  %-15s: %d%n", e.getKey(), e.getValue());
                }
                
                // Field distribution
                System.out.println("\n--- Field Distribution ---");
                for (Map.Entry<Field, Integer> e : fieldCounts.entrySet()) {
                    System.out.printf("  %-12s: %d%n", e.getKey(), e.getValue());
                }
                
                // Intensity distribution
                System.out.println("\n--- Intensity Distribution ---");
                for (Map.Entry<Intensity, Integer> e : intensityCounts.entrySet()) {
                    System.out.printf("  %-12s: %d%n", e.getKey(), e.getValue());
                }
                
                // Bugs by combo (top 5)
                if (!bugsByCombo.isEmpty()) {
                    System.out.println("\n--- Top Bug-Triggering Combos ---");
                    bugsByCombo.entrySet().stream()
                            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                            .limit(5)
                            .forEach(e -> System.out.printf("  %s: %d times%n", e.getKey(), e.getValue()));
                }
                
                // Raw action distribution (compact)
                System.out.println("\n--- Raw Action Distribution ---");
                System.out.print("  ");
                allActionCounts.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> System.out.printf("[%d]:%d ", e.getKey(), e.getValue()));
                System.out.println();
                
                // Reset for next window
                totalReward = 0;
                totalBugs = 0;
                startTime = System.currentTimeMillis();
                allActionCounts.clear();
                httpTypeCounts.clear();
                endpointCounts.clear();
                strategyCounts.clear();
                fieldCounts.clear();
                intensityCounts.clear();
                bugsByCombo.clear();
                executeCount = 0;
                dialTurnerCount = 0;
                transportFailureCount = 0;
            }
        }
    }

    private double[] executeEpisode(Random rng) {
        StrategyBuilder strategy = new StrategyBuilder();
        lastItemId = null;
        lastPriceId = null;
        lastDiscountId = null;
        lastPointsId = null;
        State currentState = initState();
        strategy.reset();
        boolean[] mask = getValidMask(currentState, strategy);
        int currentAction = ann.epsilonGreedyMasked(currentState.scale(), EPSILON, mask, rng);

        double episodeReward = 0;
        int bugsFound = 0;

        for (int step = 0; step < STEP_LIMIT; step++) {
            allActionCounts.merge(currentAction, 1, Integer::sum);

            // S_t: snapshot the features BEFORE anything mutates the State object.
            // State is mutated in place and every helper returns the same instance,
            // so without this snapshot s and s' would be the identical vector and the
            // TD target would carry no information about what the action did.
            double[] sFeat = currentState.scale();

            // Per-step, NOT per-episode: a stale response would otherwise keep paying
            // the bug reward and keep resetting the dials on every later step.
            Response response = null;
            String executedCombo = null;
            boolean wasExecute = strategy.isExecute(currentAction);

            if (wasExecute) {
                // Track strategy combo before executing
                trackStrategyExecution(strategy);
                executedCombo = String.format("%s+%s+%s+%s",
                        strategy.getHttpType(), strategy.getEndpoint(), strategy.getStrategy(), strategy.getField());
                // Pass IDs to PayloadGenerator for PRICES/DISCOUNTS/POINTS endpoints
                pbt.setLastItemId(parseId(lastItemId));
                pbt.setLastPriceId(parseId(lastPriceId));
                pbt.setLastDiscountId(parseId(lastDiscountId));
                response = executeWithStrategy(strategy);
                executeCount++;
            } else {
                currentState = strategy.applyAction(currentAction, currentState);
                currentState.setStepsSinceExecute(Math.min(currentState.getStepsSinceExecute() + 1, 10));
                dialTurnerCount++;
            }

            // Environment transition -> S_{t+1}
            State nextState = updateStateFromResponse(currentState, strategy, response);

            // R_{t+1} for this transition, scored while the dials are still intact.
            RewardOutcome outcome = calculateReward(response, executedCombo, wasExecute);
            episodeReward += outcome.reward;
            if (outcome.novelBug) bugsFound++;

            // Post-execute bookkeeping must happen BEFORE A_{t+1} is chosen, so that
            // a' is drawn from the same s' that gets fed to the SARSA update and so
            // that the action mask reflects the state the action will actually run in.
            if (wasExecute) {
                strategy.reset();
                nextState.resetAfterExecute();
            }

            boolean[] nextMask = getValidMask(nextState, strategy);
            double[] sNextFeat = nextState.scale();
            int nextAction = ann.epsilonGreedyMasked(sNextFeat, EPSILON, nextMask, rng);

            // STEP_LIMIT is a truncation, not a true terminal state: nothing about the
            // API makes step 34 an absorbing state, and the step index is not in the
            // features. Zeroing the bootstrap here would bias every value estimate, so
            // we bootstrap through the cut (partial-episode bootstrapping). This is only
            // sound because GAMMA < 1 bounds the continuing return.
            boolean terminal = false;
            ann.sarsaUpdate(sFeat, currentAction, outcome.reward, sNextFeat, nextAction, terminal, ALPHA, GAMMA);
//            ann.qLearningUpdate(sFeat, currentAction, outcome.reward, sNextFeat, terminal, ALPHA, GAMMA);

            currentState = nextState;
            currentAction = nextAction;
        }

        return new double[]{episodeReward, bugsFound};
    }

    /** Reward for one transition, plus whether it was a first-time bug discovery. */
    private record RewardOutcome(double reward, boolean novelBug) {}

    private RewardOutcome calculateReward(Response response, String executedCombo, boolean wasExecute) {
        if (!wasExecute) {
            // Cost of turning a dial instead of firing a request.
            return new RewardOutcome(DIAL_STEP_COST, false);
        }
        if (response == null) {
            // Execute attempted but the request could not be completed (transport
            // failure). Neutral: not a confirmed bug, but not a dial-turn either.
            return new RewardOutcome(0, false);
        }
        if (response.getStatusCode() != 500) {
            return new RewardOutcome(0, false);
        }

        // Track bug by strategy combo (captured before reset)
        boolean novel = false;
        if (executedCombo != null) {
            bugsByCombo.merge(executedCombo, 1, Integer::sum);
            novel = uniqueBugCombos.add(executedCombo);
        }
        // Re-triggering an already-known combo pays far less, so the optimal policy is
        // to broaden coverage rather than spam one known 500 for the rest of the run.
        // NOTE: this makes the reward history-dependent, i.e. non-Markovian w.r.t. the
        // feature vector, since the discovered-combo set is not part of the state. That
        // is the usual trade-off for novelty bonuses in fuzzing; tune the two constants
        // if you want a stricter MDP (set them equal) or stronger coverage pressure.
        return new RewardOutcome(novel ? NOVEL_BUG_REWARD : REPEAT_BUG_REWARD, novel);
    }

    private static Long parseId(String id) {
        if (id == null) return null;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    private void trackStrategyExecution(StrategyBuilder strategy) {
        httpTypeCounts.merge(strategy.getHttpType(), 1, Integer::sum);
        endpointCounts.merge(strategy.getEndpoint(), 1, Integer::sum);
        strategyCounts.merge(strategy.getStrategy(), 1, Integer::sum);
        fieldCounts.merge(strategy.getField(), 1, Integer::sum);
        intensityCounts.merge(strategy.getIntensity(), 1, Integer::sum);
    }


    private Response executeWithStrategy(StrategyBuilder s) {
        HttpType httpType = s.getHttpType();
        Endpoint endpoint = s.getEndpoint();
        // Generate endpoint-aware payload
        String payload = pbt.generate(endpoint, s.getField(), s.getStrategy(), s.getIntensity());

        String lastId;
        if(endpoint == Endpoint.ITEMS){
            lastId = lastItemId;
        } else if (endpoint == Endpoint.PRICES) {
            lastId = lastPriceId;
        }else if (endpoint == Endpoint.DISCOUNTS){
            lastId = lastDiscountId;
        }else{
            lastId = lastPointsId;
        }

        // Use lowercase endpoint name for URL (items, prices)
        String endpointPath = endpoint.name().toLowerCase();

        // The action mask should already prevent this, but a literal "/null" in the URL
        // would silently pollute the bug statistics, so refuse it rather than send it.
        if (httpTypeRequiresId(httpType) && lastId == null) {
            return null;
        }

        try {
            return switch (httpType) {
                case POST -> postItem(payload, endpoint);
                case PUT -> putItem(payload, endpoint);
                case PATCH -> patchItem(payload, endpoint);
                case DELETE -> RestAssured.delete(BASE_URL + endpointPath + "/" + lastId);
                case GET -> RestAssured.get(BASE_URL + endpointPath + "/" + lastId);
                case GET_ALL -> RestAssured.get(BASE_URL + endpointPath);
                default -> null;
            };
        } catch (Exception e) {
            // Transport-level failure (connection reset, timeout, server down). Scored
            // as neutral rather than as a 500: a genuine crash and a flaky socket are
            // indistinguishable here, and rewarding it would teach the agent to DoS
            // the target instead of finding logic bugs. Without this catch a single
            // dropped connection aborts the whole run.
            transportFailureCount++;
            return null;
        }
    }

    private State updateStateFromResponse(State state, StrategyBuilder strategy, Response response) {
        if (response == null) return state;  // Dial-turner, no response yet

        state.setLastStatusCall(response.statusCode());
        HttpType httpType = strategy.getHttpType();
        state.setLastMethod(getMethodForEndpoint(httpType));
        state.setEndpoint(strategy.getEndpoint().ordinal());

        // POST success
        if (httpType == HttpType.POST && response.statusCode() == 201 && strategy.getEndpoint() == Endpoint.ITEMS) {
            state.setHasValidItemId(1);
            //removing for now
//            state.setHasAnyItems(1);
        }

        if (httpType == HttpType.POST && response.statusCode() == 201 && strategy.getEndpoint() == Endpoint.PRICES) {
            state.setHasValidPriceId(1);
            // removing for now
//            state.setHasAnyItems(1);
        }

        if (httpType == HttpType.POST && response.statusCode() == 201 && strategy.getEndpoint() == Endpoint.DISCOUNTS) {
            state.setHasValidDiscountId(1);
            // removing for now
//            state.setHasAnyItems(1);
        }

        if (httpType == HttpType.POST && response.statusCode() == 201 && strategy.getEndpoint() == Endpoint.POINTS) {
            state.setHasValidPointsId(1);
        }


        // DELETE success
        if (httpType == HttpType.DELETE && (response.statusCode() == 200 || response.statusCode() == 204)  && strategy.getEndpoint() == Endpoint.ITEMS) {
            state.setHasValidItemId(0);
            lastItemId = null;
        }

        if (httpType == HttpType.DELETE && (response.statusCode() == 200 || response.statusCode() == 204)  && strategy.getEndpoint() == Endpoint.PRICES) {
            state.setHasValidPriceId(0);
            lastPriceId = null;
        }

        if (httpType == HttpType.DELETE && (response.statusCode() == 200 || response.statusCode() == 204)  && strategy.getEndpoint() == Endpoint.DISCOUNTS) {
            state.setHasValidDiscountId(0);
            lastDiscountId = null;
        }

        if (httpType == HttpType.DELETE && (response.statusCode() == 200 || response.statusCode() == 204)  && strategy.getEndpoint() == Endpoint.POINTS) {
            state.setHasValidPointsId(0);
            lastPointsId = null;
        }

        // GET_ALL - check if items exist
        if (httpType == HttpType.GET_ALL && response.statusCode() == 200 && strategy.getEndpoint() == Endpoint.ITEMS) {
            extractIdFromGetAll(response, Endpoint.ITEMS);
            if (lastItemId != null) {
                state.setHasValidItemId(1);
                state.setHasAnyItems(1);
            } else {
                state.setHasAnyItems(0);
            }
        }

        if (httpType == HttpType.GET_ALL && response.statusCode() == 200 && strategy.getEndpoint() == Endpoint.PRICES) {
            extractIdFromGetAll(response, Endpoint.PRICES);
            if (lastPriceId != null) {
                state.setHasValidPriceId(1);
//                state.setHasAnyItems(1);
//            } else {
//                state.setHasAnyItems(0);
            }
        }

        if (httpType == HttpType.GET_ALL && response.statusCode() == 200 && strategy.getEndpoint() == Endpoint.DISCOUNTS) {
            extractIdFromGetAll(response, Endpoint.DISCOUNTS);
            if (lastDiscountId != null) {
                state.setHasValidDiscountId(1);
            }
        }

        if (httpType == HttpType.GET_ALL && response.statusCode() == 200 && strategy.getEndpoint() == Endpoint.POINTS) {
            extractIdFromGetAll(response, Endpoint.POINTS);
            if (lastPointsId != null) {
                state.setHasValidPointsId(1);
            }
        }

        return state;
    }

    private int getMethodForEndpoint(HttpType httpType) {
        return switch (httpType) {
            case GET, GET_ALL -> 0;
            case POST -> 1;
            case PUT -> 2;
            case DELETE -> 3;
            case PATCH -> 4;
            case NONE -> 0;
        };
    }

    private boolean[] getValidMask(State state, StrategyBuilder strategy) {
        boolean[] mask = new boolean[ANN_ACTIONS];
        boolean hasItemId = state.getHasValidItemId() == 1;
        boolean hasPriceId = state.getHasValidPriceId() == 1;
        boolean hasDiscountId = state.getHasValidDiscountId() == 1;
        boolean hasPointsId = state.getHasValidPointsId() == 1;
        Endpoint currentEndpoint = strategy.getEndpoint();

        boolean hasIdForCurrentEndpoint;
        if (currentEndpoint == Endpoint.ITEMS) {
            hasIdForCurrentEndpoint = hasItemId;
        } else if (currentEndpoint == Endpoint.PRICES) {
            hasIdForCurrentEndpoint = hasPriceId;
        } else if (currentEndpoint == Endpoint.DISCOUNTS) {
            hasIdForCurrentEndpoint = hasDiscountId;
        } else {
            hasIdForCurrentEndpoint = hasPointsId;
        }

        for (int i = 0; i < ANN_ACTIONS; i++) {
            if (StrategyBuilder.actionRequiresId(i)) {
                // ID-dependent actions need the correct ID for the current endpoint
                mask[i] = hasIdForCurrentEndpoint;
            }
            else if (i == StrategyBuilder.getExecuteIndex()) {
                // Gating the EXPLORE_* dials alone is not enough: INSPECT_* is never
                // masked, so the agent can dial a method that needs an ID and then
                // switch to an endpoint that has none, producing DELETE /prices/null.
                // EXECUTE therefore has to re-check the pairing at fire time.
                mask[i] = strategy.isReady()
                        && (!httpTypeRequiresId(strategy.getHttpType()) || hasIdForCurrentEndpoint);
            }
            else {
                mask[i] = true;
            }
        }
        return mask;
    }

    /** HTTP methods that address a single resource and therefore need a known ID. */
    private static boolean httpTypeRequiresId(HttpType httpType) {
        return httpType == HttpType.GET
                || httpType == HttpType.PUT
                || httpType == HttpType.PATCH
                || httpType == HttpType.DELETE;
    }

    // Helper methods
    private Response postItem(String payload, Endpoint endpoint) {
        Response response = RestAssured.given()
                .contentType("application/json")
                .body(payload)
                .post(BASE_URL + endpoint.name().toLowerCase());

        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id");
            if(endpoint == Endpoint.ITEMS) {
                lastItemId = id;
            }else if (endpoint == Endpoint.PRICES){
                lastPriceId = id;
            }else if(endpoint == Endpoint.DISCOUNTS){
                lastDiscountId = id;
            }else{
                lastPointsId = id;
            }
        }
        return response;
    }

    public String getEndpointTarget(Endpoint endpoint){
        if(endpoint == Endpoint.ITEMS){
            return lastItemId;
        } else if (endpoint == Endpoint.PRICES) {
            return lastPriceId;
        }else if (endpoint == Endpoint.DISCOUNTS){
            return lastDiscountId;
        }else{
            return lastPointsId;
        }
    }

    private Response putItem(String payload, Endpoint endpoint) {
        String targetId = getEndpointTarget(endpoint);

        return RestAssured.given()
                .contentType("application/json")
                .body(payload)
                .put(BASE_URL + endpoint.name().toLowerCase() + "/" + targetId);
    }

    private Response patchItem(String payload, Endpoint endpoint) {
        String targetId = getEndpointTarget(endpoint);

        return RestAssured.given()
                .contentType("application/json")
                .body(payload)
                .patch(BASE_URL + endpoint.name().toLowerCase() + "/" + targetId);
    }

    private void extractIdFromGetAll(Response response, Endpoint endpoint) {
        if (response.statusCode() != 200) return;

        try {
            String firstId = response.jsonPath().getString("[0].id");
            if (firstId != null) {
                if (endpoint == Endpoint.ITEMS) {
                    lastItemId = firstId;
                } else if (endpoint == Endpoint.PRICES) {
                    lastPriceId = firstId;
                } else if (endpoint == Endpoint.DISCOUNTS) {
                    lastDiscountId = firstId;
                } else{
                    lastPointsId = firstId;
                }
            }
        } catch (Exception ignored) {
            // Empty list or invalid JSON
        }
    }

    private State initState(){
        return new State(0,0, 0,0, 0, 0,0, 0, 0, 0, 0, 0, 0, 0);
    }



}
