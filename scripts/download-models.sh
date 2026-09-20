#!/bin/sh
set -eu

MODEL_DIR="android/app/src/main/assets/models"
MODEL_PATH="$MODEL_DIR/pose_landmarker_lite.task"
MODEL_URL="https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
MODEL_SHA256="59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"

mkdir -p "$MODEL_DIR"
if [ ! -f "$MODEL_PATH" ]; then
    curl --fail --location --retry 3 --output "$MODEL_PATH" "$MODEL_URL"
fi

echo "$MODEL_SHA256  $MODEL_PATH" | sha256sum --check --status || {
    echo "模型校验失败，请删除 $MODEL_PATH 后重试。" >&2
    exit 1
}

echo "已准备 $MODEL_PATH"
