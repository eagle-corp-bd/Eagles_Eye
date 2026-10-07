#!/bin/bash
# Extract resources.tar.gz and convert fast_depth_256x256.onnx → TFLite
# Run from project root: bash scripts/convert_onnx_to_tflite.sh

set -e

echo "Step 1: Extracting..."
tar -xzf resources.tar.gz

echo "Step 2: Finding 256x256 ONNX model..."
MODEL=$(find . -name "fast_depth_256x256.onnx" -o -name "fast_depth_256.onnx" 2>/dev/null | head -1)
if [ -z "$MODEL" ]; then
  echo "Looking for any onnx file..."
  find . -name "*.onnx" -type f
  echo "Enter the path to the 256x256 onnx file:"
  read MODEL
fi
echo "Found: $MODEL"

echo "Step 3: Installing onnx2tf..."
pip install onnx2tf onnx onnxruntime tensorflow 2>&1 | tail -5

echo "Step 4: Converting to TFLite..."
onnx2tf -i "$MODEL" -o converted_output/

echo "Step 5: Placing in assets..."
cp converted_output/fast_depth_256x256.tflite app/src/main/assets/fastdepth_256x256.tflite 2>/dev/null || \
cp converted_output/*.tflite app/src/main/assets/fastdepth_256x256.tflite 2>/dev/null || \
echo "TFLite file not found in expected location. Check converted_output/"

echo "Done! File at app/src/main/assets/fastdepth_256x256.tflite"
