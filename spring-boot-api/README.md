# BitNet Spring Boot API — Local Testing Guide

## Prerequisites
- Java 17+ (JDK 24 works fine — Maven compiles to Java 17 bytecode)
- Maven 3.9+
- Python 3 (for the mock sidecar)

---

## Running Locally (3 terminal tabs)

### Terminal 1 — Mock Sidecar
Simulates the BitNet.cpp C++ inference engine on port 8080.

```bash
python3 -c "
from http.server import HTTPServer, BaseHTTPRequestHandler
import json

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({
            'id': 'chatcmpl-mock-001',
            'object': 'chat.completion',
            'created': 1234567890,
            'model': 'bitnet-b1.58',
            'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': 'Hello from mock BitNet sidecar!'}, 'finish_reason': 'stop'}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 12, 'total_tokens': 22}
        }).encode())
    def log_message(self, fmt, *args): print(f'[sidecar] {args[0]}')

HTTPServer(('localhost', 8080), Handler).serve_forever()
"
```

### Terminal 2 — Start the Spring Boot App

```bash
cd /Users/suman/BitNet/spring-boot-api
mvn spring-boot:run
```

Wait for `Started BitNetApiApplication` in the logs before running any tests.

### Terminal 3 — Tests

**Health check** (equivalent to the Kubernetes liveness probe):
```bash
curl http://localhost:8081/actuator/health
```

**Chat completions request:**
```bash
curl -s -X POST http://localhost:8081/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"bitnet-b1.58","messages":[{"role":"user","content":"Hello!"}]}' \
  | python3 -m json.tool
```

**Validation error test** (empty messages — expects HTTP 400):
```bash
curl -s -X POST http://localhost:8081/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"bitnet-b1.58","messages":[]}' \
  | python3 -m json.tool
```

---

## Real Query Examples

The mock sidecar always returns a hardcoded response — that is fine. These queries verify the full request pipeline: validation, routing to `localhost:8080`, and response serialization.

**Simple question:**
```bash
curl -s -X POST http://localhost:8081/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "bitnet-b1.58",
    "messages": [
      {"role": "user", "content": "What is the capital of France?"}
    ],
    "max_tokens": 100,
    "temperature": 0.7
  }' | python3 -m json.tool
```

**Multi-turn conversation** (system prompt + user message):
```bash
curl -s -X POST http://localhost:8081/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "bitnet-b1.58",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant that answers concisely."},
      {"role": "user", "content": "Explain Kubernetes in one sentence."}
    ],
    "max_tokens": 150,
    "temperature": 0.3
  }' | python3 -m json.tool
```

**Validation failure — missing messages** (expects HTTP 400):
```bash
curl -v -X POST http://localhost:8081/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "bitnet-b1.58", "messages": []}'
```

**Sidecar down test** — stop Terminal 1 (the Python mock), then send any request. Expects HTTP 503 Service Unavailable.
