/*
 * Everything a decompiled Kotlin/Java side would need to reconstruct the
 * firmware key now lives ONLY here, in native code compiled into libyas_core.so.
 * jadx/apktool/JEB show the Kotlin call sites as "call into native method" --
 * no XOR constants, no HMAC inputs, no AES key material in the dex at all.
 * Reading this logic now requires a native disassembler (Ghidra/IDA) on the
 * .so, which is a materially higher-effort attack than `jadx -d .` was.
 *
 * HONEST LIMITATION (matches the rest of this codebase's doc comments):
 * this raises the bar, it does not make the app unbreakable. A static .so
 * can still be disassembled; Frida can still hook native exports by symbol
 * name (mitigate further with -fvisibility=hidden + stripped symbols, see
 * CMakeLists.txt). Pair this with the server-issued per-operation grant
 * (OperationAuthClient/SensitiveOperationGate) which remains the REAL gate --
 * this native layer protects the "how", the server protects the "whether".
 */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/ptrace.h>
#include <dirent.h>
#include "sha256.h"
#include "aes256ctr.h"

/* ---- Native debugger / Frida detection -----------------------------------
 * Everything in TamperGuard.kt (Debug.isDebuggerConnected(), the Xposed
 * classloader probe, the /proc file-path scan) runs on the JVM side and can
 * be defeated by hooking those exact Java methods with Frida itself -- the
 * classic "hook the anti-Frida check" trick. These two checks run here, in
 * native code, specifically so that defeating them requires either patching
 * this .so's disassembly or intercepting a raw ptrace()/readdir() syscall
 * (which breaks a lot more than just this check). Still not unbeatable --
 * see the file header -- but it's a different, harder attack than hooking a
 * Kotlin method by name.
 */
static int detect_tracer(void) {
    int tracer_pid_hit = 0;
    FILE *f = fopen("/proc/self/status", "r");
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                if (atoi(line + 10) != 0) tracer_pid_hit = 1;
                break;
            }
        }
        fclose(f);
    }

    /* A process can only have one tracer. If something is already tracing us
     * (gdb, Frida's ptrace-based attach, etc.) this self-attach fails -- a
     * kernel-enforced fact we're reading directly, not a /proc text file
     * whose contents a hook could spoof. */
    int ptrace_hit = 0;
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
        ptrace_hit = 1;
    } else {
        ptrace(PTRACE_DETACH, 0, 0, 0); /* release immediately, stay runnable */
    }

    return (tracer_pid_hit || ptrace_hit) ? 1 : 0;
}

/* Frida injects a GLib main loop + JS runtime that spawn threads with
 * characteristic names ("gmain", "gdbus", "gum-js-loop", "pool-frida")
 * regardless of what the injected .so/server binary itself is named --
 * unlike a file-path or library-name scan, renaming frida-server doesn't
 * hide this. */
