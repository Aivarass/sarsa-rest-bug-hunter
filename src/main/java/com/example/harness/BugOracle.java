package com.example.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one definition of "what the server did" and "which finding is this", shared by
 * every arm.
 *
 * This has to be shared rather than reimplemented per arm, or "found Defect 1" would
 * mean something slightly different in each column of the results table and the
 * comparison would be meaningless.
 *
 * Three keys, three jobs - unchanged from the RL-only harness:
 *   request shape = method + endpoint (+ strategy + field when the method sends a body)
 *   bug path      = request shape + errorKind
 *   bug signature = method + endpoint + errorKind
 */
public final class BugOracle {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Size of the reachability-path space a body-carrying arm can emit:
     * 3 bodyless methods x 4 endpoints + 3 body methods x 4 endpoints x 8 strategies x 11 fields.
     */
    public static final int ADDRESSABLE_SHAPES = 3 * 4 + 3 * 4 * 8 * 11;

    private BugOracle() {}

    /** Only POST/PUT/PATCH carry a payload; for the rest, strategy and field are no-ops. */
    public static boolean sendsBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    /**
     * Request shape: the dial settings that can actually change the bytes we send.
     * Including strategy/field for bodyless methods aliases one DELETE bug into dozens
     * of fake "discoveries", since those requests are byte-identical.
     *
     * An arm with no notion of strategy/field (RESTler's main driver) passes the labels
     * it does have; the key stays comparable at the method+endpoint level, which is the
     * level at which both arms address the API.
     */
    public static String requestShapeKey(String method, String endpoint, String strategy, String field) {
        if (!sendsBody(method)) return method + "+" + endpoint;
        return method + "+" + endpoint + "+" + strategy + "+" + field;
    }

    /**
     * Bug path: the request shape PLUS what the server did with it.
     *
     * Shape alone is too coarse. DELETE /points has three distinct outcomes behind one
     * shape - the deliberate guard and the two planted crashes - and keying on shape
     * alone lets the trivial guard absorb the novelty that both hidden defects would
     * otherwise earn.
     */
    public static String bugPathKey(String requestShape, HttpResult r) {
        return requestShape + "+" + errorKind(r);
    }

    /** Strips the trailing errorKind off a bug path, recovering the request shape. */
    public static String requestShapeOf(String bugPath) {
        int i = bugPath.lastIndexOf('+');
        return i < 0 ? bugPath : bugPath.substring(0, i);
    }

    /** Real-bug identity: what the server actually did, not how we got there. */
    public static String bugSignature(String method, String endpoint, HttpResult r) {
        return method + "+" + endpoint + "+" + errorKind(r);
    }

    /**
     * GUARD  = deliberate internalServerError() with an empty body.
     * CRASH  = unhandled exception; the class name needs
     *          server.error.include-exception=true in application.properties.
     */
    public static String errorKind(HttpResult r) {
        String body = r.body();
        if (body == null || body.isBlank()) return "GUARD";
        try {
            String ex = MAPPER.readTree(body).path("exception").asText("");
            if (!ex.isEmpty()) return "CRASH:" + ex.substring(ex.lastIndexOf('.') + 1);
        } catch (Exception ignored) {
            // fall through
        }
        return "CRASH:unknown";
    }

    /** True when the response is a server-side failure, i.e. a finding. */
    public static boolean isBug(HttpResult r) {
        return r != null && r.status() == 500;
    }

    /** Extracts {@code id} from a single-object response body; null if absent. */
    public static String extractId(String body) {
        try {
            JsonNode id = MAPPER.readTree(body).path("id");
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts the first element's {@code id} from an array response body; null if absent. */
    public static String extractFirstId(String body) {
        try {
            JsonNode arr = MAPPER.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) return null;
            JsonNode id = arr.get(0).path("id");
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
