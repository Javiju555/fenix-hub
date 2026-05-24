#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAURI_CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/tauri"
LINUXDEPLOY_URL="${LINUXDEPLOY_URL:-https://github.com/linuxdeploy/linuxdeploy/releases/download/1-alpha-20251107-1/linuxdeploy-x86_64.AppImage}"
LINUXDEPLOY_BIN="$TAURI_CACHE_DIR/linuxdeploy-x86_64.AppImage"
APPDIR="$ROOT_DIR/target/release/bundle/appimage/FenixHub.AppDir"
OUT_DIR="$ROOT_DIR/target/release/bundle/appimage"
VERSION="$(sed -n 's/.*"version": "\([^"]*\)".*/\1/p' "$ROOT_DIR/src-tauri/tauri.conf.json" | head -n 1)"
FINAL_APPIMAGE="$OUT_DIR/FenixHub_${VERSION}_amd64.AppImage"
TMP_DIR="$(mktemp -d)"
BUN_BIN="${BUN_BIN:-}"

if [ -z "$BUN_BIN" ]; then
  BUN_BIN="$(command -v bun || true)"
fi

if [ -z "$BUN_BIN" ] && [ -x "$HOME/.bun/bin/bun" ]; then
  BUN_BIN="$HOME/.bun/bin/bun"
fi

if [ -z "$BUN_BIN" ]; then
  echo "No se ha encontrado bun. Exporta BUN_BIN o instala bun en PATH." >&2
  exit 1
fi

cleanup() {
  rm -rf "$TMP_DIR"
}

trap cleanup EXIT

mkdir -p "$TAURI_CACHE_DIR" "$OUT_DIR"

if [ ! -f "$LINUXDEPLOY_BIN" ]; then
  echo "==> Descargando linuxdeploy reciente"
  curl -L "$LINUXDEPLOY_URL" -o "$LINUXDEPLOY_BIN"
  chmod +x "$LINUXDEPLOY_BIN"
fi

echo "==> Limpiando AppDir anterior"
rm -rf "$APPDIR"
rm -f "$FINAL_APPIMAGE"

echo "==> Compilando frontend"
"$BUN_BIN" run --cwd "$ROOT_DIR/frontend" build

echo "==> Pidiendo a Tauri que prepare el AppDir"
if ! (
  cd "$ROOT_DIR"
  "$BUN_BIN" x tauri build --bundles appimage
); then
  if [ ! -d "$APPDIR" ]; then
    echo "Tauri no ha llegado a generar el AppDir necesario para el AppImage." >&2
    exit 1
  fi

  echo "Tauri ha fallado al empaquetar el AppImage en esta distro."
  echo "Continuando con linuxdeploy manual usando el strip del sistema."
fi

echo "==> Empaquetando AppImage final"
(
  cd "$TMP_DIR"
  PATH="$TAURI_CACHE_DIR:$PATH" "$LINUXDEPLOY_BIN" --appimage-extract >/dev/null
  rm -f squashfs-root/usr/bin/strip
  ln -s /usr/bin/strip squashfs-root/usr/bin/strip
  PATH="$TAURI_CACHE_DIR:$PATH" ./squashfs-root/AppRun --appdir "$APPDIR" --output appimage
  mv FenixHub-x86_64.AppImage "$FINAL_APPIMAGE"
)

chmod +x "$FINAL_APPIMAGE"

echo
echo "AppImage listo:"
echo "  $FINAL_APPIMAGE"