static int detect_frida_threads(void) {
    DIR *d = opendir("/proc/self/task");
    if (!d) return 0;
    struct dirent *entry;
    int hit = 0;
    while ((entry = readdir(d)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        char comm[64] = {0};
        if (fgets(comm, sizeof(comm), f)) {
            if (strstr(comm, "gum-js-loop") || strstr(comm, "gmain") ||
                strstr(comm, "gdbus") || strstr(comm, "pool-frida")) {
                hit = 1;
            }
        }
        fclose(f);
        if (hit) break;
    }
    closedir(d);
    return hit;
}

static int native_trace_detected(void) {
    return (detect_tracer() || detect_frida_threads()) ? 1 : 0;
}

/* ---- Obfuscated embedded partial key (was OperationKeyClient.embeddedPartialKey) ----
 * Stored XOR'd with a per-byte mask so it doesn't sit as a readable 32-byte
 * run in the .rodata section of the .so (defeats a plain `strings`/entropy
 * scan, not a disassembler). Regenerate both arrays for your own deployment
 * -- do not ship this example value. */
static const uint8_t EMBEDDED_PARTIAL_KEY_ENC[32] = {
    0xd0,0x90,0x69,0x3e,0x68,0x69,0x68,0x16,
    0x59,0xff,0x09,0xdb,0x94,0x43,0xcd,0x09,
    0x84,0x53,0xba,0x8f,0x8f,0x0e,0xef,0xb6,
    0x31,0xd0,0x06,0xf4,0x97,0x97,0x9e,0xa3
};
#define XOR_PAD 0x5A

/* ---- Obfuscated firmware KEY_MATERIAL (was FirmwareCipher.KEY_MATERIAL/KEY_MASK) ---- */
static const uint8_t FW_KEY_MATERIAL[32] = {
    75,89,-18,105,117,120,-90,80,57,91,-10,66,31,-15,120,-114,
    24,107,-37,-94,-111,78,33,-32,-29,-75,81,87,-3,73,0,-41
};
static const uint8_t FW_KEY_MASK[32] = {
    -38,-77,25,-76,39,-40,38,-127,-65,87,-32,-46,51,96,31,29,
    4,-106,79,48,50,99,105,-18,9,-95,22,-46,-48,53,73,41
};
#define FW_PAD 0x7E

static void reconstruct_embedded_partial_key(uint8_t out[32]) {
    for (int i=0;i<32;++i) out[i] = (uint8_t)(EMBEDDED_PARTIAL_KEY_ENC[i] ^ XOR_PAD);
}

static void reconstruct_fw_key_material(uint8_t out[32]) {
    for (int i=0;i<32;++i) {
        uint8_t mask = (uint8_t)(FW_KEY_MASK[i] ^ FW_PAD);
        out[i] = (uint8_t)(FW_KEY_MATERIAL[i] ^ mask);
    }
}

static int hex_to_bytes(const char *hex, size_t hexlen, uint8_t *out, size_t outcap) {
    if (hexlen % 2 != 0 || hexlen / 2 > outcap) return -1;
    for (size_t i=0; i<hexlen/2; ++i) {
        int hi = hex[i*2], lo = hex[i*2+1];
        int hv = (hi>='0'&&hi<='9')?hi-'0':(hi>='a'&&hi<='f')?hi-'a'+10:(hi>='A'&&hi<='F')?hi-'A'+10:-1;
        int lv = (lo>='0'&&lo<='9')?lo-'0':(lo>='a'&&lo<='f')?lo-'a'+10:(lo>='A'&&lo<='F')?lo-'A'+10:-1;
        if (hv<0 || lv<0) return -1;
        out[i] = (uint8_t)((hv<<4)|lv);
    }
    return 0;
}

/*
 * Replaces OperationKeyClient.deriveFirmwareKey's HKDF-ish step.
 * Java signature: static native byte[] deriveFirmwareKeyNative(String keyMaterialHex)
 * Returns 32 bytes, or null if keyMaterialHex is malformed.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_edl_flasher_auth_NativeCore_deriveFirmwareKeyNative(JNIEnv *env, jobject thiz, jstring keyMaterialHex) {
    (void)thiz;
    const char *hex = (*env)->GetStringUTFChars(env, keyMaterialHex, NULL);
    size_t hexlen = strlen(hex);
    uint8_t serverMaterial[256];
    if (hexlen == 0 || hexlen/2 > sizeof(serverMaterial) ||
        hex_to_bytes(hex, hexlen, serverMaterial, sizeof(serverMaterial)) != 0) {
        (*env)->ReleaseStringUTFChars(env, keyMaterialHex, hex);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, keyMaterialHex, hex);

    uint8_t embeddedPartialKey[32];
    reconstruct_embedded_partial_key(embeddedPartialKey);

    uint8_t prk[32];
    hmac_sha256(embeddedPartialKey, sizeof(embeddedPartialKey), serverMaterial, hexlen/2, prk);
    memset(embeddedPartialKey, 0, sizeof(embeddedPartialKey));

    static const char info[] = "yas-firmware-key-v1";
    uint8_t out[32];
    hmac_sha256(prk, sizeof(prk), (const uint8_t*)info, sizeof(info)-1, out);
    memset(prk, 0, sizeof(prk));

    jbyteArray result = (*env)->NewByteArray(env, 32);
    (*env)->SetByteArrayRegion(env, result, 0, 32, (const jbyte*)out);
    memset(out, 0, sizeof(out));
    return result;
}

/*
 * Replaces FirmwareCipher.deriveBoundKey + decryptingStream's cipher setup,
 * but returns the key bytes to Kotlin rather than owning the whole stream --
 * streaming a Java InputStream through JNI per-chunk is more plumbing than
 * benefit here, and a transient key byte[] in Java heap for the duration of
 * one CipherInputStream read is the same exposure the Kotlin version already
 * had. What matters (the derivation inputs/constants) is now native-only.
 *
 * Java signature: static native byte[] deriveBoundKeyNative(String certSha256Hex, byte[] serverKey)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_edl_flasher_auth_NativeCore_deriveBoundKeyNative(JNIEnv *env, jobject thiz, jstring certSha256Hex, jbyteArray serverKey) {
    (void)thiz;
    const char *certHex = (*env)->GetStringUTFChars(env, certSha256Hex, NULL);
    jsize certHexLen = (*env)->GetStringUTFLength(env, certSha256Hex);

    uint8_t material[32];
    reconstruct_fw_key_material(material);

    SHA256_CTX c1;
    sha256_init(&c1);
    sha256_update(&c1, material, sizeof(material));
    memset(material, 0, sizeof(material));
    /* lowercase hex, as the Kotlin side did with certSha256Hex.lowercase() --
     * callers MUST pass an already-lowercased hex string. */
    sha256_update(&c1, (const uint8_t*)certHex, (size_t)certHexLen);
    uint8_t certKey[32];
    sha256_final(&c1, certKey);
    (*env)->ReleaseStringUTFChars(env, certSha256Hex, certHex);

    jsize serverKeyLen = (*env)->GetArrayLength(env, serverKey);
    jbyte *serverKeyBytes = (*env)->GetByteArrayElements(env, serverKey, NULL);

    SHA256_CTX c2;
    sha256_init(&c2);
    sha256_update(&c2, certKey, sizeof(certKey));
    memset(certKey, 0, sizeof(certKey));
    sha256_update(&c2, (const uint8_t*)serverKeyBytes, (size_t)serverKeyLen);
    (*env)->ReleaseByteArrayElements(env, serverKey, serverKeyBytes, JNI_ABORT);

    uint8_t finalKey[32];
    sha256_final(&c2, finalKey);

    /* Same rationale as cert-binding above, one layer deeper: if a debugger
     * or Frida is attached to THIS process right now, silently corrupt the
     * returned key instead of refusing/throwing. A loud failure (exception,
     * null return) is one `if` an attacker can find and invert; a wrong key
     * just makes the firmware decrypt to garbage, with nothing to patch
     * because nothing here says "tampering detected" anywhere an attacker
     * can find and flip it. Combined with TamperGuard's Java-side report to
     * the server (for banning the device), this means even a fully disabled
     * server check still leaves the app non-functional for a hooked process. */
    if (native_trace_detected()) {
        for (int i = 0; i < 32; ++i) finalKey[i] ^= (uint8_t)(0xC3 + i);
    }

    jbyteArray result = (*env)->NewByteArray(env, 32);
    (*env)->SetByteArrayRegion(env, result, 0, 32, (const jbyte*)finalKey);
    memset(finalKey, 0, sizeof(finalKey));
    return result;
}

