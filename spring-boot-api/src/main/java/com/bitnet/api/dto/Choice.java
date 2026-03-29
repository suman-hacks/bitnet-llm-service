package com.bitnet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single completion choice returned by the inference engine.
 */
public record Choice(
        @JsonProperty("index") int index,
        @JsonProperty("message") Message message,
        @JsonProperty("finish_reason") String finishReason
) {}
