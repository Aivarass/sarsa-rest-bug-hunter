package com.example.sarsa.strategy;

/**
 * Target endpoint for API calls.
 */
public enum HttpType {
    NONE,       // No endpoint selected
    GET,        // GET single item
    GET_ALL,    // GET all items
    POST,       // Create new item
    PUT,        // Full update
    PATCH,      // Partial update
    DELETE      // Delete item
}

