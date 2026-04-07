package com.example.sarsa;

import com.example.ann.TinyQNetwork;
import com.example.sarsa.generator.PayloadGenerator;
import com.example.sarsa.model.State;
import com.example.sarsa.strategy.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class SarsaRestTester {

    private int EPISODES = 70_000;
    private int LOG_EVERY = 10_000;
    private int SEED = 1234;
    private int STEP_LIMIT = 35;

    //HYPER PARAMS
    private double EPSILON = 0.01;
    static final double GAMMA = 1.0;
    static final double ALPHA = 0.01;

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
    
    // Tracking - for chart/demo
    private static List<int[]> chartData = new ArrayList<>();  // [episode, hiddenBugHitsThisWindow]
    private static boolean hiddenBugDiscovered = false;
    private static int hiddenBugFirstEpisode = -1;
    private static int hiddenBugCount = 0;
    private static int hiddenBugHitsThisWindow = 0;  // Resets each LOG_EVERY


    TinyQNetwork ann;

    @Test
    public void executeSarsaTester(){
        pbt = new PayloadGenerator(SEED);
        ann = new TinyQNetwork(ANN_INPUTS, ANN_NEURONS, ANN_ACTIONS, SEED);
        executeSarsa(EPISODES);
        exportChartData();
    }

    private void executeSarsa(int episodes) {
        Random rng = new Random(SEED);
        // Tracking stats
        double totalReward = 0;
        double totalBugs = 0;
        long startTime = System.currentTimeMillis();
        
        for (int i = 1; i <= episodes; i++) {
            double[] result = executeEpisode(rng, i);
            totalReward += result[0];
            totalBugs += result[1];
            
            // Record data point every 1000 episodes for chart
            if (i % 1000 == 0) {
                chartData.add(new int[]{i, hiddenBugHitsThisWindow});
            }
            
            if (i % LOG_EVERY == 0) {
                double avgReward = totalReward / LOG_EVERY;
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                double executeRatio = executeCount > 0 ? (double) executeCount / (executeCount + dialTurnerCount) * 100 : 0;
                
                System.out.println("\n" + "=".repeat(60));
                System.out.printf("Episode %,d | Avg Reward: %.3f | Unique Bug Combos: %d | Time: %.1fs%n",
                        i, avgReward, uniqueBugCombos.size(), elapsed);
                System.out.printf("Execute ratio: %.1f%% (%d executes, %d dial-turners)%n", 
                        executeRatio, executeCount, dialTurnerCount);
                
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
                hiddenBugHitsThisWindow = 0;
            }
        }
    }

    private double[] executeEpisode(Random rng, int episodeNum) {
        StrategyBuilder strategy = new StrategyBuilder();
        lastItemId = null;
        lastPriceId = null;
        lastDiscountId = null;
        lastPointsId = null;
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
                executedCombo = String.format("%s+%s+%s+%s", 
                        strategy.getHttpType(), strategy.getEndpoint(), strategy.getStrategy(), strategy.getField());
                // Pass IDs to PayloadGenerator for PRICES/DISCOUNTS/POINTS endpoints
                pbt.setLastItemId(lastItemId != null ? Long.parseLong(lastItemId) : null);
                pbt.setLastPriceId(lastPriceId != null ? Long.parseLong(lastPriceId) : null);
                pbt.setLastDiscountId(lastDiscountId != null ? Long.parseLong(lastDiscountId) : null);
                response = executeWithStrategy(strategy);
                executeCount++;
            }else{
                currentState = strategy.applyAction(currentAction, currentState);
                currentState.setStepsSinceExecute(Math.min(currentState.getStepsSinceExecute() + 1, 10));
                dialTurnerCount++;
            }

            //NEXT
            State nextState = updateStateFromResponse(currentState, strategy, response);
            boolean[] nextMask = getValidMask(nextState, strategy);
            int nextAction = ann.epsilonGreedyMasked(nextState.scale(), EPSILON, nextMask, rng);

            if(response != null) {
                strategy.reset();
                nextState.resetAfterExecute();
            }

            double reward = calculateReward(response, executedCombo, episodeNum);
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

    private double calculateReward(Response response, String executedCombo, int episodeNum){
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
            
            // Check for HIDDEN BUG: DELETE+POINTS with any strategy
            if (executedCombo.startsWith("DELETE+POINTS+")) {
                hiddenBugCount++;
                hiddenBugHitsThisWindow++;
                logHiddenBugDiscovery(executedCombo, episodeNum);
            }
        }
        return 10;
    }
    
    private void logHiddenBugDiscovery(String combo, int episodeNum) {
        if (!hiddenBugDiscovered) {
            hiddenBugDiscovered = true;
            hiddenBugFirstEpisode = episodeNum;
            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════════════════╗");
            System.out.println("║              HIDDEN BUG DISCOVERED!                          ║");
            System.out.println("╠════════════════════════════════════════════════════════════════╣");
            System.out.printf("║  Episode:    %,d%n", episodeNum);
            System.out.printf("║  Combo:      %s%n", combo);
            System.out.println("║  Condition:  DELETE POINTS → 500 (ancestor price < 0)          ║");
            System.out.println("║  Chain:      ITEM → PRICE(neg) → DISCOUNT → POINTS → DELETE   ║");
            System.out.println("╚════════════════════════════════════════════════════════════════╝");
            System.out.println();
        } else {
            // Subsequent discoveries - shorter log
            System.out.printf(" HIDDEN BUG HIT #%d @ Episode %,d | %s | HTTP 500%n",
                    hiddenBugCount, episodeNum, combo);
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

        return switch (httpType) {
            case POST -> postItem(payload, endpoint);
            case PUT -> putItem(payload, endpoint);
            case PATCH -> patchItem(payload, endpoint);
            case DELETE -> RestAssured.delete(BASE_URL + endpointPath + "/" + lastId);
            case GET -> RestAssured.get(BASE_URL + endpointPath + "/" + lastId);
            case GET_ALL -> RestAssured.get(BASE_URL + endpointPath);
            default -> null;
        };
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

        for (int i = 0; i < ANN_ACTIONS; i++) {
            if (StrategyBuilder.actionRequiresId(i)) {
                // ID-dependent actions need the correct ID for the current endpoint
                if (currentEndpoint == Endpoint.ITEMS) {
                    mask[i] = hasItemId;
                } else if (currentEndpoint == Endpoint.PRICES) {
                    mask[i] = hasPriceId;
                } else if (currentEndpoint == Endpoint.DISCOUNTS) {
                    mask[i] = hasDiscountId;
                } else {
                    mask[i] = hasPointsId;
                }
            }
            else if (i == StrategyBuilder.getExecuteIndex()) {
                mask[i] = strategy.isReady();
            }
            else {
                mask[i] = true;
            }
        }
        return mask;
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
    
    private void exportChartData() {
        // Export CSV for external charting
        try (PrintWriter csv = new PrintWriter(new FileWriter("bug_discovery.csv"))) {
            csv.println("episode,hidden_bug_hits");
            for (int[] data : chartData) {
                csv.printf("%d,%d%n", data[0], data[1]);
            }
            System.out.println("\nChart data exported to: bug_discovery.csv");
        } catch (IOException e) {
            System.err.println("Failed to export CSV: " + e.getMessage());
        }
        
        // Export HTML chart with Chart.js
        try (PrintWriter html = new PrintWriter(new FileWriter("bug_discovery_chart.html"))) {
            html.println(generateChartHtml());
            System.out.println("Interactive chart exported to: bug_discovery_chart.html");
        } catch (IOException e) {
            System.err.println("Failed to export HTML: " + e.getMessage());
        }
        
        // Summary
        System.out.println("\n" + "=".repeat(60));
        System.out.println("FINAL SUMMARY");
        System.out.println("=".repeat(60));
        System.out.printf("Total unique bugs discovered: %d%n", uniqueBugCombos.size());
        System.out.printf("Hidden bug (DELETE+POINTS) hits: %d%n", hiddenBugCount);
        if (hiddenBugFirstEpisode > 0) {
            System.out.printf("Hidden bug first discovered at episode: %,d%n", hiddenBugFirstEpisode);
        } else {
            System.out.println("Hidden bug was NOT discovered in this run.");
        }
    }
    
    private String generateChartHtml() {
        StringBuilder episodes = new StringBuilder("[");
        StringBuilder hiddenHits = new StringBuilder("[");
        
        for (int i = 0; i < chartData.size(); i++) {
            int[] data = chartData.get(i);
            if (i > 0) {
                episodes.append(",");
                hiddenHits.append(",");
            }
            episodes.append(data[0]);
            hiddenHits.append(data[1]);
        }
        episodes.append("]");
        hiddenHits.append("]");
        
        String markerAnnotation = "";
        if (hiddenBugFirstEpisode > 0) {
            markerAnnotation = String.format("""
                annotation: {
                    annotations: {
                        hiddenBug: {
                            type: 'line',
                            xMin: %d,
                            xMax: %d,
                            borderColor: 'rgb(255, 99, 132)',
                            borderWidth: 3,
                            borderDash: [6, 6],
                            label: {
                                display: true,
                                content: '🎯 Hidden Bug Discovered',
                                position: 'start',
                                backgroundColor: 'rgba(255, 99, 132, 0.8)',
                                color: 'white',
                                font: { size: 14, weight: 'bold' }
                            }
                        }
                    }
                }
            """, hiddenBugFirstEpisode, hiddenBugFirstEpisode);
        }
        
        return String.format("""
<!DOCTYPE html>
<html>
<head>
    <title>Hidden Bug Discovery - SARSA Agent</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/chartjs-plugin-annotation"></script>
    <style>
        body { 
            font-family: 'Segoe UI', sans-serif; 
            background: #0d1117; 
            color: #e6edf3; 
            padding: 40px;
            margin: 0;
        }
        .container { max-width: 1000px; margin: 0 auto; }
        h1 { text-align: center; color: #58a6ff; margin-bottom: 8px; font-size: 1.8em; }
        .subtitle { text-align: center; color: #8b949e; margin-bottom: 30px; font-size: 0.95em; }
        .chart-container { 
            background: #161b22; 
            border: 1px solid #30363d;
            border-radius: 12px; 
            padding: 25px;
        }
        .stats {
            display: flex;
            justify-content: center;
            gap: 30px;
            margin-top: 25px;
        }
        .stat-box {
            background: #161b22;
            border: 1px solid #30363d;
            padding: 15px 30px;
            border-radius: 8px;
            text-align: center;
        }
        .stat-value { font-size: 2em; color: #58a6ff; font-weight: 600; }
        .stat-label { color: #8b949e; margin-top: 4px; font-size: 0.85em; }
        .highlight { border-color: #f85149; }
        .highlight .stat-value { color: #f85149; }
    </style>
</head>
<body>
    <div class="container">
        <h1>Hidden Bug Discovery</h1>
        <p class="subtitle">SARSA agent discovers conditional 5-step bug chain</p>
        
        <div class="chart-container">
            <canvas id="bugChart"></canvas>
        </div>
        
        <div class="stats">
            <div class="stat-box">
                <div class="stat-value">%d</div>
                <div class="stat-label">Total Unique Bugs</div>
            </div>
            <div class="stat-box highlight">
                <div class="stat-value">%s</div>
                <div class="stat-label">Discovery Episode</div>
            </div>
            <div class="stat-box">
                <div class="stat-value">%d</div>
                <div class="stat-label">Hidden Bug Hits</div>
            </div>
        </div>
    </div>

    <script>
        const ctx = document.getElementById('bugChart').getContext('2d');
        new Chart(ctx, {
            type: 'line',
            data: {
                labels: %s,
                datasets: [{
                    label: 'Hidden Bug Hits (per 1k episodes)',
                    data: %s,
                    borderColor: '#f85149',
                    backgroundColor: 'rgba(248, 81, 73, 0.15)',
                    fill: true,
                    tension: 0,
                    pointRadius: 2,
                    pointBackgroundColor: '#f85149',
                    borderWidth: 2
                }]
            },
            options: {
                responsive: true,
                interaction: { intersect: false, mode: 'index' },
                plugins: {
                    legend: { display: false },
                    title: {
                        display: true,
                        text: 'DELETE POINTS → 500 (when ancestor price < 0)',
                        color: '#8b949e',
                        font: { size: 13, weight: 'normal' }
                    },
                    %s
                },
                scales: {
                    x: { 
                        title: { display: true, text: 'Episode', color: '#8b949e' },
                        grid: { color: 'rgba(48, 54, 61, 0.6)' },
                        ticks: { color: '#8b949e' }
                    },
                    y: { 
                        title: { display: true, text: 'Bug Hits', color: '#8b949e' },
                        grid: { color: 'rgba(48, 54, 61, 0.6)' },
                        ticks: { color: '#8b949e' },
                        min: 0
                    }
                }
            }
        });
    </script>
</body>
</html>
        """, 
        uniqueBugCombos.size(),
        hiddenBugFirstEpisode > 0 ? String.format("%,d", hiddenBugFirstEpisode) : "—",
        hiddenBugCount,
        episodes.toString(),
        hiddenHits.toString(),
        markerAnnotation
        );
    }


}
