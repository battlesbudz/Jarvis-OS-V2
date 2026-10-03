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
}
