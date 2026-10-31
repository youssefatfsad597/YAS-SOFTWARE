#!/usr/bin/env bash
# Encrypts the committed plaintext firmware under firmware/<dir>/ into
# app/src/main/assets/<dir>/<name>.enc, AES-256-CTR, so the files are never
# stored plaintext inside the built APK.
#
# Format written: [16-byte random IV][ciphertext] (CTR adds no padding, so
# ciphertext length == plaintext length). This MUST match FirmwareCipher.kt's
# key exactly -- if you ever rotate the key, update it in BOTH places:
#   1. KEY_HEX below
#   2. The KEY_MATERIAL / KEY_MASK / PAD obfuscated split in
#      app/src/main/java/com/edl/flasher/protocol/FirmwareCipher.kt
#      (run tools/derive_firmware_key_arrays.py to regenerate that split
#      from a new KEY_HEX).
#
# Used by: .github/workflows/main.yml and .github/workflows/release.yml
# (replaces the old plain `cp` steps). Safe to run locally too.
#
# Usage: tools/encrypt_firmware_assets.sh

set -euo pipefail

# Keep in sync with FirmwareCipher.kt (see header comment above).
KEY_HEX="d7ca983a91da7c32bc02500a2b74731105f50afffbb85009be552b7e81ddfc66"

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
