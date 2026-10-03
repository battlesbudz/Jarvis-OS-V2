package com.battlesbudz.jarvis.v2.memory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class MemoryExtractionTest {
    private fun output(source: SourceEpisode, content: String=source.text) = JSONArray().put(JSONObject()
        .put("content",content).put("quote",source.text).put("start",0).put("end",source.text.length)
        .put("category","PREFERENCE").put("sensitivity","NORMAL").put("statementKind","EXPLICIT_STATEMENT")).toString()
    @Test fun structuredOutputRequiresExactUserSourceSupport() {
        val source=extractionSource()
        assertEquals(source.text,GemmaMemoryExtractor.decode(output(source),source).single().content)
        val uncertain=extractionSource("I think I like apricots")
        assertEquals(MemoryStatementKind.TENTATIVE_INFERENCE,GemmaMemoryExtractor.decode(output(uncertain),uncertain).single().statementKind)
        listOf(extractionSource("The assistant says I like apricots"),extractionSource("Imagine I like apricots"),extractionSource("I said \"I like apricots\""),extractionSource("Do I like apricots?")).forEach {
            try { GemmaMemoryExtractor.decode(output(it),it);fail("unsupported attribution") } catch (_:IllegalArgumentException) { }
        }
        try { GemmaMemoryExtractor.decode(output(source,"I own a yacht"),source);fail("invented wording") } catch (_:IllegalArgumentException) { }
        try { GemmaMemoryExtractor.decode(output(source).replace("EXPLICIT_STATEMENT","CONFIRMED"),source);fail("unknown label") } catch (_:IllegalArgumentException) { }
    }
    private fun spanOutput(source: SourceEpisode, quote: String) = JSONArray().put(JSONObject()
        .put("content",quote).put("quote",quote).put("start",source.text.indexOf(quote)).put("end",source.text.indexOf(quote)+quote.length)
        .put("category","FACT").put("sensitivity","NORMAL").put("statementKind","EXPLICIT_STATEMENT")).toString()
    @Test fun innerSpansCannotDiscardAttributionModalityNegationOrOuterQualifiers() {
        val cases = listOf(
            "I think I like apricots" to "I like apricots",
            "I wish I lived in Paris" to "I lived in Paris",
            "My friend believes I live in Boston" to "I live in Boston",
            "I suspect that I have asthma" to "I have asthma",
            "I do not think I like apricots" to "I like apricots",
            "I do not live in Paris" to "live in Paris",
            "I like apricots, probably" to "I like apricots",
            "Please remember that I think I like apricots" to "I like apricots",
            "The claim is \"I live in Boston\"" to "I live in Boston",
            "If I lived there, I like apricots" to "I like apricots"
        )
        cases.forEach { (text,quote) ->
            val source=extractionSource(text)
            val fact=extractionFact(source).copy(content=quote,quote=quote,start=text.indexOf(quote),end=text.indexOf(quote)+quote.length)
            assertFalse(text,MemorySourceSupport.supports(source,fact))
            try { GemmaMemoryExtractor.decode(spanOutput(source,quote),source);fail("inner span: $text") } catch (_:IllegalArgumentException) { }
            try { MemoryAcceptance.records(source,listOf(fact),MemorySnapshot(0,emptyList(),emptyList()),1_000);fail("transaction boundary: $text") } catch (_:IllegalArgumentException) { }
        }
    }
    @Test fun completeTentativeAndNegativeStatementsKeepOriginalWordingAndLabels() {
        listOf("I think I like apricots", "I believe that I have asthma", "My diagnosis might be asthma", "I am not sure I live in Paris").forEach { text ->
            val source=extractionSource(text)
            val facts=GemmaMemoryExtractor.decode(output(source),source)
            val record=MemoryAcceptance.records(source,facts,MemorySnapshot(0,emptyList(),emptyList()),1_000).single()
            assertEquals(text,record.content)
            assertEquals(MemoryStatementKind.TENTATIVE_INFERENCE,record.statementKind)
            assertEquals(50,record.confidence)
        }
        val negative=extractionSource("I do not live in Paris")
        val record=MemoryAcceptance.records(negative,GemmaMemoryExtractor.decode(output(negative),negative),MemorySnapshot(0,emptyList(),emptyList()),1_000).single()
        assertEquals(negative.text,record.content)
        assertEquals(MemoryStatementKind.EXPLICIT_STATEMENT,record.statementKind)
        listOf("I wish I lived in Paris", "My friend believes I live in Boston", "I would live in Paris", "I like apricots. I live in Paris").forEach { text ->
            val source=extractionSource(text)
            assertFalse(text,MemorySourceSupport.supports(source,extractionFact(source)))
        }
        val padded=extractionSource("  I like apricots  ")
        val full=extractionFact(padded).copy(content="I like apricots",quote="I like apricots",start=2,end=padded.text.length-2)
        assertTrue(MemorySourceSupport.supports(padded,full))
        assertFalse(MemorySourceSupport.supports(padded,full.copy(start=0,end=padded.text.length,quote=padded.text)))
        val one=extractionSource()
        val duplicated=JSONArray(output(one)).put(JSONArray(output(one)).getJSONObject(0)).toString()
        try { GemmaMemoryExtractor.decode(duplicated,one);fail("multiple full-source facts") } catch (_:IllegalArgumentException) { }
        try { MemoryAcceptance.records(one,listOf(extractionFact(one),extractionFact(one)),MemorySnapshot(0,emptyList(),emptyList()),1_000);fail("multiple transaction facts") } catch (_:IllegalArgumentException) { }
    }
}
