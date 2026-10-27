# Keep Compose and Kotlin metadata needed by reflection/serialization-free code.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keep class androidx.compose.** { *; }
-dontwarn org.conscrypt.**
