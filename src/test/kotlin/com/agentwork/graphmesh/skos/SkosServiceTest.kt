package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.RDF_TYPE_URI
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkosServiceTest {

    private val quadStore = InMemoryQuadStore()
    private val service = SkosService(quadStore)
    private val collectionId = "test-col"

    private val schemeUri = "http://example.org/scheme/animals"
    private val catUri = "http://example.org/concept/cat"
    private val dogUri = "http://example.org/concept/dog"
    private val mammalUri = "http://example.org/concept/mammal"
    private val animalUri = "http://example.org/concept/animal"

    @BeforeEach
    fun setUp() {
        quadStore.insert(collectionId, StoredQuad(schemeUri, RDF_TYPE_URI, SkosTypes.CONCEPT_SCHEME, ""))
        quadStore.insert(collectionId, StoredQuad(schemeUri, SkosTypes.PREF_LABEL, "Animals", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(schemeUri, SkosTypes.PREF_LABEL, "Tiere", "", ObjectType.LITERAL, language = "de"))
        quadStore.insert(collectionId, StoredQuad(schemeUri, SkosTypes.HAS_TOP_CONCEPT, animalUri, ""))

        quadStore.insert(collectionId, StoredQuad(animalUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.PREF_LABEL, "Animal", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.TOP_CONCEPT_OF, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.NARROWER, mammalUri, ""))

        quadStore.insert(collectionId, StoredQuad(mammalUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.PREF_LABEL, "Mammal", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.ALT_LABEL, "Saeugetier", "", ObjectType.LITERAL, language = "de"))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.BROADER, animalUri, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.NARROWER, catUri, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.NARROWER, dogUri, ""))

        quadStore.insert(collectionId, StoredQuad(catUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.PREF_LABEL, "Cat", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.BROADER, mammalUri, ""))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.RELATED, dogUri, ""))

        quadStore.insert(collectionId, StoredQuad(dogUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.PREF_LABEL, "Dog", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.SCOPE_NOTE, "A domesticated canine", "", ObjectType.LITERAL))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.BROADER, mammalUri, ""))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.RELATED, catUri, ""))
    }

    @Test
    fun `getConceptSchemes returns all schemes`() {
        val schemes = service.getConceptSchemes(collectionId)
        assertEquals(1, schemes.size)
        assertEquals(schemeUri, schemes[0].uri)
        assertEquals(2, schemes[0].prefLabels.size)
        assertTrue(schemes[0].prefLabels.any { it.value == "Animals" && it.lang == "en" })
        assertTrue(schemes[0].prefLabels.any { it.value == "Tiere" && it.lang == "de" })
        assertEquals(listOf(animalUri), schemes[0].topConcepts)
    }

    @Test
    fun `getConceptSchemes returns empty for unknown collection`() {
        val schemes = service.getConceptSchemes("unknown")
        assertTrue(schemes.isEmpty())
    }

    @Test
    fun `getConcepts returns all concepts in scheme`() {
        val concepts = service.getConcepts(collectionId, schemeUri)
        assertEquals(4, concepts.size)
        val uris = concepts.map { it.uri }.toSet()
        assertTrue(animalUri in uris)
        assertTrue(mammalUri in uris)
        assertTrue(catUri in uris)
        assertTrue(dogUri in uris)
    }

    @Test
    fun `getTopConcepts returns top concepts of scheme`() {
        val tops = service.getTopConcepts(collectionId, schemeUri)
        assertEquals(1, tops.size)
        assertEquals(animalUri, tops[0].uri)
    }

    @Test
    fun `getNarrower returns direct children`() {
        val narrower = service.getNarrower(collectionId, mammalUri)
        assertEquals(2, narrower.size)
        val uris = narrower.map { it.uri }.toSet()
        assertTrue(catUri in uris)
        assertTrue(dogUri in uris)
    }

    @Test
    fun `getBroader returns direct parents`() {
        val broader = service.getBroader(collectionId, catUri)
        assertEquals(1, broader.size)
        assertEquals(mammalUri, broader[0].uri)
    }

    @Test
    fun `getRelated returns related concepts`() {
        val related = service.getRelated(collectionId, catUri)
        assertEquals(1, related.size)
        assertEquals(dogUri, related[0].uri)
    }

    @Test
    fun `getConcept returns full concept with all fields`() {
        val concept = service.getConcept(collectionId, dogUri)
        assertNotNull(concept)
        assertEquals(dogUri, concept.uri)
        assertEquals(1, concept.prefLabels.size)
        assertEquals("Dog", concept.prefLabels[0].value)
        assertEquals(listOf(mammalUri), concept.broader)
        assertEquals(listOf(catUri), concept.related)
        assertEquals("A domesticated canine", concept.scopeNote)
        assertEquals(schemeUri, concept.inScheme)
    }

    @Test
    fun `getConcept returns null for unknown URI`() {
        assertNull(service.getConcept(collectionId, "http://example.org/nonexistent"))
    }

    @Test
    fun `findByLabel matches case-insensitive substring on prefLabel`() {
        val results = service.findByLabel(collectionId, "mam")
        assertEquals(1, results.size)
        assertEquals(mammalUri, results[0].uri)
    }

    @Test
    fun `findByLabel matches on altLabel`() {
        val results = service.findByLabel(collectionId, "saeugetier")
        assertEquals(1, results.size)
        assertEquals(mammalUri, results[0].uri)
    }

    @Test
    fun `findByLabel returns empty for no match`() {
        val results = service.findByLabel(collectionId, "zzzzz")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `countConcepts returns number of concepts in scheme`() {
        val count = service.countConcepts(collectionId, schemeUri)
        assertEquals(4, count)
    }
}
