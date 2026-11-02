# ============================================================
# YAS SOFTWARE - release hardening rules
# ============================================================

# --- Keep what's actually needed (Compose + Kotlin metadata) ---
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keep class androidx.compose.** { *; }
-dontwarn org.conscrypt.**

# --- Tink (pulled in by androidx.security:security-crypto) references
# annotation classes (errorprone, javax.annotation) that are not shipped
# in the APK. Safe to ignore: they are compile-time-only annotations. ---
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# --- Everything else: obfuscate as aggressively as R8 allows ---
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
-flattenpackagehierarchy ''
-optimizationpasses 5

# --- Strip logging from release builds (no leftover debug strings/paths) ---
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** println(...);
}

# --- Make crash/stack traces useless to an attacker (no file/line hints) ---
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable

# --- Never let R8 silently keep auth/license classes unobfuscated ---
# (no explicit -keep here on purpose: com.edl.flasher.auth.* MUST be renamed)
-keepclassmembers,allowobfuscation class com.edl.flasher.auth.** { *; }

# --- Exception: NativeCore's package + class name + native method names must
# stay exactly as written, because JNI static linking resolves libyas_core.so's
# exported Java_com_edl_flasher_auth_NativeCore_* symbols by that literal name
# (see app/src/main/cpp/yas_native.c). Renaming/repackaging this one class
# would make System.loadLibrary succeed but every external fun throw
# UnsatisfiedLinkError at the first call. This exemption only protects a
# class with no logic in it (pure `external fun` declarations) -- the actual
# key material and derivation this migration was meant to hide out of
# decompiled Kotlin never comes back by keeping this bridge visible.
-keep class com.edl.flasher.auth.NativeCore {
    native <methods>;
}
-keepnames class com.edl.flasher.auth.NativeCore
