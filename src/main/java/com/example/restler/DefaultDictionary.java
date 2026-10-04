package com.example.restler;

import com.example.sarsa.strategy.Endpoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RESTler's default mutations dictionary, verbatim.
 *
 * Source: {@code Restler.Compiler/Grammar.fs:537 DefaultPrimitiveValues} and
 * {@code Restler.Compiler/Dictionary.fs:226 DefaultMutationsDictionary} - exactly one
 * value per primitive type:
 *
 * <pre>
 *   string -> "fuzzstring"      int    -> 1
 *   number -> 1.23              bool   -> true
 * </pre>
 *
 * This is what a user gets by running the compiler and fuzzing without customising the
 * dictionary, and it is the single most consequential fact in this experiment: there is
 * **no negative number in it**. On this API that makes the main driver structurally
 * incapable of arming either planted defect - a property of the dictionary, not of BFS.
 * Measured anyway, because "out-of-the-box RESTler" is a result people need.
 *
 * Optionally extended with negative values ({@link #withNegatives()}), which is the
 * one-line customisation any user would make after reading the API - that arm exists so
 * the headline cannot be accused of hiding behind a default.
 */
public class DefaultDictionary implements ValueSource {

    private final boolean negatives;

    private DefaultDictionary(boolean negatives) {
        this.negatives = negatives;
    }

    public static DefaultDictionary restlerDefaults() {
        return new DefaultDictionary(false);
    }

    /**
     * Defaults plus one negative value per numeric primitive.
     *
     * Chosen as the mirror of the default's own style (a single plain value, -1 / -1.23)
     * rather than a value picked to trip the guard, so this stays a customisation rather
     * than a hint.
     */
    public static DefaultDictionary withNegatives() {
        return new DefaultDictionary(true);
    }

    @Override
    public String label() {
        return negatives ? "DICT_NEG" : "DICT";
    }

    @Override
    public List<CandidateValue> values(Endpoint endpoint, RequestType.Block block, int n) {
        List<String> raw = new ArrayList<>();
        switch (block.primitive()) {
            case STRING -> raw.add("\"fuzzstring\"");
            case INT -> {
                raw.add("1");
                if (negatives) raw.add("-1");
            }
            case NUMBER -> {
                raw.add("1.23");
                if (negatives) raw.add("-1.23");
            }
        }
        List<CandidateValue> out = new ArrayList<>(raw.size());
        String field = block.field().toUpperCase();
        for (String v : raw) out.add(new CandidateValue(v, label(), field));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** The primitive types this dictionary covers, for the fidelity report. */
    public static List<String> covered() {
        return Arrays.asList("string", "int", "number");
    }
}
