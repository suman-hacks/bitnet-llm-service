# Solution Design: BitNet LLM-as-a-Service (K8s Sidecar Architecture)

## Overview
This architecture defines an air-gapped, on-prem Kubernetes deployment of a 1.58-bit LLM. It utilizes the Kubernetes Sidecar pattern, deploying Microsoft's `bitnet.cpp` inference engine alongside a Spring Boot 3 microservice within a single, cohesive Pod. This provides a secure, OpenAI-compatible REST API powered entirely by CPU-native inference.

## Component 1: The AI Inference Sidecar (C++ Engine)
**Goal:** Build a lightweight container to host the Microsoft BitNet engine.
* **Base Image:** `debian:bookworm-slim` or `ubuntu:22.04`.
* **Build Steps:** * Install `build-essential`, `cmake`, `python3`, and `git`.
    * Clone the `microsoft/BitNet` repository.
    * Compile the `bitnet.cpp` engine according to the repository's CPU optimization instructions.
* **Execution:** Run the compiled server binary, pointing it to a local directory containing the 1.58-bit `.safetensors` model weights. Bind the server to `127.0.0.1:8080`.

## Component 2: The Spring Boot API (Java Microservice)
**Goal:** Build the enterprise Java wrapper to handle business logic, payload validation, and cluster-facing routing.
* **Framework:** Spring Boot 3 with Java 17 or 21.
* **API Layer:** Expose a standard POST endpoint (e.g., `/api/v1/chat/completions`) on port `8081`. 
* **Routing Logic:** When a request hits the endpoint, the Spring Boot application makes a synchronous internal HTTP call to `http://localhost:8080` (the C++ sidecar sharing the Pod's network namespace).
* **Dockerfile:** Use a multi-stage Docker build (Maven/Gradle) to package the `.jar` into a minimal `eclipse-temurin` JRE base image.

## Component 3: Kubernetes Orchestration (YAML)
**Goal:** Define the deployment topology for the on-prem cluster.
* **Deployment (`deployment.yaml`):**
    * Define a single Pod template containing both the AI Sidecar container and the Spring Boot container.
    * **Resource Constraints:** Apply explicit CPU and Memory `requests` and `limits` to both containers independently (allocating the bulk of CPU threads to the BitNet sidecar).
* **Service (`service.yaml`):**
    * Create a `ClusterIP` service that routes traffic ONLY to port `8081` (Spring Boot). The `8080` C++ port remains isolated and inaccessible from outside the Pod.