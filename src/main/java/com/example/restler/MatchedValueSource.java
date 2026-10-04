package com.example.restler;

import com.example.harness.BugOracle;
import com.example.sarsa.generator.PayloadGenerator;
import com.example.sarsa.strategy.Endpoint;
import com.example.sarsa.strategy.Field;
import com.example.sarsa.strategy.Intensity;
import com.example.sarsa.strategy.Strategy;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A RESTler custom dictionary whose contents are the RL arm's own value vocabulary.
 *
 * This is the like-for-like arm. Every other difference between the two testers is held
 * constant by the shared harness; this class removes the last one, so that arm A and
 * arm C6 differ *only* in how they decide which request to send next.
 *
 * Construction is mechanical and cannot be tuned toward the defects: for a given leaf,
 * it walks the RL generator's own axes - 11 fields x 8 strategies x 2 reachable
 * intensities - asks {@link PayloadGenerator} for the body it would have produced, and
 * lifts out that leaf's value verbatim. Whatever the RL agent could have sent for this
 * field is therefore in the dictionary, and nothing else is. No value is written by
 * hand here.
 *
 * Supplying a custom dictionary is ordinary documented RESTler usage
 * ({@code docs/user-guide/FuzzingDictionary.md}); the only unusual thing is where the
 * values come from.
 */
public class MatchedValueSource implements ValueSource {

    /** The two intensities any action can actually select; MODERATE is unreachable. */
    private static final Intensity[] INTENSITIES = {Intensity.MILD, Intensity.AGGRESSIVE};

    /**
     * The RL arm's *effective* axes, which is what its shape keys are built from.
     *
     * {@code StrategyState.getEffectiveStrategy/getEffectiveField} fold NONE into VALID
     * and ALL, so the agent never emits a NONE label and its shape space is
     * 8 strategies x 11 fields. Walking the raw {@code values()} arrays here emitted
     * NONE labels too, pushing this arm's shape count to 1,287 - outside the 1,068 space
     * arm A is scored on, which quietly broke the one coverage comparison that is
     * supposed to be like-for-like.
     */
    private static final Strategy[] STRATEGIES = Arrays.stream(Strategy.values())
            .filter(s -> s != Strategy.NONE).toArray(Strategy[]::new);
    private static final Field[] FIELDS = Arrays.stream(Field.values())
            .filter(f -> f != Field.NONE).toArray(Field[]::new);

    /**
     * Same values, NEGATIVE promoted to index 0.
     *
     * Dictionary order is a user's choice, and it is load-bearing for RESTler's deep
     * modes: {@code extend} carries forward the *first valid* rendering, so whichever
     * interesting value sits earliest in the list is the one the search builds on.
     * RESTler's own invalid-value generator puts its boundary classes first, so this is
     * a realistic configuration rather than a contrivance - and it is the one
     * configuration in which a deep mode can carry a poisoned prefix forward.
     *
     * Note what it cannot do: a RESTler dictionary is per-primitive (optionally
     * per-endpoint) and never per-method, so this one ordering has to serve both
     * {@code POST /prices}, which rejects a negative, and {@code PATCH /prices}, which
     * accepts it. That is exactly the point - the POST settles on the valid value at
     * index 1 while the PATCH keeps the negative at index 0.
     */
    private static final Strategy[] STRATEGIES_NEGATIVE_FIRST = promote(Strategy.NEGATIVE);

