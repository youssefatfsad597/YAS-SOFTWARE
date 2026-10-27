# ============================================================
# YAS SOFTWARE - release hardening rules
# ============================================================

# --- Keep what's actually needed (Compose + Kotlin metadata) ---
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keep class androidx.compose.** { *; }
-dontwarn org.conscrypt.**

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
