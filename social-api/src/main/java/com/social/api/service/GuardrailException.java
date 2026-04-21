package com.social.api.service;

/**
 * Thrown when a Redis guardrail rejects a bot interaction.
 * Maps to HTTP 429 Too Many Requests.
 */
public class GuardrailException extends RuntimeException {
    public GuardrailException(String message) {
        super(message);
    }
}
