package com.example.restler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A request sequence plus, per request, the value combination that was last found valid
 * for it.
 *
 * Mirrors {@code engine/core/sequences.py}: the prefix is always replayed with each
 * request's last known valid combination, and only the final request iterates over
 * combinations. A sequence is therefore a *reproducible recipe* for reaching a state,
 * not a record of one past execution - which is why RESTler re-sends the whole prefix
 * every time rather than caching ids.
 */
public class Sequence {

    public final List<RequestType> requests;
    /** Combination id per request, parallel to {@link #requests}. */
    public final List<Integer> combinations;
    /**
     * Cached producer set. A Sequence is immutable, and bfs calls {@link #satisfies} once
     * per (request type, sequence) pair per generation - millions of times at a large
     * pool - so recomputing this set each time was pure overhead.
     */
    private Set<String> producesCache;
    private Set<String> producesWithGetCache;

    private Sequence(List<RequestType> requests, List<Integer> combinations) {
        this.requests = requests;
        this.combinations = combinations;
    }

    public static Sequence empty() {
        return new Sequence(List.of(), List.of());
    }

    public boolean isEmpty() {
        return requests.isEmpty();
    }

    public int length() {
        return requests.size();
    }

    public RequestType last() {
        return requests.get(requests.size() - 1);
    }

    /** Appends a request with combination id 0; the renderer will search from there. */
    public Sequence plus(RequestType req) {
        List<RequestType> r = new ArrayList<>(requests);
        List<Integer> c = new ArrayList<>(combinations);
        r.add(req);
        c.add(0);
        return new Sequence(r, c);
    }

    /** This sequence with the final request pinned to a combination that was valid. */
    public Sequence withLastCombination(int combo) {
        List<Integer> c = new ArrayList<>(combinations);
        c.set(c.size() - 1, combo);
        return new Sequence(new ArrayList<>(requests), c);
    }

    /**
     * Dynamic objects this sequence yields, for
     * {@code driver.py:42 validate_dependencies}: a request may be appended only if
     * every object it consumes is produced somewhere in the prefix.
     */
    public Set<String> produces(boolean allowGetProducers) {
        Set<String> cached = allowGetProducers ? producesWithGetCache : producesCache;
        if (cached != null) return cached;
        Set<String> out = new LinkedHashSet<>();
        for (RequestType r : requests) {
            String p = allowGetProducers ? r.producesWithGet() : r.produces();
            if (p != null) out.add(p);
        }
        if (allowGetProducers) producesWithGetCache = out;
        else producesCache = out;
        return out;
    }

    /** {@code driver.py:42}. */
    public boolean satisfies(RequestType consumer, boolean allowGetProducers) {
        return produces(allowGetProducers).containsAll(consumer.consumes());
    }

    /** Stable identity for the per-generation checker dedup. */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        for (RequestType r : requests) sb.append(r.key()).append(" -> ");
        return sb.toString();
    }

    @Override
    public String toString() {
        return signature();
    }
}
