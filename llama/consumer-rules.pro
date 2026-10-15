# Bro.exe vendored llama.cpp binding.
# The JNI bridge is reached via com.arm.aichat.internal.InferenceEngineImpl; keep the
# native method holders and the facade from being stripped in release builds.
-keep class com.arm.aichat.AiChat { *; }
-keep class com.arm.aichat.InferenceEngine { *; }
-keep class com.arm.aichat.internal.InferenceEngineImpl { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
