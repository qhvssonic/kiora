package dev.kiora.embedding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddingVectorTest {
    @Test fun truncationRenormalizesAndProtectsInput() {
        val input = floatArrayOf(3f, 4f, 12f)
        val vector = EmbeddingVector("model-v1", input)
        input[0] = 0f
        val truncated = vector.truncateAndNormalize(2)
        assertEquals(0.6f, truncated.values()[0], 0.0001f)
        assertEquals(0.8f, truncated.values()[1], 0.0001f)
        assertEquals(1.0, truncated.cosineSimilarity(truncated), 0.00001)
    }

    @Test fun incompatibleVectorsAreRejected() {
        val vector = EmbeddingVector("model-v1", floatArrayOf(1f, 0f))
        assertFailsWith<IllegalArgumentException> {
            vector.cosineSimilarity(EmbeddingVector("model-v2", floatArrayOf(1f, 0f)))
        }
        assertFailsWith<IllegalArgumentException> {
            vector.cosineSimilarity(EmbeddingVector("model-v1", floatArrayOf(1f)))
        }
    }
}
