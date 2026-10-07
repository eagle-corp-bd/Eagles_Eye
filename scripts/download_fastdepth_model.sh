#!/bin/bash
# Download FastDepth TFLite model for EaglesEye Studio Portrait
# Run from project root: bash scripts/download_fastdepth_model.sh

set -e

OUTPUT_DIR="app/src/main/assets"
OUTPUT_FILE="${OUTPUT_DIR}/fastdepth_256x256.tflite"

echo "=== FastDepth Model Download ==="
echo "Target: ${OUTPUT_FILE}"

mkdir -p "${OUTPUT_DIR}"

# Try GitHub media URL (bypasses LFS, serves actual binary)
MEDIA_URL="https://media.githubusercontent.com/media/STMicroelectronics/stm32ai-modelzoo/main/depth_estimation/fastdepth/Public_pretrainedmodel_public_dataset/nyu_depthv2/fastdepth_256/fastdepth_256_int8.tflite"

if command -v wget &>/dev/null; then
  wget -O "${OUTPUT_FILE}" "${MEDIA_URL}" && echo "Downloaded!" && ls -lh "${OUTPUT_FILE}" && exit 0
elif command -v curl &>/dev/null; then
  curl -L -o "${OUTPUT_FILE}" "${MEDIA_URL}" && echo "Downloaded!" && ls -lh "${OUTPUT_FILE}" && exit 0
else
  echo "Install wget or curl and try again."
  exit 1
fi

# Fallback: GitHub raw (LFS pointer check + retry)
echo "Trying GitHub raw..."
RAW_URL="https://raw.githubusercontent.com/STMicroelectronics/stm32ai-modelzoo/main/depth_estimation/fastdepth/Public_pretrainedmodel_public_dataset/nyu_depthv2/fastdepth_256/fastdepth_256_int8.tflite"
if command -v wget &>/dev/null; then
  wget -O "${OUTPUT_FILE}" "${RAW_URL}"
elif command -v curl &>/dev/null; then
  curl -L -o "${OUTPUT_FILE}" "${RAW_URL}"
fi

if head -c 100 "${OUTPUT_FILE}" 2>/dev/null | grep -q "git-lfs"; then
  echo "Got LFS pointer. Need git lfs..."
  if command -v git-lfs &>/dev/null; then
    GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 \
      https://github.com/STMicroelectronics/stm32ai-modelzoo.git /tmp/stm_mz 2>/dev/null
    cd /tmp/stm_mz
    git lfs pull --include "depth_estimation/fastdepth/Public_pretrainedmodel_public_dataset/nyu_depthv2/fastdepth_256/fastdepth_256_int8.tflite"
    cp depth_estimation/fastdepth/Public_pretrainedmodel_public_dataset/nyu_depthv2/fastdepth_256/fastdepth_256_int8.tflite "${OLDPWD}/${OUTPUT_FILE}"
    cd "${OLDPWD}"
    rm -rf /tmp/stm_mz
    echo "Downloaded via Git LFS!"
    ls -lh "${OUTPUT_FILE}"
    exit 0
  fi
fi

echo "FAILED. Try manually:"
echo "  1. Go to: https://github.com/STMicroelectronics/stm32ai-modelzoo"
echo "     tree/main/depth_estimation/fastdepth/Public_pretrainedmodel"
echo "     _public_dataset/nyu_depthv2/fastdepth_256/"
echo "  2. Click 'fastdepth_256_int8.tflite' → 'Download raw file'"
echo "  3. Save as: ${OUTPUT_FILE}"
echo ""
echo "Or convert from PINTO ONNX:"
echo "  bash scripts/convert_onnx_to_tflite.sh"
