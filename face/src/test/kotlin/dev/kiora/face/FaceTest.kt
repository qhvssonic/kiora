package dev.kiora.face

import dev.kiora.embedding.EmbeddingVector
import kotlin.math.sqrt
import kotlin.test.*

class FaceTest {
    private fun vector(x: Float, y: Float = 0f, model: String = "v1") = EmbeddingVector(model, floatArrayOf(x, y))
    private val policy = FaceMatchPolicy(.6, .1)

    @Test fun unknownDoesNotBecomeBestIdentity() {
        val result = FaceMatcher.search(vector(1f), listOf(FaceTemplate("alice", vector(0f, 1f))), policy)
        assertEquals(FaceMatchStatus.UNKNOWN, result.status)
        assertNull(result.matchedPersonId)
    }
    @Test fun ambiguityUsesSecondPersonEvenWhenOnlyOneCandidateRequested() {
        val result = FaceMatcher.search(vector(1f), listOf(
            FaceTemplate("a", vector(1f)), FaceTemplate("a", vector(.99f,.01f)),
            FaceTemplate("b", vector(.95f,sqrt(1-.95f*.95f)))), policy, limit = 1)
        assertEquals(FaceMatchStatus.AMBIGUOUS, result.status)
        assertNull(result.matchedPersonId)
    }
    @Test fun multipleSamplesOfSamePersonDoNotCauseAmbiguity() {
        val result = FaceMatcher.search(vector(1f), listOf(FaceTemplate("a", vector(1f)),
            FaceTemplate("a", vector(.98f,.02f)), FaceTemplate("b", vector(0f,1f))), policy)
        assertEquals("a", result.matchedPersonId)
        assertEquals(2, result.candidates.size)
    }
    @Test fun modelMismatchAndZeroVectorAreRejected() {
        assertFailsWith<IllegalArgumentException> { FaceMatcher.search(vector(1f),
            listOf(FaceTemplate("a", vector(1f, model = "v2"))), policy) }
        assertFailsWith<IllegalArgumentException> { FaceMatcher.search(vector(0f),
            listOf(FaceTemplate("a", vector(1f))), policy) }
        assertEquals(FaceMatchStatus.UNKNOWN, FaceMatcher.search(vector(1f), emptyList(), policy).status)
    }
    @Test fun fivePointTransformRecoversScaleRotationAndTranslation() {
        val target = listOf(FacePoint(38.2946f,51.6963f),FacePoint(73.5318f,51.5014f),
            FacePoint(56.0252f,71.7366f),FacePoint(41.5493f,92.3655f),FacePoint(70.7299f,92.2041f))
        val input = target.map { FacePoint(-2*it.y+350,2*it.x+40) }
        val t = FaceAlignment.transform(input)
        input.zip(target).forEach { (p,q) ->
            assertEquals(q.x.toDouble(),(t[0]*p.x+t[1]*p.y+t[2]).toDouble(),.0001)
            assertEquals(q.y.toDouble(),(t[3]*p.x+t[4]*p.y+t[5]).toDouble(),.0001)
        }
        assertFailsWith<IllegalArgumentException> { FaceAlignment.transform(List(5) { FacePoint(0f,0f) }) }
    }
    @Test fun registryPersistsStableIdsAndRefusesBadSamples() {
        val rows = mutableMapOf<String, Pair<RegisteredPerson,List<EmbeddingVector>>>()
        val store = object : FaceRegistryStore {
            override fun people() = rows.values.map { it.first }
            override fun templates(personId: String, modelId: String) = rows[personId]?.second.orEmpty().filter { it.modelId == modelId }
            override fun replace(person: RegisteredPerson, modelId: String, templates: List<EmbeddingVector>) { rows[person.id] = person to templates }
            override fun delete(personId: String) { rows.remove(personId) }
        }
        val registry = FaceRegistry(store,"v1")
        val sample = FaceObservation(DetectedFace(FaceBox(0f,0f,100f,100f),List(5) { FacePoint(20f,20f) }, .99f),
            EmbeddingVector("v1", FloatArray(512) { if(it==0) 1f else 0f }))
        val person = registry.enroll("  A  ", listOf(sample))
        assertEquals("A",person.name)
        assertEquals(person.id,registry.enroll("A",listOf(sample),person.id).id)
        assertEquals(2,store.templates(person.id,"v1").size)
        assertFailsWith<IllegalArgumentException> { registry.enroll("B",listOf(sample.copy(qualityIssues=setOf(FaceQualityIssue.BLURRED)))) }
        assertFailsWith<IllegalArgumentException> { registry.enroll("A",List(11) { sample },person.id) }
        registry.remove(person.id)
        assertTrue(store.people().isEmpty())
    }
}
