package com.bitnet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Outbound response body for POST /api/v1/chat/completions.
 * Passes through the OpenAI-compatible response produced by the BitNet.cpp sidecar.
 */
public record ChatResponse(
        @JsonProperty("id") String id,
        @JsonProperty("object") String object,
        @JsonProperty("created") long created,
        @JsonProperty("model") String model,
        @JsonProperty("choices") List<Choice> choices,
        @JsonProperty("usage") Usage usage
) {}
