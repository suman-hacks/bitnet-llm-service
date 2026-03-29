package com.bitnet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Represents a single message in a chat conversation (OpenAI-compatible schema).
 */
public record Message(
        @NotBlank
        @JsonProperty("role") String role,

        @NotBlank
        @JsonProperty("content") String content
) {}
