#!/usr/bin/env bash
# Encrypts the committed plaintext firmware under firmware/<dir>/ into
# app/src/main/assets/<dir>/<name>.enc, AES-256-CTR, so the files are never
# stored plaintext inside the built APK.
#
# Format written: [16-byte random IV][ciphertext] (CTR adds no padding, so
# ciphertext length == plaintext length).
#
# KEY BINDING (must match FirmwareCipher.kt's deriveKey exactly):
#   AES key = SHA-256( KEY_MATERIAL_HEX_bytes || lowercase_hex(cert_sha256) )
# cert_sha256 is the signing certificate's SHA-256 fingerprint the APK will
# actually be signed with: pass it in via CERT_SHA256 (release builds export
# RELEASE_CERT_SHA256 for this; debug builds fall back to the committed
# app/debug.keystore's own fingerprint below, matching FirmwareCipher.kt's
# DEBUG_CERT_SHA256 constant). If you rotate KEY_MATERIAL_HEX, update it in
# BOTH places: here and the KEY_MATERIAL / KEY_MASK / PAD obfuscated split in
# app/src/main/java/com/edl/flasher/protocol/FirmwareCipher.kt (run
# tools/derive_firmware_key_arrays.py to regenerate that split).
#
# Used by: .github/workflows/main.yml and .github/workflows/release.yml
# (replaces the old plain `cp` steps). Safe to run locally too.
#
# Usage: CERT_SHA256=<hex, from your release keystore> tools/encrypt_firmware_assets.sh
#        (omit CERT_SHA256 for a debug build -- it falls back automatically)

set -euo pipefail

KEY_MATERIAL_HEX="d7ca983a91da7c32bc02500a2b74731105f50afffbb85009be552b7e81ddfc66"
# Must equal FirmwareCipher.kt's DEBUG_CERT_SHA256 constant.
DEBUG_CERT_SHA256="7cdc85e66fe80c5f7685ef2649940cc719908befcac713d79dcd1dbecd8e1b4"

CERT_SHA256="${CERT_SHA256:-$DEBUG_CERT_SHA256}"
if [ -z "$CERT_SHA256" ]; then
  CERT_SHA256="$DEBUG_CERT_SHA256"
fi
CERT_SHA256=$(echo -n "$CERT_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d ':')

# KEY_HEX = SHA-256(KEY_MATERIAL_HEX_bytes || lowercase_hex_cert_sha256_ascii_bytes),
# exactly mirroring FirmwareCipher.deriveKey() in Kotlin.
KEY_HEX=$(python3 -c "
import hashlib, binascii, sys
material = binascii.unhexlify(sys.argv[1])
cert = sys.argv[2].encode('ascii')
print(hashlib.sha256(material + cert).hexdigest())
" "$KEY_MATERIAL_HEX" "$CERT_SHA256")

echo "Deriving firmware key for cert SHA-256: $CERT_SHA256"

encrypt_one() {
  local src="$1" dst="$2"
  local iv iv_bin tmp_cipher
  iv=$(openssl rand -hex 16)
  iv_bin=$(mktemp)
  tmp_cipher=$(mktemp)
  # hex -> raw bytes via python3 (more portable across runners than xxd, which
  # isn't preinstalled everywhere)
  python3 -c "import sys,binascii; sys.stdout.buffer.write(binascii.unhexlify('$iv'))" > "$iv_bin"
  openssl enc -aes-256-ctr -K "$KEY_HEX" -iv "$iv" -in "$src" -out "$tmp_cipher"
  cat "$iv_bin" "$tmp_cipher" > "$dst"
  rm -f "$iv_bin" "$tmp_cipher"
}

encrypt_dir() {
  local src_dir="$1" dst_dir="$2"
  [ -d "$src_dir" ] || { echo "  (skip: $src_dir not found)"; return 0; }
  mkdir -p "$dst_dir"
  local f base
  for f in "$src_dir"/*; do
    [ -f "$f" ] || continue
    base=$(basename "$f")
    encrypt_one "$f" "$dst_dir/$base.enc"
    echo "  encrypted $f -> $dst_dir/$base.enc"
  done
}

echo "Encrypting X216B firmware assets..."
encrypt_dir "firmware/x216b" "app/src/main/assets/x216b"

echo "Encrypting T509 firmware assets..."
encrypt_dir "firmware/t509" "app/src/main/assets/t509"

echo "Encrypting T505N firmware assets..."
encrypt_dir "firmware/t505n" "app/src/main/assets/t505n"

echo "Done."
