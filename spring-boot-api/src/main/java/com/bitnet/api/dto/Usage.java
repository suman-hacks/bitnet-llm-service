package com.bitnet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics returned by the inference engine.
 */
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
) {}
