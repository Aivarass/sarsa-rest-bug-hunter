package com.example.restler;

import com.example.sarsa.strategy.Endpoint;

import java.util.List;

/**
 * Where a rendering gets its leaf values - a RESTler mutations dictionary, or a
 * checker's value generator.
 *
 * Isolated behind an interface because the *value vocabulary* is the one axis that has
 * to be controlled separately from the search strategy. RESTler's own defaults contain
 * no negative number, so an arm using them cannot arm either planted defect no matter
 * how well it searches; an arm using the RL agent's vocabulary is directly comparable.
 * Both are measured. See docs/RESTLER-EXPERIMENT.md, audit items A1 and B9.
 */
public interface ValueSource {

    /** Short name, used in request-shape keys so coverage can be attributed. */
    String label();

    /**
     * Up to {@code n} candidate values for one leaf, as raw JSON text ready to splice
     * into a body - strings already quoted, numbers bare.
     *
     * Order is priority order: a renderer that can afford only a few combinations takes
     * the first few, exactly as RESTler walks the dictionary list in order.
     */
    List<CandidateValue> values(Endpoint endpoint, RequestType.Block block, int n);
}
