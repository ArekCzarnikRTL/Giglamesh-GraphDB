package com.agentwork.graphmesh.storage

import com.agentwork.graphmesh.rdf.NamedGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadStoreLimitTest {

    private val collection = "c1"
    private val rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

    private fun store(): InMemoryQuadStore {
        val s = InMemoryQuadStore()
        for (i in 1..10) {
            s.insert(collection, StoredQuad(
                subject = "http://ex.org/e$i",
                predicate = "http://ex.org/p",
                objectValue = "v$i",
                dataset = NamedGraph.SOURCE,
                objectType = ObjectType.LITERAL
            ))
        }
        s.insert(collection, StoredQuad(
            subject = "http://ex.org/e1",
            predicate = rdfType,
            objectValue = "http://ex.org/Person",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        s.insert(collection, StoredQuad(
            subject = "http://ex.org/e2",
            predicate = rdfType,
            objectValue = "http://ex.org/Org",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        return s
    }

    @Test fun `query respects limit`() {
        val s = store()
        val all = s.query(collection, QuadQuery())
        assertTrue(all.size > 5, "fixture should have >5 quads")
        val limited = s.query(collection, QuadQuery(), limit = 5)
        assertEquals(5, limited.size)
    }

    @Test fun `query without limit returns all`() {
        val s = store()
        val all = s.query(collection, QuadQuery())
        val limited = s.query(collection, QuadQuery(), limit = null)
        assertEquals(all.size, limited.size)
    }

    @Test fun `findSubjects returns distinct subjects matching substring case-insensitive`() {
        val s = store()
        val matches = s.findSubjects(collection, substringMatch = "E1", limit = 10)
        assertTrue("http://ex.org/e1" in matches)
        assertTrue("http://ex.org/e10" in matches)
        assertEquals(matches.size, matches.distinct().size, "must be distinct")
    }

    @Test fun `findSubjects respects limit`() {
        val s = store()
        val matches = s.findSubjects(collection, substringMatch = "ex.org", limit = 3)
        assertEquals(3, matches.size)
    }

    @Test fun `aggregateMetadata returns distinct datasets predicates and entity types`() {
        val s = store()
        val meta = s.aggregateMetadata(collection)
        assertEquals(listOf(NamedGraph.SOURCE), meta.datasets)
        assertTrue("http://ex.org/p" in meta.predicates)
        assertTrue(rdfType in meta.predicates)
        assertEquals(listOf("http://ex.org/Org", "http://ex.org/Person"), meta.entityTypes)
    }
}
