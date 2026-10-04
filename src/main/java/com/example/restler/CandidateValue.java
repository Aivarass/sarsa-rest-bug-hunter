package com.example.restler;

/**
 * One dictionary entry: the raw JSON text to splice into a body, plus where it came
 * from.
 *
 * The provenance labels exist so request-shape coverage can be attributed on the same
 * axes the RL arm uses ({@code strategy} x {@code field}). For the vocabulary-matched
 * arm they are the RL agent's own labels, which makes that arm's coverage directly
 * comparable with arm A's 1,068-shape space; for RESTler's own dictionaries they name
 * the dictionary instead, and the README says so rather than implying a comparison that
 * does not hold.
 */
public record CandidateValue(String json, String strategyLabel, String fieldLabel) {}
