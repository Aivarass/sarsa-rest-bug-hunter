package com.example.sarsa.strategy;

/**
 * Mutation strategy for payload generation.
 */
public enum Strategy {
    NONE,               // No strategy (use default)
    VALID,              // Generate valid payload
    NULL_INJECT,        // Inject null values
    NEGATIVE,           // Negative numbers, invalid values
    BOUNDARY,           // Edge cases (empty, huge, MAX_INT)
    STRUCTURE,          // Missing/extra fields, wrong types
    INJECTION,          // SQL, XSS, path traversal
    TYPE_CONFUSE,       // Wrong types (string where int expected)
    ENCODING            // Unicode, special characters
}

