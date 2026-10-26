# sherpa-onnx looks up this typed method from JNI, outside the JVM call graph.
-keep class dev.kiora.speech.sherpa.SherpaTtsCallback { *; }
