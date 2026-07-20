# WebRTC's native (C++/JNI) side calls into org.webrtc.* by field/method name — R8 renaming or
# removing anything in this package breaks at runtime with NoSuchMethodError/NoSuchFieldError,
# not at build time, since R8 has no visibility into what the native side actually touches.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Tink (pulled in transitively via androidx.security.crypto) references these errorprone
# annotations at the source level only (CLASS/SOURCE retention, no runtime behavior) — the
# annotation processor jar itself isn't on the runtime classpath, so R8 can't find the classes.
# Safe to just silence: nothing here is ever needed at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
