#!/usr/bin/env bash
# download_model.sh — One-time script run by a Linux admin to download
# Microsoft BitNet model weights from Hugging Face into a target directory
# (which should be backed by a Kubernetes PersistentVolume).
#
# Usage:
#   ./download_model.sh <target-directory> [model-id]
#
# Examples:
#   ./download_model.sh /mnt/bitnet-models
#   ./download_model.sh /mnt/bitnet-models microsoft/bitnet_b1_58-3B
#
# Available Microsoft BitNet models on Hugging Face:
#   microsoft/bitnet_b1_58-large   — large variant  (~1.5 GB)
#   microsoft/bitnet_b1_58-3B      — 3B parameters  (~700 MB) ← default
#
# Prerequisites:
#   python3 and pip3 must be installed on this machine.
#   The target directory must already exist and be writable.
#   If the model is gated, log in first: huggingface-cli login

set -euo pipefail

# ── Arguments ────────────────────────────────────────────────────────────────

TARGET_DIR="${1:-}"
MODEL_ID="${2:-microsoft/bitnet_b1_58-3B}"

if [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <target-directory> [model-id]"
    echo ""
    echo "Example:"
    echo "  $0 /mnt/bitnet-models"
    echo "  $0 /mnt/bitnet-models microsoft/bitnet_b1_58-large"
    exit 1
fi

# ── Preflight checks ─────────────────────────────────────────────────────────

echo "[download] Target directory : $TARGET_DIR"
echo "[download] Model            : $MODEL_ID"
echo ""

if [ ! -d "$TARGET_DIR" ]; then
    echo "[download] ERROR: Directory '$TARGET_DIR' does not exist."
    echo "[download]        Create it (and mount your PV there) before running this script."
    exit 1
fi

if [ ! -w "$TARGET_DIR" ]; then
    echo "[download] ERROR: Directory '$TARGET_DIR' is not writable by the current user."
    exit 1
fi

# Ensure huggingface_hub is available.
if ! command -v huggingface-cli &>/dev/null; then
    echo "[download] huggingface-cli not found — installing huggingface_hub..."
    pip3 install --quiet huggingface_hub
fi

# ── Disk space check ─────────────────────────────────────────────────────────

AVAILABLE_KB=$(df -k "$TARGET_DIR" | awk 'NR==2 {print $4}')
REQUIRED_KB=$((3 * 1024 * 1024))   # 3 GB — safe margin for any model variant

if [ "$AVAILABLE_KB" -lt "$REQUIRED_KB" ]; then
    AVAILABLE_GB=$(( AVAILABLE_KB / 1024 / 1024 ))
    echo "[download] WARNING: Only ~${AVAILABLE_GB} GB available in $TARGET_DIR."
    echo "[download]          At least 3 GB is recommended. Proceeding anyway..."
fi

# ── Download ─────────────────────────────────────────────────────────────────

echo "[download] Starting download — this may take several minutes..."
echo ""

huggingface-cli download "$MODEL_ID" \
    --local-dir "$TARGET_DIR" \
    --local-dir-use-symlinks False

# ── Verify ───────────────────────────────────────────────────────────────────

echo ""
echo "[download] Verifying downloaded files..."

ST_COUNT=$(find "$TARGET_DIR" -name "*.safetensors" | wc -l)
GGUF_COUNT=$(find "$TARGET_DIR" -name "*.gguf" | wc -l)

if [ "$ST_COUNT" -eq 0 ] && [ "$GGUF_COUNT" -eq 0 ]; then
    echo "[download] ERROR: No .safetensors or .gguf files found after download."
    echo "[download]        The download may have failed or the model repo has an unexpected layout."
    exit 1
fi

echo "[download] Found $ST_COUNT .safetensors file(s) and $GGUF_COUNT .gguf file(s)."
echo ""
echo "[download] Done. Contents of $TARGET_DIR:"
ls -lh "$TARGET_DIR"
echo ""
echo "[download] Next steps:"
echo "  1. Ensure your Kubernetes PersistentVolume is bound to this directory."
echo "  2. Deploy the app:  kubectl apply -f k8s/"
