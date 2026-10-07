#!/bin/bash
# Convert ZipDepth PyTorch → ONNX → TFLite for Android (base variant, GPU path)
# Run this on a machine with Python 3.9+, PyTorch 2.4+, CUDA (optional)

set -e

MODEL_DIR="$(dirname "$0")/../app/src/main/assets"
CHECKPOINT_URL="https://github.com/fabiotosi92/ZipDepth/raw/main/checkpoints/zipdepth_base.pth"
ZIPDEPTH_REPO="https://github.com/fabiotosi92/ZipDepth.git"

echo "=== Step 1: Clone ZipDepth repo ==="
git clone --depth 1 "$ZIPDEPTH_REPO" /tmp/zipdepth
cd /tmp/zipdepth

echo "=== Step 2: Install dependencies ==="
pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
pip install onnx onnx2tf

echo "=== Step 3: Download checkpoint ==="
wget "$CHECKPOINT_URL" -O checkpoints/zipdepth_base_npu.pth

echo "=== Step 4: Export to ONNX ==="
python scripts/export.py \
    --ckpt checkpoints/zipdepth_base.pth \
    --format onnx --variant base \
    --height 384 --width 384

echo "=== Step 5: Convert ONNX → TFLite ==="
# Using onnx2tf (install with: pip install onnx2tf)
onnx2tf \
    -i checkpoints/zipdepth_base_384x384.onnx \
    -o /tmp/zipdepth_tflite \
    -oiqt \
    -qt per-tensor

# Move TFLite model to assets
cp /tmp/zipdepth_tflite/zipdepth_base_384x384_float32.tflite "$MODEL_DIR/zipdepth_384x384.tflite"

echo "=== Done! ==="
echo "Model saved to: $MODEL_DIR/zipdepth_384x384.tflite"
echo "Size: $(ls -lh "$MODEL_DIR/zipdepth_384x384.tflite" | awk '{print $5}')"
