package com.example.sarsa.generator;

import com.example.sarsa.strategy.Endpoint;
import com.example.sarsa.strategy.Field;
import com.example.sarsa.strategy.Intensity;
import com.example.sarsa.strategy.Strategy;

import java.util.Random;

/**
 * PBT-style payload generator for REST API testing.
 * Generates fresh JSON payloads for each category.
 * 
 * Categories:
 *   1. VALID      - syntactically and semantically correct
 *   2. NULL       - null values in various fields
 *   3. NEGATIVE   - negative numbers, invalid values
 *   4. BOUNDARY   - edge cases (empty, huge, zero, MAX_INT)
 *   5. STRUCTURE  - missing fields, extra fields, wrong types
 */
public class PayloadGenerator {

    private final Random rng;

    // Character pools for string generation
    private static final String ALPHA = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String SPECIAL = "!@#$%^&*()_+-=[]{}|;':\",./<>?`~";
    private static final String UNICODE = "äöü中文";

    public PayloadGenerator(long seed) {
        this.rng = new Random(seed);
    }

    public PayloadGenerator() {
        this(System.nanoTime());
    }

    // ========================== Category 1: VALID ==========================

    public String valid() {
        String name = randomAlphaString(3, 20);
        String desc = randomAlphaString(5, 50);
        int qty = rng.nextInt(1000) + 1;  // 1-1000
        
        return String.format(
            "{\"name\": \"%s\", \"description\": \"%s\", \"quantity\": %d}",
            name, desc, qty
        );
    }

    // ========================== Category 2: NULL ==========================

    public String nullInjection() {
        int variant = rng.nextInt(5);
        String name = randomAlphaString(3, 10);
        int qty = rng.nextInt(100);
        
        return switch (variant) {
            case 0 -> "{\"name\": null, \"description\": \"test\", \"quantity\": " + qty + "}";
            case 1 -> "{\"name\": \"" + name + "\", \"description\": null, \"quantity\": " + qty + "}";
            case 2 -> "{\"name\": \"" + name + "\", \"description\": \"test\", \"quantity\": null}";
            case 3 -> "{\"name\": null, \"description\": null, \"quantity\": null}";
            case 4 -> "{\"name\": \"" + name + "\", \"quantity\": null}";  // missing desc + null qty
            default -> nullInjection();
        };
    }

    // ========================== Category 3: NEGATIVE ==========================

    public String negative() {
        int variant = rng.nextInt(4);
        String name = randomAlphaString(3, 10);
        int negQty = -(rng.nextInt(10000) + 1);  // -1 to -10000
        
        return switch (variant) {
            case 0 -> "{\"name\": \"" + name + "\", \"quantity\": " + negQty + "}";
            case 1 -> "{\"name\": \"" + name + "\", \"quantity\": " + Integer.MIN_VALUE + "}";
            case 2 -> "{\"name\": \"" + name + "\", \"quantity\": -0.5}";  // float instead of int
            case 3 -> "{\"name\": \"\", \"quantity\": " + negQty + "}";  // empty name + negative
            default -> negative();
        };
    }

    // ========================== Category 4: BOUNDARY ==========================

    public String boundary() {
        int variant = rng.nextInt(6);
        String name = randomAlphaString(3, 10);
        
        return switch (variant) {
            case 0 -> "{\"name\": \"\", \"quantity\": 0}";  // empty name, zero qty
            case 1 -> "{\"name\": \"" + name + "\", \"quantity\": " + Integer.MAX_VALUE + "}";
            case 2 -> "{\"name\": \"" + name + "\", \"quantity\": 0}";
            case 3 -> "{\"name\": \"" + randomAlphaString(1000, 2000) + "\", \"quantity\": 1}";  // huge name
            case 4 -> "{\"name\": \"" + name + "\", \"description\": \"" + 
                      randomAlphaString(5000, 10000) + "\", \"quantity\": 1}";  // huge description
            case 5 -> "{\"name\": \" \", \"quantity\": 1}";  // whitespace only name
            default -> boundary();
        };
    }

