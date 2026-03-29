package com.bitnet.api.client;

import com.bitnet.api.dto.ChatRequest;
import com.bitnet.api.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous HTTP client that forwards chat-completion requests to the
 * BitNet.cpp inference engine running on localhost:8080 inside the same Pod.
 *
 * The sidecar exposes an OpenAI-compatible /v1/chat/completions endpoint,
 * so the payload can be forwarded as-is without any transformation.
 */
@Component
public class BitNetInferenceClient {

    private static final Logger log = LoggerFactory.getLogger(BitNetInferenceClient.class);

    // Path exposed by the llama-server / BitNet.cpp HTTP layer.
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private final RestClient restClient;

    public BitNetInferenceClient(RestClient bitnetRestClient) {
        this.restClient = bitnetRestClient;
    }

    /**
     * Sends the chat request to the sidecar and returns its response.
     *
     * @param request the validated inbound chat request
     * @return the ChatResponse produced by the BitNet.cpp engine
     * @throws RestClientException if the sidecar is unreachable or returns an error
     */
    public ChatResponse complete(ChatRequest request) {
        log.debug("Forwarding chat request to sidecar: model={}, messages={}",
                request.model(), request.messages().size());

        ChatResponse response = restClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        log.debug("Received response from sidecar: id={}", response != null ? response.id() : "null");
        return response;
    }
}
