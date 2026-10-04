package com.example.restler;

import com.example.sarsa.strategy.Endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.restler.RequestType.Block;
import static com.example.restler.RequestType.Primitive.INT;
import static com.example.restler.RequestType.Primitive.NUMBER;
import static com.example.restler.RequestType.Primitive.STRING;

/**
 * The compiled grammar for the API under test - what RESTler's compiler would produce
 * from an OpenAPI spec of these four controllers.
 *
 * Derivation rules, applied mechanically so the grammar cannot be tuned toward the
 * planted defects (see docs/RESTLER-EXPERIMENT.md, audit item B2):
 *
 *  - One request type per (method, path) actually served by a controller.
 *  - Body schemas come from the **model classes**: Item{name,description,quantity},
 *    Price{item,price}, Discount{price,discount}, Point{discount,points}. Nothing else
 *    is declared - no examples, no minimum/maximum, no annotations.
 *  - A nested {@code {"<parent>": {"id": N}}} property is a consumer of the parent
 *    collection's id. This is RESTler's own inference rule for nested object ids
 *    (Dependencies.fs:219 getNestedObjectProducer), so the chain is inferred, not
 *    donated.
 *  - POST and PUT responses are producers of their endpoint's id.
 *
 * Deliberately excluded, with reasons (audit items A6, A7, C9):
 *  - {@code DELETE /api/{collection}} (delete-all): the RL arm cannot address it, and
 *    including it lets this arm wipe its own chains. Configurable, and measured
 *    separately.
 *  - {@code HEAD} / {@code OPTIONS} on items: no analogue in the RL arm's action space,
 *    and neither can return 5xx (ItemController:97-112).
 *  - the scalar {@code itemId} / {@code discountId} keys read by the three PATCH
 *    re-link branches: {@code PayloadGenerator} never emits them, so they are
 *    unreachable for the RL arm. Matching the surface matters more than covering a
 *    branch neither planted defect needs. Documented as an API finding instead.
 *
 * What remains is exactly 24 request types = 6 methods x 4 endpoints, which is
 * precisely the RL arm's addressable surface.
 */
public final class ApiGrammar {

    /** Fuzzable leaves per endpoint, read off the model classes. */
    private static final Map<Endpoint, List<Block>> BLOCKS = Map.of(
            Endpoint.ITEMS, List.of(
                    new Block("name", STRING),
                    new Block("description", STRING),
                    new Block("quantity", INT)),
            Endpoint.PRICES, List.of(
                    new Block("price", NUMBER)),
            Endpoint.DISCOUNTS, List.of(
                    new Block("discount", NUMBER)),
            Endpoint.POINTS, List.of(
                    new Block("points", INT)));

    /** The chain, as the nested-id inference rule yields it. */
    private static final Map<Endpoint, Endpoint> PARENT = Map.of(
            Endpoint.PRICES, Endpoint.ITEMS,
            Endpoint.DISCOUNTS, Endpoint.PRICES,
            Endpoint.POINTS, Endpoint.DISCOUNTS);

    private ApiGrammar() {}

    /** The 24 matched request types, in a stable order. */
    public static List<RequestType> matched() {
        return build(false);
    }

    /** As {@link #matched()} plus the collection-level DELETE (audit item A6). */
    public static List<RequestType> withDeleteAll() {
        return build(true);
    }

    private static List<RequestType> build(boolean includeDeleteAll) {
        List<RequestType> out = new ArrayList<>();
        for (Endpoint e : Endpoint.values()) {
            List<Block> blocks = BLOCKS.get(e);
            Endpoint parent = PARENT.get(e);
            // Order mirrors the RL arm's HttpType enum so distributions line up.
            out.add(new RequestType("GET", e, true, null, false, List.of()));
            out.add(new RequestType("GET_ALL", e, false, null, false, List.of()));
            out.add(new RequestType("POST", e, false, parent, true, blocks));
            out.add(new RequestType("PUT", e, true, parent, true, blocks));
            out.add(new RequestType("PATCH", e, true, parent, true, blocks));
            out.add(new RequestType("DELETE", e, true, null, false, List.of()));
            if (includeDeleteAll) {
                out.add(new RequestType("DELETE_ALL", e, false, null, false, List.of()));
            }
        }
        return out;
    }

    /**
     * Builds the request body: the parent id comes from the sequence's dynamic objects,
     * the leaf values from the renderer.
     *
     * The templates are the model classes serialised, which is what the real compiler
     * emits from a schema. {@code values} is in {@link #BLOCKS} order.
     */
    public static String body(Endpoint endpoint, String parentId, List<String> values) {
        return switch (endpoint) {
            case ITEMS -> String.format(
                    "{\"name\": %s, \"description\": %s, \"quantity\": %s}",
                    values.get(0), values.get(1), values.get(2));
            case PRICES -> String.format(
                    "{\"item\": {\"id\": %s}, \"price\": %s}", parentId, values.get(0));
            case DISCOUNTS -> String.format(
                    "{\"price\": {\"id\": %s}, \"discount\": %s}", parentId, values.get(0));
            case POINTS -> String.format(
                    "{\"discount\": {\"id\": %s}, \"points\": %s}", parentId, values.get(0));
        };
    }

    /**
     * Rebuilds a body with one leaf replaced, leaving every other value at its original
     * rendering. This is what {@code InvalidValueChecker} does - one parameter at a
     * time (invalid_value_checker.py:300-315).
     */
    public static String bodyWithReplacement(Endpoint endpoint, String parentId,
                                             List<String> values, int blockIndex, String replacement) {
        List<String> copy = new ArrayList<>(values);
        copy.set(blockIndex, replacement);
        return body(endpoint, parentId, copy);
    }
}