/*
 * Reporting-only: lets TamperGuard.kt include native-level trace/hook
 * detection in the indicator string sent to the server (authorize_operation
 * / authorize_firmware_download / report_tamper), same as the existing
 * root/debugger/hooking/emulator signals. This is IN ADDITION TO, not
 * instead of, the silent key corruption in deriveBoundKeyNative above --
 * that one still protects the app even if this report is suppressed.
 *
 * Java signature: static native boolean nativeTraceDetectedNative()
 */
JNIEXPORT jboolean JNICALL
Java_com_edl_flasher_auth_NativeCore_nativeTraceDetectedNative(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return native_trace_detected() ? JNI_TRUE : JNI_FALSE;
}

/*
 * One-shot AES-256-CTR encrypt/decrypt of a byte[] chunk (CTR is symmetric).
 * `iv` must be the current 16-byte counter block; the caller (Kotlin) is
 * responsible for chunking large files and NOT reusing an IV across two
 * different chunks with the same key -- see NativeCore.kt's streaming
 * wrapper, which increments the counter between chunks exactly the way
 * CipherInputStream did.
 *
 * Java signature: static native byte[] aesCtrXcryptNative(byte[] key, byte[] iv, byte[] data)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_edl_flasher_auth_NativeCore_aesCtrXcryptNative(JNIEnv *env, jobject thiz, jbyteArray key, jbyteArray iv, jbyteArray data) {
    (void)thiz;
    if ((*env)->GetArrayLength(env, key) != AES256_KEYLEN || (*env)->GetArrayLength(env, iv) != AES_BLOCKLEN) {
        return NULL;
    }
    jbyte *keyBytes = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *ivBytes = (*env)->GetByteArrayElements(env, iv, NULL);
    jsize dataLen = (*env)->GetArrayLength(env, data);
    jbyte *dataBytes = (*env)->GetByteArrayElements(env, data, NULL);

    uint8_t *buf = (uint8_t*)malloc((size_t)dataLen > 0 ? (size_t)dataLen : 1);
    if (buf == NULL) {
        (*env)->ReleaseByteArrayElements(env, key, keyBytes, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, iv, ivBytes, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, data, dataBytes, JNI_ABORT);
        return NULL;
    }
    memcpy(buf, dataBytes, (size_t)dataLen);

    AES256_ctx ctx;
    AES256_init_ctx_iv(&ctx, (const uint8_t*)keyBytes, (const uint8_t*)ivBytes);
    AES256_CTR_xcrypt_buffer(&ctx, buf, (size_t)dataLen);

    (*env)->ReleaseByteArrayElements(env, key, keyBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, iv, ivBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, data, dataBytes, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, dataLen);
    (*env)->SetByteArrayRegion(env, result, 0, dataLen, (const jbyte*)buf);
    memset(buf, 0, (size_t)dataLen);
    free(buf);
    return result;
}
