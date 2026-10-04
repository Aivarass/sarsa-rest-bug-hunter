package com.example.harness;

/** A server response: status plus raw body. Shared by every arm. */
public record HttpResult(int status, String body) {}
