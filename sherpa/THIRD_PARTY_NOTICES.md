# Third-party notices for the :sherpa module

This module vendors the sherpa-onnx Kotlin API and bundles prebuilt native
libraries. Attribution is bundled in the APK assets alongside the voice assets.

## sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx)
- Version: v1.13.8
- License: Apache License 2.0 (see LICENSE in this directory)
- Vendored: `src/main/java/com/k2fsa/sherpa/onnx/*.kt`
- Native libs copied into the APK from the official android release tarball:
  `libsherpa-onnx-jni.so` and `libonnxruntime.so` (arm64-v8a).

## ONNX Runtime (https://github.com/microsoft/onnxruntime)
- Bundled as `libonnxruntime.so` (part of the sherpa-onnx android release).
- License: MIT License.