    // ========================== Category 5: STRUCTURE ==========================

    public String structure() {
        int variant = rng.nextInt(7);
        String name = randomAlphaString(3, 10);
        int qty = rng.nextInt(100);
        
        return switch (variant) {
            // Missing fields
            case 0 -> "{}";  // empty object
            case 1 -> "{\"quantity\": " + qty + "}";  // missing name
            case 2 -> "{\"name\": \"" + name + "\"}";  // missing quantity
            
            // Extra fields
            case 3 -> "{\"name\": \"" + name + "\", \"quantity\": " + qty + 
                      ", \"unknown\": \"extra\", \"hack\": true}";
            
            // Wrong types
            case 4 -> "{\"name\": 12345, \"quantity\": \"not a number\"}";
            case 5 -> "{\"name\": [\"array\"], \"quantity\": {\"object\": true}}";
            
            // Malformed-ish (but still valid JSON)
            case 6 -> "{\"name\": \"" + name + "\", \"name\": \"duplicate\", \"quantity\": " + qty + "}";
            
            default -> structure();
        };
    }

    // ========================== BONUS: Injection ==========================

    public String injection() {
        int variant = rng.nextInt(5);
        int qty = rng.nextInt(100);
        
        return switch (variant) {
            // SQL injection attempts
            case 0 -> "{\"name\": \"'; DROP TABLE items; --\", \"quantity\": " + qty + "}";
            case 1 -> "{\"name\": \"1' OR '1'='1\", \"quantity\": " + qty + "}";
            
            // XSS attempts
            case 2 -> "{\"name\": \"<script>alert('xss')</script>\", \"quantity\": " + qty + "}";
            
            // Path traversal
            case 3 -> "{\"name\": \"../../../etc/passwd\", \"quantity\": " + qty + "}";
            
            // Unicode/encoding
            case 4 -> "{\"name\": \"" + randomUnicodeString(5, 15) + "\", \"quantity\": " + qty + "}";
            
            default -> injection();
        };
    }

    // ========================== Helpers ==========================

