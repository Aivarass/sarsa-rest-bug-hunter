package com.example.sarsa.strategy;

/**
 * Target field for mutation strategies.
 * 
 * ITEMS endpoint: name, description, quantity
 * PRICES endpoint: price, itemId
 */
public enum Field {
    NONE,           // No specific field (use default)
    NAME,           // Target "name" field (ITEMS)
    QUANTITY,       // Target "quantity" field (ITEMS)
    DESCRIPTION,    // Target "description" field (ITEMS)
    PRICE,          // Target "price" field (PRICES)
    ITEM_ID,        // Target "itemId" field (PRICES) - needs valid item!
    DISCOUNT_ID,
    DISCOUNT,
    POINTS_ID,
    POINTS,
    ALL,            // Apply to all fields
    UNKNOWN         // Add unknown/extra fields
}

