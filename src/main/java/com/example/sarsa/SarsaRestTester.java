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

    private int EPISODES = 200_000;
    private int LOG_EVERY = 10_000;
    private int SEED = 1234;
    private int STEP_LIMIT = 35;

    //HYPER PARAMS
    private double EPSILON = 0.01;
    static final double GAMMA = 1.0;
    static final double ALPHA = 0.01;

    //ANN
    private int ANN_INPUTS = State.FEATURE_COUNT;
    private int ANN_ACTIONS = StrategyBuilder.getActionCount();  // 22 actions
    private int ANN_NEURONS = 8;


    //URL
    private static final String BASE_URL = "http://localhost:8080/api/";
    private static final String ITEMS = "items";

    //HELPERS
    PayloadGenerator pbt;
    private String lastId;
    
    // Tracking - raw actions
    private static LinkedHashMap<Integer, Integer> allActionCounts = new LinkedHashMap<>();
    
    // Tracking - strategy-level (what actually got executed)
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
                
                // Endpoint distribution
                System.out.println("\n--- Endpoint Distribution ---");
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
                endpointCounts.clear();
                strategyCounts.clear();
                fieldCounts.clear();
                intensityCounts.clear();
                bugsByCombo.clear();
                executeCount = 0;
                dialTurnerCount = 0;
            }
        }
    }

    private double[] executeEpisode(Random rng) {
        StrategyBuilder strategy = new StrategyBuilder();
        lastId = null;  // Reset ID at episode start
        State currentState = initState();
        boolean[] mask = getValidMask(currentState, strategy);
        int currentAction = ann.epsilonGreedyMasked(currentState.scale(), EPSILON, mask, rng);
        
        double episodeReward = 0;
        int bugsFound = 0;

        Response response = null;
        strategy.reset();
        
        for (int step = 0; step < STEP_LIMIT; step++) {
            allActionCounts.merge(currentAction, 1, Integer::sum);

            String executedCombo = null;
            if(strategy.isExecute(currentAction)){
                // Track strategy combo before executing
                trackStrategyExecution(strategy);
                executedCombo = String.format("%s+%s+%s", 
                        strategy.getEndpoint(), strategy.getStrategy(), strategy.getField());
                response = executeWithStrategy(strategy);
                strategy.reset();
                currentState.resetAfterExecute();
                executeCount++;
            }else{
                currentState = strategy.applyAction(currentAction, currentState);
                dialTurnerCount++;
            }

            //NEXT
            State nextState = updateStateFromResponse(currentState, strategy, response);
            boolean[] nextMask = getValidMask(nextState, strategy);
            int nextAction = ann.epsilonGreedyMasked(nextState.scale(), EPSILON, nextMask, rng);

            double reward = calculateReward(response, executedCombo);
            episodeReward += reward;
            if (reward > 0) bugsFound++;  // Only count novel bugs
            
            boolean terminal = (step == STEP_LIMIT - 1);
            ann.sarsaUpdate(currentState.scale(), currentAction, reward, nextState.scale(), nextAction, terminal, ALPHA, GAMMA);
//            ann.qLearningUpdate(currentState.scale(), currentAction, reward, nextState.scale(), terminal, ALPHA, GAMMA);

            currentState = nextState;
            currentAction = nextAction;
        }
        
        return new double[]{episodeReward, bugsFound};
    }

    private double calculateReward(Response response, String executedCombo){
        if (response == null) {
            return -0.15;
        }
        if(response.getStatusCode() != 500){
            return 0;
        }
        // Track bug by strategy combo (captured before reset)
        if (executedCombo != null) {
            bugsByCombo.merge(executedCombo, 1, Integer::sum);
            uniqueBugCombos.add(executedCombo);
        }
        return 10;
    }
    
    private void trackStrategyExecution(StrategyBuilder strategy) {
        endpointCounts.merge(strategy.getEndpoint(), 1, Integer::sum);
        strategyCounts.merge(strategy.getStrategy(), 1, Integer::sum);
        fieldCounts.merge(strategy.getField(), 1, Integer::sum);
        intensityCounts.merge(strategy.getIntensity(), 1, Integer::sum);
    }


    private Response executeWithStrategy(StrategyBuilder s) {
        Endpoint endpoint = s.getEndpoint();
        String payload = pbt.generate(s.getField(), s.getStrategy(), s.getIntensity());

        return switch (endpoint) {
            case POST -> postItem(payload);
            case PUT -> putItem(payload);
            case PATCH -> patchItem(payload);
            case DELETE -> RestAssured.delete(BASE_URL + ITEMS + "/" + lastId);
            case GET -> RestAssured.get(BASE_URL + ITEMS + "/" + lastId);
            case GET_ALL -> RestAssured.get(BASE_URL + ITEMS);
            default -> null;
        };
    }

    private State updateStateFromResponse(State state, StrategyBuilder strategy, Response response) {
        if (response == null) return state;  // Dial-turner, no response yet

        state.setLastStatusCall(response.statusCode());
        Endpoint endpoint = strategy.getEndpoint();
        state.setLastMethod(getMethodForEndpoint(endpoint));

        // POST success
        if (endpoint == Endpoint.POST && response.statusCode() == 201) {
            state.setHasValidId(1);
            state.setHasAnyItems(1);
        }

        // DELETE success
        if (endpoint == Endpoint.DELETE && (response.statusCode() == 200 || response.statusCode() == 204)) {
            state.setHasValidId(0);
            lastId = null;
        }

        // GET_ALL - check if items exist
        if (endpoint == Endpoint.GET_ALL && response.statusCode() == 200) {
            extractIdFromGetAll(response);
            if (lastId != null) {
                state.setHasValidId(1);
                state.setHasAnyItems(1);
            } else {
                state.setHasAnyItems(0);
            }
        }

        return state;
    }

    private int getMethodForEndpoint(Endpoint endpoint) {
        return switch (endpoint) {
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
        boolean hasId = state.getHasValidId() == 1;

        for (int i = 0; i < ANN_ACTIONS; i++) {
            // ID-dependent endpoint selections need ID
            if (StrategyBuilder.actionRequiresId(i)) {
                mask[i] = hasId;
            }
            // EXECUTE only valid when endpoint is set
            else if (i == StrategyBuilder.getExecuteIndex()) {
                mask[i] = strategy.isReady();
            }
            // All other dial-turners always valid
            else {
                mask[i] = true;
            }
        }
        return mask;
    }

    // Helper methods
    private Response postItem(String payload) {
        Response response = RestAssured.given()
                .contentType("application/json")
                .body(payload)
                .post(BASE_URL + ITEMS);

        if (response.statusCode() == 201) {
            lastId = response.jsonPath().getString("id");
        }
        return response;
    }

    private Response putItem(String payload) {
        return RestAssured.given()
                .contentType("application/json")
                .body(payload)
                .put(BASE_URL + ITEMS + "/" + lastId);
    }

    private Response patchItem(String payload) {
        return RestAssured.given()
                .contentType("application/json")
                .body(payload)
                .patch(BASE_URL + ITEMS + "/" + lastId);
    }

    private void extractIdFromGetAll(Response response) {
        if (response.statusCode() != 200 || lastId != null) return;
        
        try {
            String firstId = response.jsonPath().getString("[0].id");
            if (firstId != null) lastId = firstId;
        } catch (Exception ignored) {
            // Empty list or invalid JSON - no ID to extract
        }
    }

    private State initState(){
        return new State(0, 0, 0,0, 0, 0, 0, 0, 0, 0);
    }



}
