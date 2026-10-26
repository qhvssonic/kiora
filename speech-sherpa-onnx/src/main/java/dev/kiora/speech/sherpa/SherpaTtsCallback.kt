package dev.kiora.speech.sherpa

/**
 * sherpa-onnx JNI looks up invoke([F)Ljava/lang/Integer; on the concrete callback.
 * An invokedynamic lambda only exposes the erased invoke(Object): Object method.
 * Keep this explicit class and its typed override, including in optimized apps.
 */
internal class SherpaTtsCallback(private val delegate: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = delegate(samples)
}
