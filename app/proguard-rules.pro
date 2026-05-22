# =============================================================================
# Voicetastic ProGuard / R8 rules
# =============================================================================
# Applied on release builds (isMinifyEnabled = true in app/build.gradle.kts).

# Keep source-line attributes so crash reports stay readable. The matching
# `-renamesourcefileattribute` opaques the file name so stack traces don't
# leak our package layout in obfuscated builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# UniFFI-generated Kotlin bindings
# -----------------------------------------------------------------------------
# The bindings under `uniffi.*` are JNI-adjacent: they call into
# libvoicetastic.so via JNA and are called back from Rust worker threads
# through the foreign-trait machinery. R8 can't see those reflective entry
# points so we keep them whole. Cost is a few KB of class metadata; the
# alternative is hunting down each reflective callsite, which is brittle
# across uniffi-rs releases.
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }

# -----------------------------------------------------------------------------
# JNA (Java Native Access)
# -----------------------------------------------------------------------------
# UniFFI's Kotlin runtime loads libvoicetastic.so through JNA. JNA itself
# uses heavy reflection / native lookups; the upstream-recommended rule set
# keeps the library and any user-defined callback / structure subclasses.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
}
# JNA's desktop integration references java.awt.* (e.g. Native$AWT.getComponentID)
# which doesn't exist on Android. The AWT entry points are unreachable at runtime
# but R8's class verification stops the build unless we silence the warnings.
-dontwarn java.awt.**

# -----------------------------------------------------------------------------
# Protobuf Lite
# -----------------------------------------------------------------------------
# protobuf-javalite registers generated classes by name through the
# `GeneratedMessageLite` machinery. The MeshProtos.* generated types must
# survive minification so encode/decode keeps round-tripping.
-keep class com.google.protobuf.** { *; }
-keepnames class com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# -----------------------------------------------------------------------------
# USB serial
# -----------------------------------------------------------------------------
# The usb-serial library uses reflection to enumerate driver classes for
# the various USB→serial chipsets (CP210x, FTDI, CDC-ACM, Prolific, ...).
-keep class com.hoho.android.usbserial.driver.** { *; }

# -----------------------------------------------------------------------------
# App-side BroadcastReceivers / coroutine entry points
# -----------------------------------------------------------------------------
# Anonymous BroadcastReceivers in re.chasam.voicetastic are registered
# dynamically with registerReceiver(); R8 can see those, so no keeps are
# needed for the app's own code. Listed here as a marker if we ever add
# manifest-declared receivers.
