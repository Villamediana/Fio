#!/usr/bin/env bash
# Monta o App Bundle (.aab) da versão Google Play do Novelo, pronto pra subir no Play Console.
#
#   tools/play-bundle.sh            # dist/Novelo-<versão>.aab
#
# Antes: subir versionCode e versionName em app/build.gradle.kts (a Play não aceita o mesmo número
# duas vezes), commitar e fazer o merge na main.
#
# O que faz: compila a versão play com o motor no celular (o llama.cpp numa pasta temporária, apagada
# no fim) → confere pacote e versão, que não tem nada de se atualizar sozinho nem alarme restrito, que
# as bibliotecas nativas aguentam páginas de 16 KB (a Play exige) e que foi assinado com a chave de
# envio (~/keystores/novelo-upload.jks; quem assina o app que chega no celular é a Play).
set -euo pipefail
cd "$(dirname "$0")/.."

PACKAGE=com.villamediana.novelo

die() { echo "play-bundle: $*" >&2; exit 1; }

[[ -x ${JAVA_HOME:-}/bin/java ]] || export JAVA_HOME=$HOME/.toolchain/jdk-21.0.13+9
sdk=$(sed -nE 's/^sdk\.dir=(.*)$/\1/p' local.properties 2>/dev/null || true)
for candidate in "$sdk" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
  [[ -n $candidate && -d $candidate/build-tools ]] && { export ANDROID_HOME=$candidate; break; }
done
[[ -d ${ANDROID_HOME:-}/build-tools ]] || die "Android SDK não encontrado (local.properties sdk.dir ou ANDROID_HOME)"
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$JAVA_HOME/bin:$PATH"
READELF=$(find "$ANDROID_HOME/ndk" -name llvm-readelf -path "*linux-x86_64*" 2>/dev/null | sort -V | tail -1)
[[ -x $READELF ]] || die "llvm-readelf do NDK não encontrado"
UPLOAD_PROPS=$HOME/keystores/novelo-upload.properties
[[ -f $UPLOAD_PROPS ]] || die "falta a chave de envio: $UPLOAD_PROPS"

name=$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
code=$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts)
[[ $name =~ ^[0-9]+(\.[0-9]+)+$ && -n $code ]] || die "versionName/versionCode não lidos de app/build.gradle.kts"
aab="dist/Novelo-$name.aab"
echo "play-bundle: Novelo $name (build $code)"

git diff --quiet && git diff --cached --quiet || die "há mudanças sem commit"

# 1. O bundle, com o motor no celular (NOVELO_NATIVE=0 tira; só pra teste, a Play precisa dele).
work=$(mktemp -d)
trap 'rm -rf "$work" app/.cxx app/build/intermediates/cxx' EXIT
llama=""
if [[ ${NOVELO_NATIVE:-1} != 0 ]]; then
  tools/fetch-llama.sh "$work/llama.cpp"
  llama=$work/llama.cpp
fi
./gradlew :app:bundlePlayRelease -Pnovelo.llama="$llama" --console=plain -q
mkdir -p dist
cp app/build/outputs/bundle/playRelease/app-play-release.aab "$aab"

# 2. Pacote, versão e o que a Play não deixa: instalar atualizações por conta própria e o alarme
# exato reservado a despertador/calendário. (O manifesto junto, do jeito que vai no bundle.)
manifest=$(find app/build/intermediates -path "*playRelease*" -name AndroidManifest.xml -path "*merged_manifest*" | head -1)
[[ -f $manifest ]] || die "manifesto final da versão play não encontrado"
grep -q "package=\"$PACKAGE\"" "$manifest" || die "pacote não é $PACKAGE"
grep -q "android:versionCode=\"$code\"" "$manifest" || die "versionCode não é $code"
for banned in REQUEST_INSTALL_PACKAGES USE_EXACT_ALARM ".update.UpdateService" "application/vnd.android.package-archive"; do
  grep -q "$banned" "$manifest" && die "a versão play não pode ter $banned"
done
unzip -l "$aab" | grep -q "com/villamediana/novelo/update/" && die "o código do atualizador entrou no bundle"

# 3. Bibliotecas nativas alinhadas em 16 KB (exigência da Play pra quem mira Android 15+).
mkdir "$work/lib"
unzip -q -o "$aab" "base/lib/*" -d "$work/lib" || die "bundle sem bibliotecas nativas"
if [[ -n $llama ]]; then
  [[ -f $work/lib/base/lib/arm64-v8a/libnovelo_engine.so ]] || die "bundle sem o motor no celular (libnovelo_engine.so)"
fi
for so in "$work"/lib/base/lib/*/*.so; do
  for align in $("$READELF" -lW "$so" | awk '$1 == "LOAD" { print $NF }'); do
    (( align >= 16384 )) || die "$(basename "$so") alinhada em $align (precisa de 16 KB)"
  done
done

# 4. Assinado com a chave de envio.
want=$(keytool -list -v -keystore "$(sed -n 's/^storeFile=//p' "$UPLOAD_PROPS")" -storepass "$(sed -n 's/^storePassword=//p' "$UPLOAD_PROPS")" -alias upload 2>/dev/null | awk '/SHA256:/ { print $2; exit }')
got=$(keytool -printcert -jarfile "$aab" 2>/dev/null | awk '/SHA256:/ { print $2; exit }')
[[ -n $want && $got == "$want" ]] || die "bundle assinado com outra chave ($got)"

echo "play-bundle: $aab $(stat -c %s "$aab") bytes · chave de envio $got"
