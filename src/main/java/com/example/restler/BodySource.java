package com.example.restler;

import com.example.sarsa.strategy.Endpoint;

import java.util.List;

/**
 * Supplies whole request bodies rather than per-parameter values.
 *
 * This is the {@code restler_custom_payload} shape of RESTler configuration - a
 * documented feature for replacing an entire body instead of filling in primitives
 * (see {@code docs/user-guide/FuzzingDictionary.md}). It exists here to close the last
 * vocabulary gap between the arms: with it, the RESTler arm's available request bytes
 * are exactly the RL arm's, and the two differ only in which request to send next.
 *
 * Index 0 of every list must be a body the service accepts. The renderer treats the
 * combination id as a direct index here, so unlike the per-parameter path there is no
 * frozen-leaf effect - but the search still depends on combination 0 standing up long
 * enough to build a dependency chain.
 */
public interface BodySource {

    /** One candidate body, with the provenance used for request-shape attribution. */
    record BodyCandidate(String json, String strategyLabel, String fieldLabel) {}

    /** Short name, used in request-shape keys. */
    String label();

    /** Up to {@code n} candidate bodies for this endpoint, valid one first. */
    List<BodyCandidate> bodies(Endpoint endpoint, int n);
}
