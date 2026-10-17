# Bro.exe ProGuard/R8 rules (release builds shrink with full R8 mode).

# Keep names of anything JNI reaches by name. AGP's default config keeps `native`
# methods, but the vendored sherpa-onnx Kotlin API is a thin facade whose classes and
# result objects cross the JNI boundary, so keep the whole package explicitly.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The vendored llama.cpp binding ships its own keep rules in :llama, repeated here so
# the facade survives even if the module rules are ever changed.
-keep class com.arm.aichat.** { *; }

# Belt-and-braces: never rename native method holders.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Manifest components + app entry points (also auto-kept by AGP, kept explicitly so a
# future refactor cannot quietly strip them).
-keep class com.elthon.broexe.MainActivity { *; }
-keep class com.elthon.broexe.BroExeApp { *; }
-keep class com.elthon.broexe.voice.VoiceService { *; }
