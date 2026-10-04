package com.example.restler;

import com.example.harness.BugOracle;
import com.example.sarsa.generator.PayloadGenerator;
import com.example.sarsa.strategy.Endpoint;
import com.example.sarsa.strategy.Field;
import com.example.sarsa.strategy.Intensity;
import com.example.sarsa.strategy.Strategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole bodies drawn from the RL arm's own generator - the maximally-matched vocabulary.
 *
 * {@link MatchedValueSource} matches the *set* of values but still composes bodies
 * RESTler's way: one varying leaf, the rest held at their valid rendering. This source
 * removes even that difference by handing over the complete body
 * {@link PayloadGenerator} would have produced for a given {@code (strategy, field)}
 * pair - byte-for-byte a request the RL agent could have sent.
 *
 * The parent id is the one thing substituted: the generator fills that slot with
 * whatever id it was last told about, and RESTler must fill it with the dynamic object
 * its own prefix produced. Substitution is structural (parse, set
 * {@code <parent>.id}, re-serialise) rather than textual, so a body that deliberately
 * omits or mangles the parent object is passed through untouched - which is faithful,
 * because the RL arm sends those too.
 *
 * Strategy-major ordering, {@code VALID} first, for the reasons in
 * {@link MatchedValueSource}: the combination id indexes this list directly, and the
 * search can only build a chain if combination 0 is accepted.
 */
public class MatchedBodySource implements BodySource {

    private static final Intensity[] INTENSITIES = {Intensity.MILD, Intensity.AGGRESSIVE};
    private static final Strategy[] STRATEGIES = Arrays.stream(Strategy.values())
            .filter(s -> s != Strategy.NONE).toArray(Strategy[]::new);
    private static final Field[] FIELDS = Arrays.stream(Field.values())
            .filter(f -> f != Field.NONE).toArray(Field[]::new);

    /** The body property that carries the parent's id, per endpoint. */
    private static final Map<Endpoint, String> PARENT_FIELD = Map.of(
            Endpoint.PRICES, "item",
            Endpoint.DISCOUNTS, "price",
            Endpoint.POINTS, "discount");

    private final PayloadGenerator generator;
    private final Map<String, List<BodyCandidate>> cache = new HashMap<>();

    public MatchedBodySource(long seed) {
        this.generator = new PayloadGenerator(seed);
        // A plausible parent id so the generator takes its "id in hand" branches; the
        // value is overwritten at render time by the real dynamic object.
        generator.setLastItemId(1L);
        generator.setLastPriceId(1L);
        generator.setLastDiscountId(1L);
    }

    @Override
    public String label() {
        return "MATCHED_BODY";
    }

    @Override
    public List<BodyCandidate> bodies(Endpoint endpoint, int n) {
        return cache.computeIfAbsent(endpoint.name() + "#" + n, k -> build(endpoint, n));
    }

    private List<BodyCandidate> build(Endpoint endpoint, int n) {
        Set<String> seen = new LinkedHashSet<>();
        List<BodyCandidate> out = new ArrayList<>();
        int axes = STRATEGIES.length * FIELDS.length * INTENSITIES.length;
        for (int c = 0; c < axes * 4 && out.size() < n; c++) {
            Strategy strategy = STRATEGIES[c % STRATEGIES.length];
            Field field = FIELDS[(c / STRATEGIES.length) % FIELDS.length];
            Intensity intensity = INTENSITIES[(c / (STRATEGIES.length * FIELDS.length)) % INTENSITIES.length];
            String body;
            try {
                body = generator.generate(endpoint, field, strategy, intensity);
            } catch (Exception e) {
                continue;
            }
            if (body == null || body.isBlank()) continue;
            if (seen.add(body)) {
                out.add(new BodyCandidate(body, strategy.name(), field.name()));
            }
        }
        return out;
    }

    /**
     * Replaces the parent object's id with the dynamic object the prefix produced.
     *
     * Returns the body unchanged when there is no parent object to patch - either the
     * endpoint has no parent, or this particular payload deliberately dropped or
     * mangled it. Both are bodies the RL arm sends, so neither is corrected here.
     */
    public static String withParentId(Endpoint endpoint, String body, String parentId) {
        String parentField = PARENT_FIELD.get(endpoint);
        if (parentField == null || parentId == null) return body;
        try {
            JsonNode root = BugOracle.mapper().readTree(body);
            if (!root.isObject()) return body;
            JsonNode parent = root.get(parentField);
            if (parent == null || !parent.isObject()) return body;
            ((ObjectNode) parent).put("id", Long.parseLong(parentId));
            return root.toString();
        } catch (Exception e) {
            return body;
        }
    }
}
