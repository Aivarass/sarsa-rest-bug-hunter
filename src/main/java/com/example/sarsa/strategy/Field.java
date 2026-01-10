package com.example.sarsa.strategy;

/**
 * Target field for mutation strategies.
 */
public enum Field {
    NONE,           // No specific field (use default)
    NAME,           // Target "name" field
    QUANTITY,       // Target "quantity" field
    DESCRIPTION,    // Target "description" field
    ALL,            // Apply to all fields
    UNKNOWN         // Add unknown/extra fields
}

