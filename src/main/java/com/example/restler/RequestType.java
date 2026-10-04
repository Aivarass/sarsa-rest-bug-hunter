package com.example.restler;

import com.example.sarsa.strategy.Endpoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One request type in the grammar: a (method, endpoint) pair with its producer-consumer
 * edges and its fuzzable leaves.
 *
 * This is the Java equivalent of one request definition in a RESTler {@code grammar.py}
 * - the unit that {@code driver.py:extend} appends to a sequence and
 * {@code sequences.py:render} fills in with values.
 */
public class RequestType {

    /** A fuzzable leaf value in the body, i.e. one {@code restler_fuzzable_*} block. */
    public record Block(String field, Primitive primitive) {}

    public enum Primitive { STRING, INT, NUMBER }

    public final String method;          // GET, GET_ALL, POST, PUT, PATCH, DELETE
    public final Endpoint endpoint;
    /** Addresses a single resource, so it consumes its own endpoint's id via the path. */
    public final boolean pathId;
    /** Endpoint whose id this request embeds in its body; null when it embeds none. */
    public final Endpoint parent;
    public final boolean hasBody;
    public final List<Block> blocks;

    private final Set<String> consumes = new LinkedHashSet<>();
    private final String produces;

    public RequestType(String method, Endpoint endpoint, boolean pathId, Endpoint parent,
                       boolean hasBody, List<Block> blocks) {
        this.method = method;
        this.endpoint = endpoint;
        this.pathId = pathId;
        this.parent = parent;
        this.hasBody = hasBody;
        this.blocks = blocks;

        if (pathId) consumes.add(dynName(endpoint));
        if (hasBody && parent != null) consumes.add(dynName(parent));
        // POST and PUT return the stored object, so both yield an id. This matches
        // RESTler's inference, which only accepts POST/PUT response producers
        // (Dependencies.fs:236-239) unless GET producers are explicitly enabled.
        this.produces = ("POST".equals(method) || "PUT".equals(method)) ? dynName(endpoint) : null;
    }

    public static String dynName(Endpoint e) {
        return e.name().toLowerCase() + ".id";
    }

    /** Dynamic objects this request needs in scope before it can be rendered. */
    public Set<String> consumes() {
        return consumes;
    }

    /** Dynamic object this request yields on success, or null. */
    public String produces() {
        return produces;
    }

    /**
     * Variant of {@link #produces()} for the {@code allowGetProducers} configuration:
     * a GET-all response carries ids too, so enabling it lets a sequence re-acquire a
     * resource it did not create. The RL arm does exactly this with
     * {@code extractFirstId}, so this option exists to remove that asymmetry.
     */
    public String producesWithGet() {
        if (produces != null) return produces;
        return "GET_ALL".equals(method) ? dynName(endpoint) : null;
    }

    /** The URL path, with {id} left for the renderer to substitute. */
    public String pathTemplate() {
        String base = endpoint.name().toLowerCase();
        return pathId ? base + "/{id}" : base;
    }

    /** The HTTP verb actually sent; GET_ALL is a GET on the collection. */
    public String httpVerb() {
        return "GET_ALL".equals(method) ? "GET" : method;
    }

    public String key() {
        return method + " /" + endpoint.name().toLowerCase() + (pathId ? "/{id}" : "");
    }

    @Override
    public String toString() {
        return key();
    }

    public List<String> blockFields() {
        List<String> out = new ArrayList<>();
        for (Block b : blocks) out.add(b.field());
        return out;
    }
}
