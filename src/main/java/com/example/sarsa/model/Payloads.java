package com.example.sarsa.model;

public enum Payloads {

    POST_1("{\"name\": \"apple\", \"description\": \"fruit\", \"quantity\": 1}"),
    POST_2("{\"name\": \"\", \"description\": \"missing name\", \"quantity\": 1}"), // invalid: empty name
    POST_3("{\"name\": \"banana\", \"quantity\": -5}"),  // invalid: negative qty
    POST_4("{\"description\": \"no name field\", \"quantity\": 1}"), // invalid: missing name
    POST_5("{}"),  // invalid: empty body

    PUT_1("{\"name\": \"updated\", \"description\": \"changed\", \"quantity\": 10}"),
    PUT_2("{\"name\": \"\", \"description\": \"empty name\", \"quantity\": 1}"),
    PUT_3("{\"quantity\": 5}"), // partial (missing fields)
    PUT_4("{\"name\": \"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}"),  // edge case: huge name
    PUT_5("not json at all"), // invalid JSON

    PATCH_1("{\"quantity\": 99}"),
    PATCH_2("{\"name\": \"patched\"}"),
    PATCH_3("{\"unknownField\": \"test\"}"), // unknown field
    PATCH_4("{\"quantity\": null}"), // null value
    PATCH_5("{\"name\": \"\"}"); // empty string

    private final String json;

    Payloads(String json) {   // ← matches enum name
        this.json = json;
    }

    public String getJson() {
        return json;
    }
}