    private String randomAlphaString(int minLen, int maxLen) {
        if (maxLen < minLen) maxLen = minLen;  // Defensive
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHA.charAt(rng.nextInt(ALPHA.length())));
        }
        return sb.toString();
    }

    private String randomSpecialString(int minLen, int maxLen) {
        if (maxLen < minLen) maxLen = minLen;  // Defensive
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len);
        String pool = ALPHA + SPECIAL;
        for (int i = 0; i < len; i++) {
            sb.append(pool.charAt(rng.nextInt(pool.length())));
        }
        return escapeJson(sb.toString());
    }

    private String randomUnicodeString(int minLen, int maxLen) {
        if (maxLen < minLen) maxLen = minLen;  // Defensive
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len);
        String pool = ALPHA + UNICODE;
        for (int i = 0; i < len; i++) {
            sb.append(pool.charAt(rng.nextInt(pool.length())));
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ========================== Convenience ==========================

    /**
     * Generate payload by category index (for SARSA action mapping)
     * 0 = VALID, 1 = NULL, 2 = NEGATIVE, 3 = BOUNDARY, 4 = STRUCTURE, 5 = INJECTION
     */
    public String byCategory(int category) {
        return switch (category) {
            case 0 -> valid();
            case 1 -> nullInjection();
            case 2 -> negative();
            case 3 -> boundary();
            case 4 -> structure();
            case 5 -> injection();
            default -> valid();
        };
    }

    // ========================== Strategy-Aware Generation ==========================

    // Track IDs for nested resource generation (set by SarsaRestTester)
    private Long lastItemId = null;
    private Long lastPriceId = null;

    public void setLastItemId(Long itemId) {
        this.lastItemId = itemId;
    }

    public void setLastPriceId(Long priceId) {
        this.lastPriceId = priceId;
    }

    /**
     * Generate payload based on endpoint, field target, strategy, and intensity.
     * This is the main entry point for strategy-aware SARSA.
     */
    public String generate(Endpoint endpoint, Field field, Strategy strategy, Intensity intensity) {
        return switch (endpoint) {
            case ITEMS -> generateItemsPayload(field, strategy, intensity);
            case PRICES -> generatePricesPayload(field, strategy, intensity);
            case DISCOUNTS -> generateDiscountsPayload(field, strategy, intensity);
        };
    }

    /**
     * Legacy method for backwards compatibility.
     */
    public String generate(Field field, Strategy strategy, Intensity intensity) {
        return generateItemsPayload(field, strategy, intensity);
    }

    private String generateItemsPayload(Field field, Strategy strategy, Intensity intensity) {
        int stringLen = getStringLength(intensity);
        int numMagnitude = getNumMagnitude(intensity);
        
        return switch (strategy) {
            case VALID -> generateValid(field, stringLen);
            case NULL_INJECT -> generateNullInject(field, stringLen);
            case NEGATIVE -> generateNegative(field, numMagnitude);
            case BOUNDARY -> generateBoundary(field, intensity);
            case STRUCTURE -> generateStructure(field);
            case INJECTION -> generateInjection(field);
            case TYPE_CONFUSE -> generateTypeConfuse(field);
            case ENCODING -> generateEncoding(field, stringLen);
            case NONE -> valid();
        };
    }

    private String generatePricesPayload(Field field, Strategy strategy, Intensity intensity) {
        int numMagnitude = getNumMagnitude(intensity);
        
        return switch (strategy) {
            case VALID -> generatePriceValid(field);
            case NULL_INJECT -> generatePriceNullInject(field);
            case NEGATIVE -> generatePriceNegative(field, numMagnitude);
            case BOUNDARY -> generatePriceBoundary(field, intensity);
            case STRUCTURE -> generatePriceStructure(field);
            case INJECTION -> generatePriceInjection(field);
            case TYPE_CONFUSE -> generatePriceTypeConfuse(field);
            case ENCODING -> generatePriceValid(field); // Encoding doesn't apply well to prices
            case NONE -> generatePriceValid(field);
        };
    }

    private int getStringLength(Intensity intensity) {
        return switch (intensity) {
            case MILD -> 3 + rng.nextInt(10);
            case MODERATE -> 10 + rng.nextInt(50);
            case AGGRESSIVE -> 100 + rng.nextInt(1000);
        };
    }

    private int getNumMagnitude(Intensity intensity) {
        return switch (intensity) {
            case MILD -> 1 + rng.nextInt(100);
            case MODERATE -> 1 + rng.nextInt(10000);
            case AGGRESSIVE -> 1 + rng.nextInt(Integer.MAX_VALUE / 2);
        };
    }

    // ========================== Field-Targeted Generators ==========================

    private String generateValid(Field field, int stringLen) {
        String name = randomAlphaString(3, stringLen);
        String desc = randomAlphaString(5, stringLen);
        int qty = rng.nextInt(1000) + 1;
        return String.format("{\"name\": \"%s\", \"description\": \"%s\", \"quantity\": %d}", name, desc, qty);
    }

    private String generateNullInject(Field field, int stringLen) {
        String name = randomAlphaString(3, stringLen);
        int qty = rng.nextInt(100);
        
        return switch (field) {
            case NAME -> "{\"name\": null, \"description\": \"test\", \"quantity\": " + qty + "}";
            case QUANTITY -> "{\"name\": \"" + name + "\", \"description\": \"test\", \"quantity\": null}";
            case DESCRIPTION -> "{\"name\": \"" + name + "\", \"description\": null, \"quantity\": " + qty + "}";
            case ALL -> "{\"name\": null, \"description\": null, \"quantity\": null}";
            default -> nullInjection(); // Random null injection
        };
    }

    private String generateNegative(Field field, int magnitude) {
        String name = randomAlphaString(3, 10);
        int negQty = -(rng.nextInt(magnitude) + 1);
        
        return switch (field) {
            case QUANTITY -> "{\"name\": \"" + name + "\", \"quantity\": " + negQty + "}";
            case NAME -> "{\"name\": \"\", \"quantity\": " + negQty + "}"; // Empty name + negative
            case ALL -> "{\"name\": \"\", \"description\": \"\", \"quantity\": " + negQty + "}";
            default -> negative(); // Random negative
        };
    }

    private String generateBoundary(Field field, Intensity intensity) {
        String name = randomAlphaString(3, 10);
        
        return switch (field) {
            case NAME -> switch (intensity) {
                case MILD -> "{\"name\": \"\", \"quantity\": 1}";
                case MODERATE -> "{\"name\": \"" + randomAlphaString(100, 500) + "\", \"quantity\": 1}";
                case AGGRESSIVE -> "{\"name\": \"" + randomAlphaString(5000, 10000) + "\", \"quantity\": 1}";
            };
            case QUANTITY -> switch (intensity) {
                case MILD -> "{\"name\": \"" + name + "\", \"quantity\": 0}";
                case MODERATE -> "{\"name\": \"" + name + "\", \"quantity\": " + Integer.MAX_VALUE / 2 + "}";
                case AGGRESSIVE -> "{\"name\": \"" + name + "\", \"quantity\": " + Integer.MAX_VALUE + "}";
            };
            case DESCRIPTION -> "{\"name\": \"" + name + "\", \"description\": \"" + 
                    randomAlphaString(1000, 5000) + "\", \"quantity\": 1}";
            default -> boundary();
        };
    }

    private String generateStructure(Field field) {
        String name = randomAlphaString(3, 10);
        int qty = rng.nextInt(100);
        
        return switch (field) {
            case NAME -> "{\"quantity\": " + qty + "}"; // Missing name
            case QUANTITY -> "{\"name\": \"" + name + "\"}"; // Missing quantity
            case UNKNOWN -> "{\"name\": \"" + name + "\", \"quantity\": " + qty + 
                    ", \"unknown\": \"extra\", \"hack\": true}";
            case ALL -> "{}"; // Empty object
            default -> structure();
        };
    }

    private String generateInjection(Field field) {
        int qty = rng.nextInt(100);
        String[] injections = {
            "'; DROP TABLE items; --",
            "1' OR '1'='1",
            "<script>alert('xss')</script>",
            "../../../etc/passwd",
            "${7*7}",
            "{{constructor.constructor('return this')()}}"
        };
        String inject = injections[rng.nextInt(injections.length)];
        
        return switch (field) {
            case NAME -> "{\"name\": \"" + inject + "\", \"quantity\": " + qty + "}";
            case DESCRIPTION -> "{\"name\": \"test\", \"description\": \"" + inject + "\", \"quantity\": " + qty + "}";
            default -> "{\"name\": \"" + inject + "\", \"description\": \"" + inject + "\", \"quantity\": " + qty + "}";
        };
    }

    private String generateTypeConfuse(Field field) {
        String name = randomAlphaString(3, 10);
        
        return switch (field) {
            case NAME -> "{\"name\": 12345, \"quantity\": 1}";
            case QUANTITY -> "{\"name\": \"" + name + "\", \"quantity\": \"not a number\"}";
            case DESCRIPTION -> "{\"name\": \"" + name + "\", \"description\": [\"array\"], \"quantity\": 1}";
            case ALL -> "{\"name\": 123, \"description\": {\"nested\": true}, \"quantity\": \"string\"}";
            default -> "{\"name\": 12345, \"quantity\": \"not a number\"}";
        };
    }

    private String generateEncoding(Field field, int stringLen) {
        String unicode = randomUnicodeString(5, Math.max(5, stringLen));
        int qty = rng.nextInt(100);
        
        return switch (field) {
            case NAME -> "{\"name\": \"" + unicode + "\", \"quantity\": " + qty + "}";
            case DESCRIPTION -> "{\"name\": \"test\", \"description\": \"" + unicode + "\", \"quantity\": " + qty + "}";
            default -> "{\"name\": \"" + unicode + "\", \"description\": \"" + unicode + "\", \"quantity\": " + qty + "}";
        };
    }

    // ========================== PRICES Endpoint Generators ==========================

    private String getItemIdJson() {
        // Use real item ID if available, otherwise use a fake one
        long itemId = lastItemId != null ? lastItemId : (rng.nextLong(1000) + 1);
        return String.format("{\"id\": %d}", itemId);
    }

    private String generatePriceValid(Field field) {
        double price = 10.0 + rng.nextDouble() * 990.0; // 10-1000
        String itemJson = getItemIdJson();
        return String.format("{\"item\": %s, \"price\": %.2f}", itemJson, price);
    }

    private String generatePriceNullInject(Field field) {
        double price = 10.0 + rng.nextDouble() * 100.0;
        String itemJson = getItemIdJson();
        
        return switch (field) {
            case PRICE -> "{\"item\": " + itemJson + ", \"price\": null}";
            case ITEM_ID -> "{\"item\": null, \"price\": " + price + "}";
            case ALL -> "{\"item\": null, \"price\": null}";
            default -> {
                int variant = rng.nextInt(2);
                yield variant == 0 
                    ? "{\"item\": null, \"price\": " + price + "}"
                    : "{\"item\": " + itemJson + ", \"price\": null}";
            }
        };
    }

    private String generatePriceNegative(Field field, int magnitude) {
        String itemJson = getItemIdJson();
        double negPrice = -(rng.nextDouble() * magnitude + 1);
        
        return switch (field) {
            case PRICE -> String.format("{\"item\": %s, \"price\": %.2f}", itemJson, negPrice);
            case ITEM_ID -> "{\"item\": {\"id\": -1}, \"price\": 50.0}"; // Invalid item ID
            case ALL -> String.format("{\"item\": {\"id\": -1}, \"price\": %.2f}", negPrice);
            default -> String.format("{\"item\": %s, \"price\": %.2f}", itemJson, negPrice);
        };
    }

    private String generatePriceBoundary(Field field, Intensity intensity) {
        String itemJson = getItemIdJson();
        
        return switch (field) {
            case PRICE -> switch (intensity) {
                case MILD -> "{\"item\": " + itemJson + ", \"price\": 0}";
                case MODERATE -> "{\"item\": " + itemJson + ", \"price\": " + Double.MAX_VALUE / 2 + "}";
                case AGGRESSIVE -> "{\"item\": " + itemJson + ", \"price\": " + Double.MAX_VALUE + "}";
            };
            case ITEM_ID -> switch (intensity) {
                case MILD -> "{\"item\": {\"id\": 0}, \"price\": 50.0}";
                case MODERATE -> "{\"item\": {\"id\": " + Integer.MAX_VALUE + "}, \"price\": 50.0}";
                case AGGRESSIVE -> "{\"item\": {\"id\": " + Long.MAX_VALUE + "}, \"price\": 50.0}";
            };
            default -> "{\"item\": {\"id\": 0}, \"price\": 0}";
        };
    }

    private String generatePriceStructure(Field field) {
        String itemJson = getItemIdJson();
        double price = 50.0 + rng.nextDouble() * 50.0;
        
        return switch (field) {
            case PRICE -> "{\"item\": " + itemJson + "}"; // Missing price
            case ITEM_ID -> "{\"price\": " + price + "}"; // Missing item
            case UNKNOWN -> "{\"item\": " + itemJson + ", \"price\": " + price + ", \"currency\": \"USD\", \"tax\": 0.1}";
            case ALL -> "{}"; // Empty object
            default -> {
                int variant = rng.nextInt(3);
                yield switch (variant) {
                    case 0 -> "{}";
                    case 1 -> "{\"price\": " + price + "}"; // Missing item
                    case 2 -> "{\"item\": " + itemJson + "}"; // Missing price
                    default -> generatePriceValid(field);
                };
            }
        };
    }

    private String generatePriceInjection(Field field) {
        String itemJson = getItemIdJson();
        String[] injections = {
            "'; DROP TABLE prices; --",
            "1' OR '1'='1",
            "<script>alert('xss')</script>",
            "../../../etc/passwd"
        };
        String inject = injections[rng.nextInt(injections.length)];
        
        return switch (field) {
            case PRICE -> "{\"item\": " + itemJson + ", \"price\": \"" + inject + "\"}";
            case ITEM_ID -> "{\"item\": {\"id\": \"" + inject + "\"}, \"price\": 50.0}";
            default -> "{\"item\": {\"id\": \"" + inject + "\"}, \"price\": \"" + inject + "\"}";
        };
    }

    private String generatePriceTypeConfuse(Field field) {
        String itemJson = getItemIdJson();
        
        return switch (field) {
            case PRICE -> "{\"item\": " + itemJson + ", \"price\": \"not a number\"}";
            case ITEM_ID -> "{\"item\": \"not an object\", \"price\": 50.0}";
            case ALL -> "{\"item\": [1, 2, 3], \"price\": {\"value\": 50}}";
            default -> {
                int variant = rng.nextInt(3);
                yield switch (variant) {
                    case 0 -> "{\"item\": " + itemJson + ", \"price\": \"fifty dollars\"}";
                    case 1 -> "{\"item\": 12345, \"price\": 50.0}";
                    case 2 -> "{\"item\": " + itemJson + ", \"price\": [50, 60, 70]}";
                    default -> generatePriceValid(field);
                };
            }
        };
    }

    // ========================== DISCOUNTS Endpoint Generators ==========================

    private String getPriceIdJson() {
        // Use real price ID if available, otherwise use a fake one
        long priceId = lastPriceId != null ? lastPriceId : (rng.nextLong(1000) + 1);
        return String.format("{\"id\": %d}", priceId);
    }

    private String generateDiscountsPayload(Field field, Strategy strategy, Intensity intensity) {
        int numMagnitude = getNumMagnitude(intensity);
        
        return switch (strategy) {
            case VALID -> generateDiscountValid(field);
            case NULL_INJECT -> generateDiscountNullInject(field);
            case NEGATIVE -> generateDiscountNegative(field, numMagnitude);
            case BOUNDARY -> generateDiscountBoundary(field, intensity);
            case STRUCTURE -> generateDiscountStructure(field);
            case INJECTION -> generateDiscountInjection(field);
            case TYPE_CONFUSE -> generateDiscountTypeConfuse(field);
            case ENCODING -> generateDiscountValid(field); // Encoding doesn't apply well to discounts
            case NONE -> generateDiscountValid(field);
        };
    }

    private String generateDiscountValid(Field field) {
        double discount = rng.nextDouble() * 50.0; // 0-50% discount
        String priceJson = getPriceIdJson();
        return String.format("{\"price\": %s, \"discount\": %.2f}", priceJson, discount);
    }

    private String generateDiscountNullInject(Field field) {
        double discount = 10.0 + rng.nextDouble() * 20.0;
        String priceJson = getPriceIdJson();
        
        return switch (field) {
            case PRICE -> "{\"price\": null, \"discount\": " + discount + "}";
            case ALL -> "{\"price\": null, \"discount\": null}";
            default -> {
                int variant = rng.nextInt(2);
                yield variant == 0 
                    ? "{\"price\": null, \"discount\": " + discount + "}"
                    : "{\"price\": " + priceJson + ", \"discount\": null}";
            }
        };
    }

    private String generateDiscountNegative(Field field, int magnitude) {
        String priceJson = getPriceIdJson();
        double negDiscount = -(rng.nextDouble() * Math.min(magnitude, 100) + 1); // Negative discount
        
        return switch (field) {
            case PRICE -> "{\"price\": {\"id\": -1}, \"discount\": 10.0}"; // Invalid price ID
            case ALL -> String.format("{\"price\": {\"id\": -1}, \"discount\": %.2f}", negDiscount);
            default -> String.format("{\"price\": %s, \"discount\": %.2f}", priceJson, negDiscount);
        };
    }

    private String generateDiscountBoundary(Field field, Intensity intensity) {
        String priceJson = getPriceIdJson();
        
        return switch (field) {
            case PRICE -> switch (intensity) {
                case MILD -> "{\"price\": {\"id\": 0}, \"discount\": 10.0}";
                case MODERATE -> "{\"price\": {\"id\": " + Integer.MAX_VALUE + "}, \"discount\": 10.0}";
                case AGGRESSIVE -> "{\"price\": {\"id\": " + Long.MAX_VALUE + "}, \"discount\": 10.0}";
            };
            default -> switch (intensity) {
                case MILD -> "{\"price\": " + priceJson + ", \"discount\": 0}";
                case MODERATE -> "{\"price\": " + priceJson + ", \"discount\": 100}"; // 100% discount
                case AGGRESSIVE -> "{\"price\": " + priceJson + ", \"discount\": " + Double.MAX_VALUE + "}";
            };
        };
    }

    private String generateDiscountStructure(Field field) {
        String priceJson = getPriceIdJson();
        double discount = 15.0 + rng.nextDouble() * 20.0;
        
        return switch (field) {
            case PRICE -> "{\"discount\": " + discount + "}"; // Missing price
            case UNKNOWN -> "{\"price\": " + priceJson + ", \"discount\": " + discount + ", \"code\": \"SAVE10\", \"expires\": \"2025-12-31\"}";
            case ALL -> "{}"; // Empty object
            default -> {
                int variant = rng.nextInt(3);
                yield switch (variant) {
                    case 0 -> "{}";
                    case 1 -> "{\"discount\": " + discount + "}"; // Missing price
                    case 2 -> "{\"price\": " + priceJson + "}"; // Missing discount
                    default -> generateDiscountValid(field);
                };
            }
        };
    }

    private String generateDiscountInjection(Field field) {
        String priceJson = getPriceIdJson();
        String[] injections = {
            "'; DROP TABLE discounts; --",
            "1' OR '1'='1",
            "<script>alert('xss')</script>",
            "../../../etc/passwd"
        };
        String inject = injections[rng.nextInt(injections.length)];
        
        return switch (field) {
            case PRICE -> "{\"price\": {\"id\": \"" + inject + "\"}, \"discount\": 10.0}";
            default -> "{\"price\": {\"id\": \"" + inject + "\"}, \"discount\": \"" + inject + "\"}";
        };
    }

    private String generateDiscountTypeConfuse(Field field) {
        String priceJson = getPriceIdJson();
        
        return switch (field) {
            case PRICE -> "{\"price\": \"not an object\", \"discount\": 10.0}";
            case ALL -> "{\"price\": [1, 2, 3], \"discount\": {\"value\": 10}}";
            default -> {
                int variant = rng.nextInt(3);
                yield switch (variant) {
                    case 0 -> "{\"price\": " + priceJson + ", \"discount\": \"ten percent\"}";
                    case 1 -> "{\"price\": 12345, \"discount\": 10.0}";
                    case 2 -> "{\"price\": " + priceJson + ", \"discount\": [10, 20, 30]}";
                    default -> generateDiscountValid(field);
                };
            }
        };
    }
}