    /** How this dictionary orders its strategies. */
    public enum Order {
        /** Valid value first - the shape RESTler's own default dictionary has. */
        VALID_FIRST,
        /** Negative first on every leaf. Breaks resource creation; see {@link #PER_ENDPOINT}. */
        NEGATIVE_FIRST,
        /**
         * Negative first only on **single-leaf** endpoints; valid first on multi-leaf ones.
         *
         * A blanket negative-first ordering is self-defeating, and the reason is the
         * frozen-leaf effect again: {@code /items} has three leaves and
         * {@code max_combinations} is 20 < 20x20x20, so leaves 2 and 3 never move off
         * index 0. With a negative there, {@code quantity} is negative in *every*
         * rendering, {@code POST /items} always hits its guard, and nothing in the chain
         * can be created at all - measured: 0 prices, 0 discounts, 0 points, and
         * {@code POST /prices} never even addressed.
         *
         * Single-leaf endpoints have no frozen leaf: {@code POST /prices} rejects the
         * negative at index 0 and settles on the valid value at index 1, while
         * {@code PATCH /prices} *accepts* index 0 - so the negative rendering becomes the
         * first valid one and is the one {@code extend} carries forward. That is the only
         * way a deep mode can build on a poisoned prefix.
         *
         * Per-resource dictionaries are a documented RESTler feature, and the rule here
         * is mechanical - "leaf count", observable from the spec - rather than anything
         * about the planted defects. The operator signal is equally generic: RESTler's own
         * run summary would show every {@code POST /items} returning 500.
         */
        PER_ENDPOINT
    }

    private static Strategy[] promote(Strategy first) {
        List<Strategy> out = new ArrayList<>();
        out.add(first);
        for (Strategy s : STRATEGIES) if (s != first) out.add(s);
        return out.toArray(new Strategy[0]);
    }

    private final PayloadGenerator generator;
    /** Which strategy ordering this dictionary uses. */
    private final Order order;
    /**
     * One cursor **per leaf**, not one shared cursor.
     *
     * A shared cursor made a leaf's index-0 value depend on how many other leaves had
     * been asked for first, and index 0 is not an ordinary entry: when
     * {@code max_combinations} is smaller than the product of the leaves' dictionary
     * sizes, the renderer freezes all but one leaf at index 0 for every single
     * combination (real RESTler does the same via {@code itertools.product} truncation,
     * where index 0 of each primitive is its one default value - always a usable one).
     *
     * With a shared cursor, {@code POST /items} drew index 0 = {@code null} for
     * {@code quantity}, so every rendering hit the controller's Integer unboxing and
     * returned 500. {@code items.id} was never produced, so nothing downstream of Item
     * was reachable, and every vocabulary-matched arm looked completely incapable - a
     * false result that had nothing to do with RESTler.
     *
     * The fix is to be an actual dictionary: **one fixed list per parameter**, computed
     * once and handed to every request type that uses that parameter, with
     * {@code Strategy.VALID} at index 0 - which is exactly RESTler's model, where
     * {@code restler_fuzzable_number} is a single list used everywhere it appears.
     * Keyed by size as well, because the main driver and the checker ask for different
     * budgets and are separate dictionaries in the real tool too.
     */
    private final Map<String, List<CandidateValue>> dictionary = new HashMap<>();
    /** Set alongside the last returned batch, for request-shape attribution. */
    private Strategy lastStrategy = Strategy.VALID;
    private Field lastField = Field.ALL;

    public MatchedValueSource(long seed) {
        this(seed, Order.VALID_FIRST);
    }

    public MatchedValueSource(long seed, Order order) {
        this.generator = new PayloadGenerator(seed);
        this.order = order;
    }

    @Override
    public String label() {
        return switch (order) {
            case VALID_FIRST -> "MATCHED";
            case NEGATIVE_FIRST -> "MATCHED_NEGFIRST";
            case PER_ENDPOINT -> "MATCHED_NEGFIRST_PE";
        };
    }

    /** Multi-leaf endpoints keep a valid value at index 0; see {@link Order#PER_ENDPOINT}. */
    private boolean negativeFirstFor(Endpoint endpoint) {
        return switch (order) {
            case VALID_FIRST -> false;
            case NEGATIVE_FIRST -> true;
            case PER_ENDPOINT -> leafCount(endpoint) == 1;
        };
    }

