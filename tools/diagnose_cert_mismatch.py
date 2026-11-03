#!/usr/bin/env python3
"""
diagnose_cert_mismatch.py -- admin-only, run LOCALLY with your service_role key.

Pulls the real ciphertext + key_material_hex + sha256_plaintext for one slug
straight from your Supabase project, then tries to decrypt it with every
candidate certificate SHA-256 you give it. Whichever candidate (if any)
reproduces sha256_plaintext is the cert hash that was ACTUALLY used when you
ran upload_firmware.py for this slug -- no more guessing whether "they match".

If NONE of your candidates match, the row's key_material_hex/sha256_plaintext
itself is out of sync with the uploaded blob (re-run upload_firmware.py for
this slug to fix that), rather than a cert problem.

Usage:
  pip install requests cryptography --break-system-packages
  SUPABASE_URL=https://xxxx.supabase.co \
  SUPABASE_SERVICE_ROLE_KEY=ey... \
  python3 diagnose_cert_mismatch.py \
      --slug t505n/T505N.elf \
      --cert debug \
      --cert <your_release_keystore_sha256> \
      --cert <your_Play_Console_app_signing_cert_sha256>

Get candidate cert hashes with:
  - Debug (committed app/debug.keystore): already built in as "debug" below.
  - Local release keystore:
      keytool -list -v -keystore your.jks -alias your_alias | grep SHA256
  - Actual signing cert baked into an installed/built APK (most reliable --
    this is what TamperGuard.currentCertSha256() reads at runtime):
      apksigner verify --print-certs -v path/to/app.apk
  - Play Console (if distributed via Google Play -- Play re-signs the APK,
    so this is OFTEN the real answer when a local keystore hash "should"
    match but doesn't): Release > Setup > App integrity > App signing key
    certificate > SHA-256 certificate fingerprint.
"""
import argparse
import hashlib
import hmac
import os
import sys

try:
    import requests
except ImportError:
    sys.exit("Missing dependency: pip install requests cryptography --break-system-packages")

# ---- MUST match app/src/main/cpp/yas_native.c and upload_firmware.py exactly ----
EMBEDDED_PARTIAL_KEY = bytes.fromhex(
    "8aca33643233324c03a55381ce199753de09e0d5d554b5ec6b8a5caecdcdc4f9"[:64]
)
FW_KEY_MATERIAL = bytes.fromhex(
    "ef9489a32cdefeaff87268ee52ef19ed6283eaecdd533670946a39fb53023780"[:64]
)

DEBUG_CERT_SHA256 = "7cdc85e66fe80c5f7685ef2649940cc719908befcac713d79dcd1dbecd8e1b4"


def derive_final_key(key_material: bytes, cert_sha256_hex: str) -> bytes:
    prk = hmac.new(EMBEDDED_PARTIAL_KEY, key_material, hashlib.sha256).digest()
    firmware_key = hmac.new(prk, b"yas-firmware-key-v1", hashlib.sha256).digest()
    cert_key = hashlib.sha256(FW_KEY_MATERIAL + cert_sha256_hex.lower().encode("ascii")).digest()
    return hashlib.sha256(cert_key + firmware_key).digest()


def aes256_ctr_decrypt(key: bytes, iv: bytes, ciphertext: bytes) -> bytes:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    decryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).decryptor()
    return decryptor.update(ciphertext) + decryptor.finalize()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--slug", required=True, help="e.g. t505n/T505N.elf")
    ap.add_argument("--bucket", default="yas-firmware")
    ap.add_argument(
        "--cert", action="append", default=[], metavar="HASH_OR_'debug'",
        help="a candidate lowercase-hex SHA-256 cert fingerprint to try; repeatable. "
             "Pass the literal word 'debug' to include DEBUG_CERT_SHA256.",
    )
    args = ap.parse_args()

    supabase_url = os.environ.get("SUPABASE_URL")
    service_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not supabase_url or not service_key:
        sys.exit("Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY environment variables first.")
    if not args.cert:
        sys.exit("Pass at least one --cert candidate (use 'debug' for the built-in debug keystore hash).")

    candidates = [DEBUG_CERT_SHA256 if c.strip().lower() == "debug" else c.strip().lower() for c in args.cert]

    headers = {"Authorization": f"Bearer {service_key}", "apikey": service_key}

    # 1) Pull the registered row for this slug directly (bypasses RLS via service_role).
    sel_url = f"{supabase_url}/rest/v1/yas_firmware_files"
    resp = requests.get(sel_url, headers=headers, params={"slug": f"eq.{args.slug}", "select": "*"}, timeout=30)
    resp.raise_for_status()
    rows = resp.json()
    if not rows:
        sys.exit(f"No row found for slug='{args.slug}' in yas_firmware_files.")
    row = rows[0]
    key_material = bytes.fromhex(row["key_material_hex"])
    expected_sha256 = row["sha256_plaintext"].lower()
    storage_path = row["storage_path"]
    print(f"[1/3] Row found: storage_path={storage_path}, size_bytes={row['size_bytes']}")
    print(f"      expected sha256_plaintext = {expected_sha256}")

    # 2) Download the actual ciphertext blob from Storage.
    dl_url = f"{supabase_url}/storage/v1/object/{args.bucket}/{storage_path}"
    resp = requests.get(dl_url, headers=headers, timeout=120)
    resp.raise_for_status()
    blob = resp.content
    iv, ciphertext = blob[:16], blob[16:]
    print(f"[2/3] Downloaded {len(blob):,} bytes from Storage ({len(ciphertext):,} bytes ciphertext)")

    # 3) Try each candidate cert hash.
    print(f"[3/3] Trying {len(candidates)} candidate cert hash(es)...\n")
    match_found = False
    for cert in candidates:
        final_key = derive_final_key(key_material, cert)
        plaintext = aes256_ctr_decrypt(final_key, iv, ciphertext)
        actual_sha256 = hashlib.sha256(plaintext).hexdigest()
        ok = actual_sha256 == expected_sha256
        match_found = match_found or ok
        print(f"  cert={cert}")
        print(f"    -> sha256={actual_sha256}  {'MATCH ✅' if ok else 'no match'}")

    print()
    if match_found:
        print("Found the cert hash that was actually used to encrypt this file.")
        print("Compare it to the cert hash your installed/running APK reports")
        print("(TamperGuard.currentCertSha256(context), or `apksigner verify --print-certs`")
        print("on the exact APK file installed on the test device) -- that's your real mismatch source.")
    else:
        print("None of the candidates matched. This means key_material_hex/sha256_plaintext")
        print("for this slug is itself out of sync with the uploaded blob (not a cert problem) --")
        print("re-run upload_firmware.py for this slug to re-sync both together.")


if __name__ == "__main__":
    main()
