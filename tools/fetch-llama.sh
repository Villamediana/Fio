#!/usr/bin/env bash
# Baixa o llama.cpp (ggml-org, MIT) na versão que o motor do celular usa, em third_party/llama.cpp.
# Com ele presente, o build inclui o LFM3 no celular (-Pfio.native=true, o release.sh faz sozinho).
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=v0.9.0
if [[ -d third_party/llama.cpp/.git ]]; then
  git -C third_party/llama.cpp fetch -q --depth 1 origin tag "$TAG"
  git -C third_party/llama.cpp checkout -q "$TAG"
else
  git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp third_party/llama.cpp
fi
git -C third_party/llama.cpp log --oneline -1
