package com.bitnet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Inbound request body for POST /api/v1/chat/completions.
 * Schema mirrors the OpenAI Chat Completions API so existing clients need no changes.
 */
public record ChatRequest(
        @JsonProperty("model") String model,

        @Valid
        @NotEmpty
        @JsonProperty("messages") List<Message> messages,

        @JsonProperty("temperature") Double temperature,

        @JsonProperty("max_tokens") Integer maxTokens,

        @JsonProperty("stream") Boolean stream
) {}
