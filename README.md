# BitNet LLM-as-a-Service
### Kubernetes Sidecar Architecture — Air-Gapped, CPU-Native, On-Prem

A production-ready deployment of Microsoft's 1.58-bit large language model on a Kubernetes cluster.
No GPU required. No internet dependency at runtime. Fully OpenAI-API-compatible.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Component 1 — BitNet.cpp AI Inference Sidecar](#3-component-1--bitnetcpp-ai-inference-sidecar)
4. [Component 2 — Spring Boot API](#4-component-2--spring-boot-api)
5. [Component 3 — Kubernetes Orchestration](#5-component-3--kubernetes-orchestration)
6. [Component 4 — Web UI (Open WebUI)](#6-component-4--web-ui-open-webui)
7. [Request Flow](#7-request-flow)
8. [Repository Structure](#8-repository-structure)
9. [Deployment Guide](#9-deployment-guide)
10. [Local Testing](#10-local-testing)
11. [Configuration Reference](#11-configuration-reference)

---

## 1. Overview

This project deploys Microsoft's [BitNet b1.58](https://github.com/microsoft/BitNet) inference
engine on a Kubernetes cluster using the **Sidecar pattern**. Both the C++ inference engine and
the Java API wrapper run in a single Pod, communicating over localhost. The architecture is
designed for:

- **Air-gapped environments** — no internet access required after initial model download
- **CPU-only inference** — 1.58-bit quantisation makes LLM inference viable on standard server CPUs
- **Enterprise integration** — OpenAI-compatible REST API, ready for internal tooling
- **Security** — the raw inference engine is never exposed to the cluster network

---

## 2. Architecture

### High-Level Cluster View

```
  Office Network
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  Browser  ──── http://<node-ip>:30080 ────────────────────────────────────▶ │
  └─────────────────────────────────────────────────────────────────────────────┘
                                          │
  ┌───────────────────────────────────────┼─────────────────────────────────────┐
  │           Kubernetes Cluster  (On-Prem / Air-Gapped)                        │
  │                                       │                                      │
  │           ┌───────────────────────────▼──────────────┐                      │
  │           │   NodePort Service : open-webui-service   │  port 30080          │
  │           └───────────────────────────┬──────────────┘                      │
  │                                       │                                      │
  │           ┌───────────────────────────▼──────────────┐                      │
  │           │   Pod: open-webui                         │                      │
  │           │   Open WebUI  |  port 8080                │                      │
  │           │   • Browser chat interface                │                      │
  │           │   • Chat history  (PVC: open-webui-pvc)   │                      │
  │           │   • User accounts                         │                      │
  │           └───────────────────────────┬──────────────┘                      │
  │                                       │                                      │
  │                  http://bitnet-llm-service/api/v1/chat/completions           │
  │                                       │                                      │
  │   In-cluster clients ─────────────────┤                                      │
  │   (other services, curl, scripts)     │                                      │
  │                                       ▼                                      │
  │           ┌──────────────────────────────────────┐                          │
  │           │   ClusterIP Service                   │  port 80 → 8081          │
  │           │   bitnet-llm-service                  │  (8080 not exposed)      │
  │           └──────────────────────┬───────────────┘                          │
  │                                  │                                           │
  │           ┌──────────────────────▼───────────────────────────────────┐      │
  │           │                  Pod: bitnet-llm                          │      │
  │           │                                                           │      │
  │           │   ┌─────────────────────────┐   localhost:8080           │      │
  │           │   │   Container 2           │ ────────────────────────▶  │      │
  │           │   │   Spring Boot API       │                             │      │
  │           │   │   Java 17  |  port 8081 │ ◀────────────────────────  │      │
  │           │   │                         │   OpenAI-compatible JSON   │      │
  │           │   │   • REST Controller     │                             │      │
  │           │   │   • Request Validation  │   ┌─────────────────────┐  │      │
  │           │   │   • HTTP Client         │   │   Container 1        │  │      │
  │           │   │   • Error Handling      │   │   BitNet.cpp Sidecar │  │      │
  │           │   └─────────────────────────┘   │   C++  |  port 8080  │  │      │
  │           │                                 │   127.0.0.1 only     │  │      │
  │           │                                 │   • llama-server     │  │      │
  │           │                                 │   • 1.58-bit kernels │  │      │
  │           │                                 │   • CPU-only, 6 cores│  │      │
  │           │                                 └──────────┬───────────┘  │      │
  │           │                                            │ /models       │      │
  │           │                                            ▼               │      │
  │           │                                 ┌──────────────────────┐   │      │
  │           │                                 │  PersistentVolume    │   │      │
  │           │                                 │  model weights       │   │      │
  │           │                                 │  (bitnet-model-pvc)  │   │      │
  │           │                                 └──────────────────────┘   │      │
  │           └──────────────────────────────────────────────────────────┘      │
  └─────────────────────────────────────────────────────────────────────────────┘
```

### Why the Sidecar Pattern?

Both containers share the **same Pod network namespace**. This means they communicate over
`localhost` without any network hop, DNS lookup, or cluster routing. The C++ engine is
effectively invisible to the rest of the cluster — only the Spring Boot API is reachable.

```
  Pod network namespace
  ┌────────────────────────────────────────────┐
  │                                            │
  │   Spring Boot  ──── localhost:8080 ──────▶ BitNet.cpp   │
  │   (port 8081)       zero-hop, no DNS       (port 8080)  │
  │                                            │
  └────────────────────────────────────────────┘
         ▲
         │  only this port is reachable from outside the Pod
```

---

## 3. Component 1 — BitNet.cpp AI Inference Sidecar

**Location:** `bitnet-sidecar/`

```
  bitnet-sidecar/
  ├── Dockerfile.bitnet     ← multi-stage container build
  ├── start.sh              ← entrypoint: model discovery + server launch
  └── download_model.sh     ← one-time admin script to populate the PV
```

### Dockerfile.bitnet — Multi-Stage Build

```
  ┌─────────────────────────────────────────────────────────┐
  │  Stage 1: builder  (ubuntu:22.04)                        │
  │                                                          │
  │   apt install: build-essential, cmake, git, python3      │
  │       │                                                  │
  │       ▼                                                  │
  │   git clone --recursive microsoft/BitNet                 │
  │       │  (includes llama.cpp as a submodule)             │
  │       ▼                                                  │
  │   cmake -B build -DLLAMA_NATIVE=ON                       │
  │   cmake --build build --config Release -j$(nproc)        │
  │       │                                                  │
  │       │  produces:                                       │
  │       │   build/bin/llama-server    ← HTTP server        │
  │       │   build/bin/llama-quantize  ← model converter    │
  └───────┼─────────────────────────────────────────────────┘
          │  COPY only compiled binaries + Python scripts
          ▼
  ┌─────────────────────────────────────────────────────────┐
  │  Stage 2: runtime  (ubuntu:22.04)                        │
  │                                                          │
  │   apt install: python3, libgomp1 (OpenMP runtime)        │
  │   No compiler. No cmake. No build cache.                 │
  │                                                          │
  │   /app/bin/llama-server                                  │
  │   /app/bin/llama-quantize                                │
  │   /app/setup_env.py  (model conversion)                  │
  │   /models            (VOLUME — mounted at runtime)       │
  └─────────────────────────────────────────────────────────┘
```

### start.sh — Entrypoint Logic

```
  start.sh
      │
      ├── Read env vars (MODEL_DIR, SERVER_HOST, SERVER_PORT, THREADS, CTX_SIZE, PARALLEL)
      │
      ├── Find .gguf file in $MODEL_DIR?
      │       │
      │       ├── YES ──▶ skip conversion, go straight to server launch
      │       │
      │       └── NO
      │               │
      │               ├── Find .safetensors file in $MODEL_DIR?
      │               │       │
      │               │       ├── YES ──▶ run setup_env.py (quantise to i2_s GGUF)
      │               │       │               │
      │               │       │               └──▶ find newly created .gguf
      │               │       │
      │               │       └── NO ──▶ exit 1 (no weights found)
      │               │
      │               └──▶ verify .gguf was produced
      │
      └── exec llama-server
              --model       $GGUF_FILE
              --host        127.0.0.1    ← Pod-internal only
              --port        8080
              --ctx-size    4096
              --threads     6
              --n-gpu-layers 0           ← CPU-only
              --parallel    2            ← 2 concurrent requests
```

### download_model.sh — Admin Setup Script

Run **once** on a machine with internet access before deploying to the cluster.

```bash
chmod +x bitnet-sidecar/download_model.sh

# Default: downloads microsoft/bitnet_b1_58-3B (~700 MB)
./bitnet-sidecar/download_model.sh /mnt/bitnet-models

# Or specify a larger model
./bitnet-sidecar/download_model.sh /mnt/bitnet-models microsoft/bitnet_b1_58-large
```

| Model | Size on disk | Notes |
|---|---|---|
| `microsoft/bitnet_b1_58-3B` | ~700 MB | Default — practical for CPU inference |
| `microsoft/bitnet_b1_58-large` | ~1.5 GB | Higher quality, slower on CPU |

---

## 4. Component 2 — Spring Boot API

**Location:** `spring-boot-api/`

```
  spring-boot-api/
  ├── Dockerfile
  ├── pom.xml
  ├── README.md
  └── src/main/
      ├── java/com/bitnet/api/
      │   ├── BitNetApiApplication.java
      │   ├── config/
      │   │   └── RestClientConfig.java      ← configures RestClient bean
      │   ├── controller/
      │   │   └── ChatController.java        ← POST /api/v1/chat/completions
      │   ├── client/
      │   │   └── BitNetInferenceClient.java ← calls localhost:8080
      │   └── dto/
      │       ├── ChatRequest.java           ← inbound payload (OpenAI schema)
      │       ├── ChatResponse.java          ← outbound payload
      │       ├── Message.java
      │       ├── Choice.java
      │       └── Usage.java
      └── resources/
          └── application.properties        ← port 8081, sidecar URL
```

### Class Responsibilities

```
  HTTP Request (JSON)
       │
       ▼
  ChatController
  POST /api/v1/chat/completions
       │
       │   @Valid @RequestBody ChatRequest
       │   validates: messages list not empty, role/content not blank
       │
       ▼
  BitNetInferenceClient
       │
       │   RestClient.post()
       │       .uri("/v1/chat/completions")
       │       .body(request)
       │       .retrieve()
       │       .body(ChatResponse.class)
       │
       │   connect timeout : 5s
       │   read timeout    : 120s  (LLM generation can be slow)
       │
       ▼
  llama-server on localhost:8080
       │
       ▼
  ChatResponse ──▶ ChatController ──▶ HTTP 200 + JSON body
       │
       error paths:
         ResourceAccessException  → HTTP 503 (sidecar not ready)
         HttpServerErrorException → HTTP 5xx (sidecar error, passed through)
         MethodArgumentNotValidException → HTTP 400 (bad request)
```

### Dockerfile — Multi-Stage Build

```
  ┌─────────────────────────────────────────────┐
  │  Stage 1: builder  (maven:3.9-eclipse-temurin-17)  │
  │                                             │
  │   COPY pom.xml                              │
  │   RUN mvn dependency:go-offline             │  ← cached layer
  │   COPY src/                                 │
  │   RUN mvn package -DskipTests               │
  └──────────────┬──────────────────────────────┘
                 │  COPY target/bitnet-api-*.jar
                 ▼
  ┌─────────────────────────────────────────────┐
  │  Stage 2: runtime  (eclipse-temurin:17-jre-jammy) │
  │                                             │
  │   Non-root user: bitnet                     │
  │   EXPOSE 8081                               │
  │   -XX:+UseContainerSupport                  │
  │   -XX:MaxRAMPercentage=75.0                 │
  └─────────────────────────────────────────────┘
```

---

## 5. Component 3 — Kubernetes Orchestration

**Location:** `k8s/`

```
  k8s/
  ├── pvc.yaml           ← PersistentVolumeClaim for model weights
  ├── deployment.yaml    ← Pod definition with both containers
  ├── service.yaml       ← ClusterIP exposing only port 8081
  └── open-webui.yaml    ← Open WebUI PVC + Deployment + NodePort Service
```

### pvc.yaml

```
  PersistentVolumeClaim: bitnet-model-pvc
  ┌──────────────────────────────────────────┐
  │  accessMode : ReadOnlyMany               │
  │  storage    : 10Gi                       │
  │  storageClass: <set to your on-prem SC>  │
  │  e.g. nfs-client, local-path, ceph-rbd   │
  └──────────────────────────────────────────┘
```

### deployment.yaml — Resource Allocation

```
  Pod: bitnet-llm
  ┌────────────────────────────────────────────────────────────┐
  │                                                            │
  │  Container: bitnet-sidecar          Container: spring-boot-api  │
  │  ┌──────────────────────────┐       ┌──────────────────┐  │
  │  │ CPU request : 4000m      │       │ CPU request: 250m│  │
  │  │ CPU limit   : 6000m      │       │ CPU limit  : 500m│  │
  │  │ Mem request : 6Gi        │       │ Mem request: 512Mi│ │
  │  │ Mem limit   : 10Gi       │       │ Mem limit  : 768Mi│ │
  │  │                          │       │                  │  │
  │  │ startupProbe             │       │ livenessProbe    │  │
  │  │   GET /health :8080      │       │   GET /actuator/ │  │
  │  │   30 attempts x 10s      │       │   health :8081   │  │
  │  │   = up to 5 min to load  │       │                  │  │
  │  │                          │       │ readinessProbe   │  │
  │  │ volumeMount: /models     │       │   GET /actuator/ │  │
  │  │   (readOnly: true)       │       │   health :8081   │  │
  │  └──────────────────────────┘       └──────────────────┘  │
  │                                                            │
  │  volume: model-weights → PVC: bitnet-model-pvc            │
  └────────────────────────────────────────────────────────────┘
```

### service.yaml

```
  Service: bitnet-llm-service  (ClusterIP)
  ┌──────────────────────────────────────┐
  │                                      │
  │  port 80  ──▶  targetPort 8081       │
  │                                      │
  │  port 8080 is NOT listed here.       │
  │  The C++ sidecar is invisible to     │
  │  the cluster network by design.      │
  │                                      │
  └──────────────────────────────────────┘
```

---

## 6. Component 4 — Web UI (Open WebUI)

**Location:** `k8s/open-webui.yaml`

Open WebUI is a browser-based chat interface that connects to the Spring Boot API as an
OpenAI-compatible backend. Users on the office network open it in a browser — no API knowledge
or curl commands required.

### What's inside open-webui.yaml

```
  open-webui.yaml contains three objects:
  ┌──────────────────────────────────────────────────────────┐
  │  1. PVC: open-webui-pvc  (2Gi, ReadWriteOnce)            │
  │     Stores SQLite chat history, user accounts, uploads   │
  ├──────────────────────────────────────────────────────────┤
  │  2. Deployment: open-webui                               │
  │     Image : ghcr.io/open-webui/open-webui:main           │
  │     Port  : 8080                                         │
  │                                                          │
  │     Key env vars:                                        │
  │       OPENAI_API_BASE_URLS                               │
  │         → http://bitnet-llm-service/api/v1               │
  │       DEFAULT_MODELS  → bitnet-b1.58                     │
  │       WEBUI_AUTH      → True  (user login enabled)       │
  │       WEBUI_SECRET_KEY → set before deploying            │
  │       ENABLE_OLLAMA_API → False                          │
  ├──────────────────────────────────────────────────────────┤
  │  3. Service: open-webui-service  (NodePort)              │
  │     port 8080  →  nodePort 30080                         │
  │     Accessible at http://<any-node-ip>:30080             │
  └──────────────────────────────────────────────────────────┘
```

### Resource Allocation

| Resource | Request | Limit |
|---|---|---|
| CPU | 250m | 500m |
| Memory | 512Mi | 1Gi |

### First-Time Login

When you open `http://<node-ip>:30080` for the first time, Open WebUI will prompt you to
create an admin account. The first account registered automatically becomes the admin.

---

## 7. Request Flow

```
  In-cluster client
  (another service, an ingress controller, a CLI tool)
       │
       │  POST http://bitnet-llm-service/api/v1/chat/completions
       │  Content-Type: application/json
       │  Body: { "model": "bitnet-b1.58",
       │          "messages": [{"role":"user","content":"..."}],
       │          "temperature": 0.7,
       │          "max_tokens": 200 }
       │
       ▼
  ┌─────────────────────────────────────────┐
  │  ClusterIP Service  (:80)               │
  │  Kubernetes load-balances to Pod :8081  │
  └──────────────────┬──────────────────────┘
                     │
                     ▼
  ┌─────────────────────────────────────────┐
  │  Spring Boot — ChatController           │
  │                                         │
  │  1. Deserialise JSON → ChatRequest      │
  │  2. Validate (@NotEmpty messages, etc.) │
  │     └── fail → HTTP 400                │
  │  3. Log request                         │
  └──────────────────┬──────────────────────┘
                     │
                     │  POST http://localhost:8080/v1/chat/completions
                     │  (same Pod, zero network hop)
                     │
                     ▼
  ┌─────────────────────────────────────────┐
  │  BitNet.cpp — llama-server              │
  │                                         │
  │  1. Tokenise input messages             │
  │  2. Run 1.58-bit LLM forward pass       │
  │     (CPU-only, 6 threads, no GPU)       │
  │  3. Detokenise output                   │
  │  4. Return OpenAI-compatible JSON       │
  └──────────────────┬──────────────────────┘
                     │
                     │  HTTP 200 + ChatResponse JSON
                     │
                     ▼
  ┌─────────────────────────────────────────┐
  │  Spring Boot — ChatController           │
  │                                         │
  │  error paths:                           │
  │    sidecar unreachable → HTTP 503       │
  │    sidecar 5xx         → pass through   │
  │  success:              → HTTP 200       │
  └──────────────────┬──────────────────────┘
                     │
                     ▼
  In-cluster client receives:
  {
    "id": "chatcmpl-...",
    "object": "chat.completion",
    "model": "bitnet-b1.58",
    "choices": [{
      "message": { "role": "assistant", "content": "..." },
      "finish_reason": "stop"
    }],
    "usage": { "prompt_tokens": 12, "completion_tokens": 48, "total_tokens": 60 }
  }
```

---

## 8. Repository Structure

```
  bitnet-llm-service/
  │
  ├── SOLUTION_DESIGN.md           ← original architecture design document
  ├── README.md                    ← this file
  ├── .gitignore
  │
  ├── bitnet-sidecar/              ── COMPONENT 1 ──
  │   ├── Dockerfile.bitnet        ← multi-stage C++ build
  │   ├── start.sh                 ← model discovery + server entrypoint
  │   └── download_model.sh        ← one-time admin model download script
  │
  ├── spring-boot-api/             ── COMPONENT 2 ──
  │   ├── Dockerfile               ← multi-stage Maven/JRE build
  │   ├── pom.xml                  ← Spring Boot 3.3.4, Java 17
  │   ├── README.md                ← local testing guide
  │   └── src/main/
  │       ├── java/com/bitnet/api/
  │       │   ├── BitNetApiApplication.java
  │       │   ├── config/RestClientConfig.java
  │       │   ├── controller/ChatController.java
  │       │   ├── client/BitNetInferenceClient.java
  │       │   └── dto/
  │       │       ├── ChatRequest.java
  │       │       ├── ChatResponse.java
  │       │       ├── Message.java
  │       │       ├── Choice.java
  │       │       └── Usage.java
  │       └── resources/application.properties
  │
  └── k8s/                         ── COMPONENTS 3 & 4 ──
      ├── pvc.yaml                 ← PersistentVolumeClaim (10Gi, ReadOnlyMany) for model
      ├── deployment.yaml          ← Pod with both containers + resource limits
      ├── service.yaml             ← ClusterIP, port 80 → 8081 only
      └── open-webui.yaml          ← Open WebUI PVC + Deployment + NodePort Service (:30080)
```

---

## 9. Deployment Guide

### Prerequisites

| Requirement | Notes |
|---|---|
| Kubernetes cluster | On-prem, any CNI |
| kubectl | Configured with cluster access |
| Docker | With access to a private registry |
| Internet access | Only needed once, for model download |
| Storage provisioner | NFS, Ceph, local-path, or equivalent |

---

### Step 1 — Download model weights (Linux admin, run once)

On a machine with internet access and the PersistentVolume directory mounted:

```bash
chmod +x bitnet-sidecar/download_model.sh
./bitnet-sidecar/download_model.sh /mnt/bitnet-models
```

This populates `/mnt/bitnet-models` with the `.safetensors` weight files from
`microsoft/bitnet_b1_58-3B` on Hugging Face.

---

### Step 2 — Build and push Docker images

```bash
# Build the BitNet.cpp sidecar
docker build \
  -f bitnet-sidecar/Dockerfile.bitnet \
  -t your-registry/bitnet-sidecar:latest \
  bitnet-sidecar/

# Build the Spring Boot API
docker build \
  -f spring-boot-api/Dockerfile \
  -t your-registry/bitnet-api:latest \
  spring-boot-api/

# Push both to your private registry
docker push your-registry/bitnet-sidecar:latest
docker push your-registry/bitnet-api:latest
```

---

### Step 3 — Update placeholders before deploying

In `k8s/deployment.yaml`, replace the two placeholder image names:

```yaml
# bitnet-sidecar container:
image: your-registry/bitnet-sidecar:latest

# spring-boot-api container:
image: your-registry/bitnet-api:latest
```

In `k8s/open-webui.yaml`, set a real secret key:

```yaml
# Generate with: openssl rand -hex 32
- name: WEBUI_SECRET_KEY
  value: "change-me-to-a-random-secret"
```

Also set `storageClassName` in both `k8s/pvc.yaml` and `k8s/open-webui.yaml` to match
your cluster's storage provisioner.

---

### Step 4 — Deploy to Kubernetes

```bash
# 1. Provision storage for model weights
kubectl apply -f k8s/pvc.yaml

# 2. Verify PVC is Bound before proceeding
kubectl get pvc bitnet-model-pvc

# 3. Deploy the LLM application
kubectl apply -f k8s/deployment.yaml

# 4. Expose the internal API service
kubectl apply -f k8s/service.yaml

# 5. Watch the Pod come up (sidecar takes 1–5 min to load model)
kubectl get pods -l app=bitnet-llm -w
```

---

### Step 5 — Deploy the Web UI

```bash
# Deploy Open WebUI (PVC + Deployment + NodePort Service in one file)
kubectl apply -f k8s/open-webui.yaml

# Watch it come up
kubectl get pods -l app=open-webui -w
```

Once running, open a browser and navigate to:
```
http://<any-kubernetes-node-ip>:30080
```

Create your admin account on first visit. The model `bitnet-b1.58` will be pre-selected.

---

### Step 6 — Verify

```bash
# Check all Pods are Running
kubectl get pods -l 'app in (bitnet-llm, open-webui)'

# API smoke test from inside the cluster
kubectl run curl-test --image=curlimages/curl --rm -it --restart=Never -- \
  curl -s -X POST http://bitnet-llm-service/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"bitnet-b1.58","messages":[{"role":"user","content":"Hello!"}]}'
```

---

## 10. Local Testing

See [spring-boot-api/README.md](spring-boot-api/README.md) for the full local testing guide
using a Python mock sidecar. Summary:

```bash
# Terminal 1 — mock the C++ sidecar on port 8080
python3 -c "
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'id':'mock','object':'chat.completion','created':0,'model':'bitnet-b1.58','choices':[{'index':0,'message':{'role':'assistant','content':'mock response'},'finish_reason':'stop'}],'usage':{'prompt_tokens':5,'completion_tokens':3,'total_tokens':8}}).encode())
    def log_message(self,f,*a): pass
HTTPServer(('localhost',8080),H).serve_forever()
"

# Terminal 2 — start Spring Boot
cd spring-boot-api && mvn spring-boot:run

# Terminal 3 — test
curl -s -X POST http://localhost:8081/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"bitnet-b1.58","messages":[{"role":"user","content":"Hello!"}]}' \
  | python3 -m json.tool
```

---

## 11. Configuration Reference

### BitNet Sidecar — Environment Variables

| Variable | Default | Description |
|---|---|---|
| `MODEL_DIR` | `/models` | Path to directory containing model weights |
| `SERVER_HOST` | `127.0.0.1` | Bind address (never change in production) |
| `SERVER_PORT` | `8080` | Port for the inference server |
| `THREADS` | `$(nproc)` | CPU threads allocated to inference |
| `CTX_SIZE` | `4096` | Context window size in tokens |
| `PARALLEL` | `2` | Max concurrent inference requests |

### Spring Boot API — application.properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` | Port the API listens on |
| `bitnet.sidecar.url` | `http://localhost:8080` | Base URL of the C++ sidecar |

### Kubernetes Resources

| Container | CPU Request | CPU Limit | Memory Limit |
|---|---|---|---|
| `bitnet-sidecar` | 4 cores | 6 cores | 10 Gi |
| `spring-boot-api` | 0.25 cores | 0.5 cores | 768 Mi |
| `open-webui` | 0.25 cores | 0.5 cores | 1 Gi |

### Open WebUI — Environment Variables

| Variable | Default | Description |
|---|---|---|
| `OPENAI_API_BASE_URLS` | `http://bitnet-llm-service/api/v1` | Spring Boot API endpoint |
| `OPENAI_API_KEYS` | `not-required` | Dummy value — API has no auth |
| `DEFAULT_MODELS` | `bitnet-b1.58` | Pre-selected model in the UI |
| `WEBUI_AUTH` | `True` | User login enabled |
| `WEBUI_SECRET_KEY` | *(must set)* | Session signing key — use `openssl rand -hex 32` |
| `ENABLE_OLLAMA_API` | `False` | Ollama integration disabled |
