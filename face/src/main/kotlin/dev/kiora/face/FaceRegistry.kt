package dev.kiora.face

import dev.kiora.embedding.EmbeddingVector
import java.util.UUID

data class RegisteredPerson(val id: String, val name: String)

/** Persistence is host supplied; replace must be atomic and delete must remove all templates. */
interface FaceRegistryStore {
    fun people(): List<RegisteredPerson>
    fun templates(personId: String, modelId: String): List<EmbeddingVector>
    fun replace(person: RegisteredPerson, modelId: String, templates: List<EmbeddingVector>)
    fun delete(personId: String)
}

/** Explicit enrollment; the host obtains consent and supplies a single selected face. */
class FaceRegistry(private val store: FaceRegistryStore, val modelId: String) {
    fun enroll(name: String, samples: List<FaceObservation>, personId: String? = null): RegisteredPerson {
        require(name.trim().length in 1..80) { "Name must contain 1–80 characters" }
        require(samples.isNotEmpty()) { "At least one sample is required" }
        samples.forEach {
            require(it.embedding.modelId == modelId && it.embedding.dimension == 512)
            require(it.qualityIssues.isEmpty()) { "Use a clearer, closer face sample" }
            it.embedding.cosineSimilarity(it.embedding) // Reject zero vectors.
        }
        if (personId != null) require(store.people().any { it.id == personId }) { "Unknown person" }
        val id = personId ?: UUID.randomUUID().toString()
        val existing = store.templates(id, modelId)
        require(existing.size + samples.size <= 12) { "Maximum 12 templates per person" }
        val person = RegisteredPerson(id, name.trim())
        store.replace(person, modelId, existing + samples.map { it.embedding })
        return person
    }

    fun search(query: EmbeddingVector, policy: FaceMatchPolicy, limit: Int = 5): FaceSearchResult {
        require(query.modelId == modelId)
        val templates = store.people().flatMap { person ->
            store.templates(person.id, modelId).map { FaceTemplate(person.id, it) }
        }
        return FaceMatcher.search(query, templates, policy, limit)
    }

    fun remove(personId: String) = store.delete(personId)
}
