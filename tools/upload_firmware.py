#!/usr/bin/env python3
"""
upload_firmware.py -- admin-only tool. Run this LOCALLY, on your own machine, never
on a server the public can reach and never bundled into the APK or the admin
dashboard's client-side bundle.

What it does, for ONE plaintext firmware file:
  1. Generates a fresh random 32-byte key_material (this is the ONLY thing that will
     ever be released to the app, via authorize_firmware_download(), and only to a
     currently active/licensed device).
  2. Reproduces the app's exact derivation chain to get the real AES-256 key:
       firmwareKey = HKDF-ish(embeddedPartialKey, key_material)          [HMAC-SHA256 x2]
       finalKey    = SHA256( SHA256(FW_KEY_MATERIAL || cert_sha256_hex) || firmwareKey )
     See app/src/main/cpp/yas_native.c's deriveFirmwareKeyNative / deriveBoundKeyNative
     -- this script MUST stay byte-for-byte in sync with those two functions. If you
     ever regenerate EMBEDDED_PARTIAL_KEY_ENC / FW_KEY_MATERIAL / FW_KEY_MASK there,
     update EMBEDDED_PARTIAL_KEY and FW_KEY_MATERIAL below to match (plaintext, not
     the obfuscated/XORed form -- see that file's comments for how to de-obfuscate).
  3. Encrypts the file with AES-256-CTR(finalKey, random 16-byte IV), writes
     [IV || ciphertext] to Storage at <slug>.enc in the private 'yas-firmware' bucket,
     via the Storage REST API using your service_role key (never the anon key).
  4. Calls admin_upsert_firmware_file() so the app's catalog and
     authorize_firmware_download() know about it, storing key_material (hex) --
     this is the only place that secret is persisted server-side.

Usage:
  pip install requests --break-system-packages   # if not already available
  SUPABASE_URL=https://xxxx.supabase.co \
  SUPABASE_SERVICE_ROLE_KEY=ey... \
  python3 upload_firmware.py \
      --file /path/to/sec_efs.bin \
      --slug x216b/sec_efs.bin \
      --display-name "X216B sec_efs.bin" \
      --device-model X216B \
      --cert-sha256 <your RELEASE keystore's SHA-256, lowercase hex -- see SETUP.md>

Omit --cert-sha256 during development to use the committed debug.keystore's
fingerprint (matches FirmwareCipher's DEBUG_CERT_SHA256 fallback) -- files uploaded
that way only decrypt in debug builds, not your real release APK.
"""
import argparse
import hashlib
import hmac
import os
import sys

try:
    import requests
except ImportError:
    sys.exit("Missing dependency: pip install requests --break-system-packages")

# ---- MUST match app/src/main/cpp/yas_native.c exactly (plaintext form) ----
EMBEDDED_PARTIAL_KEY = bytes.fromhex(
    "8aca33643233324c03a55381ce199753de09e0d5d554b5ec6b8a5caecdcdc4f9"[:64]
)
FW_KEY_MATERIAL = bytes.fromhex(
    "ef9489a32cdefeaff87268ee52ef19ed6283eaecdd533670946a39fb53023780"[:64]
)
assert len(EMBEDDED_PARTIAL_KEY) == 32 and len(FW_KEY_MATERIAL) == 32, \
    "key constants above are corrupted -- re-copy them from the de-obfuscation step, see this file's docstring"

DEBUG_CERT_SHA256 = "7cdc85e66fe80c5f7685ef2649940cc719908befcac713d79dcd1dbecd8e1b4"


def derive_final_key(key_material: bytes, cert_sha256_hex: str) -> bytes:
    """Mirrors NativeCore.deriveFirmwareKeyNative() then deriveBoundKeyNative()."""
    prk = hmac.new(EMBEDDED_PARTIAL_KEY, key_material, hashlib.sha256).digest()
    firmware_key = hmac.new(prk, b"yas-firmware-key-v1", hashlib.sha256).digest()

    cert_key = hashlib.sha256(FW_KEY_MATERIAL + cert_sha256_hex.lower().encode("ascii")).digest()
    final_key = hashlib.sha256(cert_key + firmware_key).digest()
    return final_key


def aes256_ctr_encrypt(key: bytes, iv: bytes, plaintext: bytes) -> bytes:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    encryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    return encryptor.update(plaintext) + encryptor.finalize()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", required=True, help="path to the PLAINTEXT firmware file")
    ap.add_argument("--slug", required=True, help="e.g. x216b/sec_efs.bin -- what the app requests")
    ap.add_argument("--display-name", required=True)
    ap.add_argument("--device-model", default="")
    ap.add_argument("--cert-sha256", default=DEBUG_CERT_SHA256,
                     help="lowercase hex SHA-256 of the signing cert this build is for")
    ap.add_argument("--bucket", default="yas-firmware")
    args = ap.parse_args()

    supabase_url = os.environ.get("SUPABASE_URL")
    service_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not supabase_url or not service_key:
        sys.exit("Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY environment variables first.")

    with open(args.file, "rb") as fh:
        plaintext = fh.read()
    sha256_plaintext = hashlib.sha256(plaintext).hexdigest()
    print(f"[1/4] Read {len(plaintext):,} bytes, sha256={sha256_plaintext}")

    key_material = os.urandom(32)
    final_key = derive_final_key(key_material, args.cert_sha256)
    iv = os.urandom(16)
    ciphertext = aes256_ctr_encrypt(final_key, iv, plaintext)
    blob = iv + ciphertext
    print(f"[2/4] Encrypted (AES-256-CTR), {len(blob):,} bytes including the 16-byte IV header")

    storage_path = args.slug + ".enc"
    upload_url = f"{supabase_url}/storage/v1/object/{args.bucket}/{storage_path}"
    resp = requests.post(
        upload_url,
        headers={
            "Authorization": f"Bearer {service_key}",
            "apikey": service_key,
            "Content-Type": "application/octet-stream",
            "x-upsert": "true",
        },
        data=blob,
        timeout=120,
    )
    if resp.status_code not in (200, 201):
        sys.exit(f"Storage upload failed: {resp.status_code} {resp.text}")
    print(f"[3/4] Uploaded to Storage: {args.bucket}/{storage_path}")

    rpc_url = f"{supabase_url}/rest/v1/rpc/admin_upsert_firmware_file"
    resp = requests.post(
        rpc_url,
        headers={
            "Authorization": f"Bearer {service_key}",
            "apikey": service_key,
            "Content-Type": "application/json",
        },
        json={
            "p_slug": args.slug,
            "p_display_name": args.display_name,
            "p_device_model": args.device_model or None,
            "p_storage_path": storage_path,
            "p_key_material_hex": key_material.hex(),
            "p_sha256_plaintext": sha256_plaintext,
            "p_size_bytes": len(plaintext),
        },
        timeout=30,
    )
    if resp.status_code not in (200, 204):
        sys.exit(f"Catalog registration failed: {resp.status_code} {resp.text}")
    print(f"[4/4] Registered in catalog as slug='{args.slug}'. Done.")
    print()
    print("IMPORTANT: key_material_hex is now stored ONLY in the yas_firmware_files table")
    print("(service_role / SQL Editor access only) -- it was never printed above and this")
    print("script does not log it anywhere. The plaintext file on your local disk is the")
    print("only other copy; keep it somewhere safe and delete the ciphertext temp copy if any.")


if __name__ == "__main__":
    main()
