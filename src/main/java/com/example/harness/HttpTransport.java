package com.example.harness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The single HTTP path for every tester in this project.
 *
 * Shared on purpose: the RL arm and the RESTler arm must differ in how they *choose*
 * requests and in nothing else. Same client, same pool, same timeouts, same request
 * counter, same budget rule - so a "requests used" number means exactly one thing
 * whichever arm produced it.
 *
 * Two entry points, and the distinction is the budget:
 *   - {@link #fuzz}    a request the tester chose to send. Counted, and subject to
 *                      the budget.
 *   - {@link #harness} a request the measurement scaffolding sends (window reset,
 *                      chain-depth sampling). Not counted, not budgeted, identical
 *                      for every arm - 20-odd requests per trial against a budget of
 *                      250,000, and charging them would make the budget depend on
 *                      scaffolding rather than on the tester.
 */
public class HttpTransport {

    /** Thrown by {@link #fuzz} once the request budget is spent. Unwinds the arm's loop. */
    public static class BudgetExhausted extends RuntimeException {
        public BudgetExhausted(int budget) {
            super("request budget exhausted: " + budget);
        }
    }

    /** One pooled client for the whole JVM; per-request clients exhaust ephemeral ports. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    /** Hard cap on {@link #fuzz} calls that reach the socket; 0 means unlimited. */
    private final int budget;

    /**
     * Every request this JVM has sent, across all transports.
     *
     * Exists so a process that is not a measured run - the invariant suite, a smoke
     * test - can assert how much traffic it emitted, and at which service. Sending
     * test traffic into a live run silently corrupts that run's trial.
     */
    private static int jvmRequests = 0;

    private int fuzzRequests = 0;
    private int harnessRequests = 0;
    private int transportFailures = 0;

    public HttpTransport(String baseUrl, int budget) {
        this.baseUrl = baseUrl;
        this.budget = budget;
    }

    /**
     * A request the tester chose to send. Counts against the budget.
     *
     * The count is incremented before the send, so a request that dies in transport
     * still costs budget - it occupied the socket and the server may well have seen it.
     */
    public HttpResult fuzz(String method, String path, String json) {
        if (exhausted()) throw new BudgetExhausted(budget);
        fuzzRequests++;
        return send(method, path, json);
    }

    /** A measurement-scaffolding request. Never counted, never budgeted. */
    public HttpResult harness(String method, String path, String json) {
        harnessRequests++;
        return send(method, path, json);
    }

    public boolean exhausted() {
        return budget > 0 && fuzzRequests >= budget;
    }

    /** Requests remaining in the budget, or {@link Integer#MAX_VALUE} if unlimited. */
    public int remaining() {
        return budget > 0 ? Math.max(0, budget - fuzzRequests) : Integer.MAX_VALUE;
    }

    public int fuzzRequests() {
        return fuzzRequests;
    }

    public int harnessRequests() {
        return harnessRequests;
    }

    public int transportFailures() {
        return transportFailures;
    }

    public int budget() {
        return budget;
    }

    /** Requests sent by every transport in this JVM. See {@link #jvmRequests}. */
    public static int jvmRequests() {
        return jvmRequests;
    }

    private HttpResult send(String method, String path, String json) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            HttpRequest.BodyPublisher body = json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json);
            HttpRequest req = switch (method) {
                case "POST" -> b.POST(body).build();
                case "PUT" -> b.PUT(body).build();
                case "PATCH" -> b.method("PATCH", body).build();
                case "DELETE" -> b.DELETE().build();
                default -> b.GET().build();
            };
            jvmRequests++;
            HttpResponse<String> rp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(rp.statusCode(), rp.body());
        } catch (Exception e) {
            transportFailures++;
            return null;
        }
    }
}
