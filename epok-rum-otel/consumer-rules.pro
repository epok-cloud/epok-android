# OTel's instrumentations are discovered with ServiceLoader; keep their constructors.
-keep class * implements io.opentelemetry.android.instrumentation.AndroidInstrumentation { public <init>(); }
# Optional compile-time annotations, not runtime classes.
-dontwarn com.google.errorprone.annotations.**
# Init crash attribution examines class prefixes, including in minified customer releases.
# Keep class names while still allowing shrinking and method optimization/obfuscation.
-keepnames class dev.epok.rum.otel.**
-keepnames class io.opentelemetry.**
# Generated lambda/outline classes can be repackaged even with -keepnames.
-keeppackagenames dev.epok.rum.otel,dev.epok.rum.otel.**,io.opentelemetry,io.opentelemetry.**

# These host-network forwarding frames are excluded from init-crash attribution.
# Prevent R8 from merging/inlining them into an attributed SDK class.
-keep,allowshrinking class dev.epok.rum.otel.HostInterceptors$* { *; }
-keep,allowshrinking class io.opentelemetry.instrumentation.okhttp.** { *; }
-keep,allowshrinking class io.opentelemetry.instrumentation.library.okhttp.** { *; }
-keep,allowshrinking class io.opentelemetry.android.instrumentation.okhttp.** { *; }
