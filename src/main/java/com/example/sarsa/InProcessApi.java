package com.example.sarsa;

import com.example.api.Application;
import com.example.api.controller.*;
import com.example.api.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * The real API with the socket removed.
 *
 * Roughly 0.96 ms of every request was localhost TCP plus Spring dispatch, and a trial
 * issues ~280,000 requests - so essentially all trial time was transport, not learning.
 * This boots the SAME Spring context with WebApplicationType.NONE and calls the SAME
 * controller beans through the SAME auto-configured ObjectMapper.
 *
 * It deliberately keeps the JSON round-trip. Jackson's coercion rules are load-bearing
 * for these defects - null becoming 0 for a primitive int, float-to-int truncation, and
 * the "point"/"points" key mismatch that made PATCH /points inert. A dispatcher that
 * passed objects straight through would not reproduce them, and would silently
 * invalidate anything measured on it. Only the transport is skipped.
 *
 * Equivalence is verifiable rather than assumed: trials are deterministic given a seed,
 * so HTTP mode and this mode must produce bit-identical result lines.
 */
final class InProcessApi {

    private final ConfigurableApplicationContext ctx;
    private final ObjectMapper mapper;
    private final ItemController items;
    private final PriceController prices;
    private final DiscountController discounts;
    private final PointsController points;

    InProcessApi() {
        this.ctx = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .properties("logging.level.root=WARN", "server.error.include-exception=true")
                .run();
        this.mapper = ctx.getBean(ObjectMapper.class);
        this.items = ctx.getBean(ItemController.class);
        this.prices = ctx.getBean(PriceController.class);
        this.discounts = ctx.getBean(DiscountController.class);
        this.points = ctx.getBean(PointsController.class);
    }

    /** Mirrors SarsaRestTester.send: (method, "items" or "items/5", body) -> status + body. */
    SarsaRestTester.HttpResult call(String method, String path, String json) {
        String[] parts = path.split("/");
        String endpoint = parts[0];
        Long id = null;
        if (parts.length > 1) {
            try {
                id = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                return new SarsaRestTester.HttpResult(400, "");   // Spring: unparseable @PathVariable
            }
        }
        try {
            return encode(dispatch(method, endpoint, id, json));
        } catch (BadBody e) {
            return new SarsaRestTester.HttpResult(400, "");       // Spring: body will not bind
        } catch (Exception e) {
            // Spring's error handler with include-exception=true surfaces the class name,
            // which is exactly what errorKind() parses to tell CRASH from GUARD.
            Throwable t = e.getCause() != null && e instanceof RuntimeException ? e : e;
            return new SarsaRestTester.HttpResult(500,
                    "{\"status\":500,\"exception\":\"" + t.getClass().getName() + "\"}");
        }
    }

    private static final class BadBody extends RuntimeException {}

    private <T> T body(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new BadBody();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> patchBody(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BadBody();
        }
    }

    private ResponseEntity<?> dispatch(String m, String ep, Long id, String json) {
        switch (ep) {
            case "items": return switch (m) {
                case "GET"    -> id == null ? items.getAllItems() : items.getItem(id);
                case "POST"   -> items.createItem(body(json, Item.class));
                case "PUT"    -> items.updateItem(id, body(json, Item.class));
                case "PATCH"  -> items.patchItem(id, patchBody(json));
                case "DELETE" -> id == null ? items.deleteAllItems() : items.deleteItem(id);
                default -> throw new IllegalArgumentException(m);
            };
            case "prices": return switch (m) {
                case "GET"    -> id == null ? prices.getAllPrices() : prices.getPrice(id);
                case "POST"   -> prices.createPrice(body(json, Price.class));
                case "PUT"    -> prices.updatePrice(id, body(json, Price.class));
                case "PATCH"  -> prices.patchPrice(id, patchBody(json));
                case "DELETE" -> id == null ? prices.deleteAllPrices() : prices.deletePrice(id);
                default -> throw new IllegalArgumentException(m);
            };
            case "discounts": return switch (m) {
                case "GET"    -> id == null ? discounts.getAllDiscounts() : discounts.getDiscount(id);
                case "POST"   -> discounts.createDiscount(body(json, Discount.class));
                case "PUT"    -> discounts.updateDiscount(id, body(json, Discount.class));
                case "PATCH"  -> discounts.patchDiscount(id, patchBody(json));
                case "DELETE" -> id == null ? discounts.deleteAllDiscounts() : discounts.deleteDiscount(id);
                default -> throw new IllegalArgumentException(m);
            };
            case "points": return switch (m) {
                case "GET"    -> id == null ? points.getAllPoints() : points.getPoints(id);
                case "POST"   -> points.createPoints(body(json, Point.class));
                case "PUT"    -> points.updatePoints(id, body(json, Point.class));
                case "PATCH"  -> points.patchPoints(id, patchBody(json));
                case "DELETE" -> id == null ? points.deleteAllPoints() : points.deletePoints(id);
                default -> throw new IllegalArgumentException(m);
            };
            default: throw new IllegalArgumentException(ep);
        }
    }

    /** A null ResponseEntity body must become "" - errorKind reads a blank body as GUARD. */
    private SarsaRestTester.HttpResult encode(ResponseEntity<?> re) throws Exception {
        int status = re.getStatusCode().value();
        Object b = re.getBody();
        return new SarsaRestTester.HttpResult(status, b == null ? "" : mapper.writeValueAsString(b));
    }

    void close() {
        try { ctx.close(); } catch (Exception ignored) { }
    }
}
