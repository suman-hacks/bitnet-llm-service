package com.bitnet.api.controller;

import com.bitnet.api.client.BitNetInferenceClient;
import com.bitnet.api.dto.ChatRequest;
import com.bitnet.api.dto.ChatResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Cluster-facing REST controller that exposes the BitNet LLM on port 8081.
 *
 * Responsibilities:
 *   1. Accept and validate inbound chat-completion requests from in-cluster clients.
 *   2. Delegate inference to the C++ sidecar via {@link BitNetInferenceClient}.
 *   3. Return the OpenAI-compatible response or a structured error.
 *
 * The raw BitNet.cpp server (port 8080) is never exposed outside the Pod.
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final BitNetInferenceClient inferenceClient;

    public ChatController(BitNetInferenceClient inferenceClient) {
        this.inferenceClient = inferenceClient;
    }

    /**
     * POST /api/v1/chat/completions
     *
     * Accepts an OpenAI-compatible chat-completion request, forwards it to the
     * BitNet.cpp sidecar on localhost:8080, and returns the generated response.
     */
    @PostMapping(
            path = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ChatResponse> chatCompletions(
            @Valid @RequestBody ChatRequest request) {

        log.info("Received chat/completions request: model={}, messages={}",
                request.model(), request.messages().size());

        try {
            ChatResponse response = inferenceClient.complete(request);
            return ResponseEntity.ok(response);

        } catch (ResourceAccessException e) {
            // Sidecar is not yet ready (still loading model weights) or crashed.
            log.error("BitNet sidecar unreachable: {}", e.getMessage());
            return ResponseEntity.status(503).build();

        } catch (HttpServerErrorException e) {
            // Sidecar returned a 5xx — surface the same status upstream.
            log.error("BitNet sidecar returned error: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).build();
        }
    }
}
