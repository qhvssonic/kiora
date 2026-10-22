package dev.kiora.embedding

import kotlin.math.sqrt

/** The host must change this identity when model weights or preprocessing change. */
class EmbeddingVector(modelId: String, values: FloatArray) {
    val modelId: String = modelId.also { require(it.isNotBlank()) }
    private val data: FloatArray = values.copyOf().also { vector ->
        require(vector.isNotEmpty()) { "Embedding vector is empty" }
        require(vector.all(Float::isFinite)) { "Embedding vector contains non-finite values" }
    }

    val dimension: Int get() = data.size
    fun values(): FloatArray = data.copyOf()

    /** Keep the leading Matryoshka dimensions and L2-normalize again. */
    fun truncateAndNormalize(dimension: Int): EmbeddingVector {
        require(dimension in 1..data.size) { "Invalid embedding dimension" }
        val result = data.copyOf(dimension)
        val magnitude = sqrt(result.sumOf { it.toDouble() * it })
        require(magnitude.isFinite() && magnitude > 0.0) { "Cannot normalize a zero embedding" }
        for (i in result.indices) result[i] = (result[i] / magnitude).toFloat()
        return EmbeddingVector(modelId, result)
    }

    /** Vectors from different models or dimensions must never be compared. */
    fun cosineSimilarity(other: EmbeddingVector): Double {
        require(modelId == other.modelId) { "Embedding models differ" }
        require(dimension == other.dimension) { "Embedding dimensions differ" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in data.indices) {
            dot += data[i] * other.data[i].toDouble()
            normA += data[i] * data[i].toDouble()
            normB += other.data[i] * other.data[i].toDouble()
        }
        require(normA > 0.0 && normB > 0.0) { "Cannot compare zero embeddings" }
        return dot / sqrt(normA * normB)
    }
}

/** Embedding is independent of the generative ModelPort and its chat history. */
interface EmbeddingPort {
    val modelId: String

    /** A search query may need model-specific instruction formatting. */
    suspend fun embedQuery(text: String): EmbeddingVector

    /** Corpus text uses document formatting rather than a query instruction. */
    suspend fun embedDocument(text: String, title: String? = null): EmbeddingVector

    /** An image is supplied by the host after checking its own access rights. */
    suspend fun embedImage(imageBytes: ByteArray): EmbeddingVector

    /** Waits for in-flight inference before releasing native resources. */
    suspend fun close()
}