    private static int leafCount(Endpoint endpoint) {
        return endpoint == Endpoint.ITEMS ? 3 : 1;
    }

    /** Strategy that produced the most recent value - used for the shape key. */
    public Strategy lastStrategy() {
        return lastStrategy;
    }

    public Field lastField() {
        return lastField;
    }

    /**
     * Mirrors the ids the RL arm would have had in hand, so a generated body's parent-id
     * slot is realistic. The value is discarded - only the leaf is lifted out - but the
     * generator's branching depends on whether an id is present.
     */
    public void setIds(Long itemId, Long priceId, Long discountId) {
        generator.setLastItemId(itemId);
        generator.setLastPriceId(priceId);
        generator.setLastDiscountId(discountId);
    }

    @Override
    public List<CandidateValue> values(Endpoint endpoint, RequestType.Block block, int n) {
        return dictionary.computeIfAbsent(
                endpoint.name() + "." + block.field() + "#" + n,
                k -> buildList(endpoint, block, n));
    }

    /** Walks the RL generator's axes from the start, so index 0 is always VALID. */
    private List<CandidateValue> buildList(Endpoint endpoint, RequestType.Block block, int n) {
        Field[] fields = FIELDS;
        Strategy[] strategies = negativeFirstFor(endpoint) ? STRATEGIES_NEGATIVE_FIRST : STRATEGIES;
        Set<String> seen = new LinkedHashSet<>();
        List<CandidateValue> out = new ArrayList<>();
        // Bounded walk: enough turns of the cursor to fill n distinct values, but never
        // an unbounded loop if a leaf simply has few distinct renderings.
        int maxAttempts = n * 8 + fields.length * strategies.length * INTENSITIES.length;
        for (int c = 0; c < maxAttempts && out.size() < n; c++) {
            // STRATEGY-major, and this ordering is load-bearing. The main driver caps a
            // leaf at max_combinations values (20 by default), so whichever axis varies
            // fastest is the only one the main search ever sees. Walking fields fastest
            // filled all 20 slots with NONE/VALID payloads - every price positive - and
            // arm C6 would then have been structurally unable to arm Defect 1, reported
            // as a search-strategy failure when it was an artefact of this loop.
            // Strategy-major puts one value from each of the 9 classes in the first 9
            // slots, which is also how a dictionary is meant to be written (one value
            // per interesting class) and how RESTler's own generator orders its output.
            Strategy strategy = strategies[c % strategies.length];
            Field field = fields[(c / strategies.length) % fields.length];
            Intensity intensity = INTENSITIES[(c / (strategies.length * fields.length)) % INTENSITIES.length];

            String value = leafOf(endpoint, block.field(), field, strategy, intensity);
            if (value == null) continue;
            if (seen.add(value)) {
                lastStrategy = strategy;
                lastField = field;
                // Labelled with the RL arm's own dial pair, so a shape key emitted here
                // lands in exactly the same 1,068-shape space arm A is scored on.
                out.add(new CandidateValue(value, strategy.name(), field.name()));
            }
        }
        return out;
    }

    /**
     * Asks the RL generator for a body and lifts out one leaf as raw JSON text.
     *
     * Returns null when that (field, strategy) combination does not emit the leaf at all
     * - several strategies deliberately drop fields - which is itself faithful: a value
     * the RL arm cannot produce for this leaf does not belong in the matched dictionary.
     */
    private String leafOf(Endpoint endpoint, String leaf, Field field,
                          Strategy strategy, Intensity intensity) {
        try {
            String payload = generator.generate(endpoint, field, strategy, intensity);
            JsonNode root = BugOracle.mapper().readTree(payload);
            JsonNode node = root.path(leaf);
            if (node.isMissingNode()) return null;
            return node.toString();
        } catch (Exception e) {
            // A strategy that emits malformed JSON has no extractable leaf. Skip it;
            // body-level malformation is the PayloadBody checker's job, not a value's.
            return null;
        }
    }
}
