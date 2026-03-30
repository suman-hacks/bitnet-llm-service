#!/usr/bin/env bash
# start.sh — Entrypoint for the BitNet.cpp inference sidecar.
#
# Behaviour:
#   1. Look for a pre-converted .gguf model file in MODEL_DIR.
#   2. If none found, look for .safetensors weights and run BitNet's
#      setup_env.py to quantise them to the i2_s GGUF format.
#   3. Launch llama-server bound to 127.0.0.1:8080 (Pod-internal only).
#
# Environment variables (all optional — defaults shown):
#   MODEL_DIR    Path to the directory containing model weights.  [/models]
#   SERVER_HOST  Address to bind the HTTP server to.              [127.0.0.1]
#   SERVER_PORT  Port for the HTTP server.                        [8080]
#   CTX_SIZE     Context window size in tokens.                   [2048]
#   THREADS      Number of CPU threads for inference.             [$(nproc)]

set -euo pipefail

MODEL_DIR="${MODEL_DIR:-/models}"
SERVER_HOST="${SERVER_HOST:-127.0.0.1}"
SERVER_PORT="${SERVER_PORT:-8080}"
CTX_SIZE="${CTX_SIZE:-2048}"
THREADS="${THREADS:-$(nproc)}"

echo "[sidecar] Starting BitNet inference sidecar..."
echo "[sidecar] Model directory : $MODEL_DIR"
echo "[sidecar] Bind address     : $SERVER_HOST:$SERVER_PORT"
echo "[sidecar] Context size     : $CTX_SIZE tokens"
echo "[sidecar] CPU threads      : $THREADS"

# ── Step 1: Locate model file ─────────────────────────────────────────────────

GGUF_FILE=$(find "$MODEL_DIR" -maxdepth 2 -name "*.gguf" | head -1)

if [ -n "$GGUF_FILE" ]; then
    echo "[sidecar] Found pre-converted model: $GGUF_FILE"
else
    # No GGUF found — attempt conversion from .safetensors (HuggingFace format).
    echo "[sidecar] No .gguf file found. Scanning for .safetensors weights..."

    ST_FILE=$(find "$MODEL_DIR" -maxdepth 2 -name "*.safetensors" | head -1)

    if [ -z "$ST_FILE" ]; then
        echo "[sidecar] ERROR: No model weights found in $MODEL_DIR."
        echo "[sidecar]        Mount a volume containing either a .gguf or .safetensors file."
        exit 1
    fi

    echo "[sidecar] Found weights: $ST_FILE"
    echo "[sidecar] Running BitNet quantisation (i2_s format) — this may take several minutes..."

    # setup_env.py converts the HuggingFace .safetensors weights to a BitNet-
    # optimised GGUF file and places it alongside the source weights.
    python3 setup_env.py \
        --model-dir "$MODEL_DIR" \
        --quant-type i2_s \
        --prebuilt-model

    GGUF_FILE=$(find "$MODEL_DIR" -maxdepth 2 -name "*.gguf" | head -1)

    if [ -z "$GGUF_FILE" ]; then
        echo "[sidecar] ERROR: Quantisation completed but no .gguf file was produced."
        exit 1
    fi

    echo "[sidecar] Quantisation complete: $GGUF_FILE"
fi

# ── Step 2: Launch the HTTP inference server ──────────────────────────────────

echo "[sidecar] Launching llama-server..."

exec ./bin/llama-server \
    --model        "$GGUF_FILE" \
    --host         "$SERVER_HOST" \
    --port         "$SERVER_PORT" \
    --ctx-size     "$CTX_SIZE" \
    --threads      "$THREADS" \
    --n-gpu-layers 0 \
    --parallel     1
    # --n-gpu-layers 0  : CPU-only inference (no CUDA/Metal)
    # --parallel     1  : one concurrent request at a time (CPU budget)
