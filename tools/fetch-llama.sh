#!/usr/bin/env bash
# Baixa o llama.cpp (ggml-org, MIT) na versão que o motor do celular usa, na pasta dada.
# Só serve pro build: o release.sh baixa numa pasta temporária e apaga no fim (nada fica no servidor).
#
#   tools/fetch-llama.sh /tmp/x/llama.cpp && ./gradlew :app:assembleRelease -Pnovelo.llama=/tmp/x/llama.cpp
set -euo pipefail
dest=${1:?uso: tools/fetch-llama.sh <pasta>}
TAG=v0.9.0
git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$dest"
git -C "$dest" log --oneline -1
