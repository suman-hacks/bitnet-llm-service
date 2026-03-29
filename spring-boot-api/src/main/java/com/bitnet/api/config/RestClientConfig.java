package com.bitnet.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class RestClientConfig {

    /**
     * Base URL of the BitNet.cpp sidecar — always localhost inside the Pod.
     * Overridable via application.properties or an env var for local dev.
     */
    @Value("${bitnet.sidecar.url:http://localhost:8080}")
    private String sidecarUrl;

    @Bean
    public RestClient bitnetRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Give the LLM reasonable time to generate a response before we time out.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(120));

        return builder
                .baseUrl(sidecarUrl)
                .requestFactory(factory)
                .build();
    }
}
