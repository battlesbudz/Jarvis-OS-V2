package com.battlesbudz.jarvis.v2.ai

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class ReferenceGrounding(val context: String, val sources: List<String>)

class ReferenceGroundingClient {
    private companion object {
        const val MAX_EVIDENCE_CHARS = 4_500
        const val MAX_EXTRACT_CHARS = 1_800
    }
    fun buildLookupQuery(
        currentPrompt: String,
        previousUserQuestion: String?,
        previousAssistantMessage: String? = null
    ): String? {
        val normalized = currentPrompt.lowercase().trim()
        val confirmation = normalized in setOf(
            "yes", "yes please", "sure", "okay", "ok", "go ahead", "do it"
        )
        val offeredLookup = previousAssistantMessage?.lowercase()?.let {
            it.contains("search wikipedia") ||
                it.contains("search wikimedia") ||
                it.contains("search wikidata")
        } == true
        val explicit = isExplicitLookupRequest(currentPrompt) ||
            (confirmation && offeredLookup)
        if (!explicit && !shouldAutomaticallyLookup(currentPrompt)) return null
        val previous = previousUserQuestion
            ?.takeIf { it.isNotBlank() && it != currentPrompt }
        return if (explicit) {
            listOfNotNull(previous, currentPrompt)
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
        } else {
            currentPrompt.takeIf { it.isNotBlank() }
        }
    }

    private fun shouldAutomaticallyLookup(query: String): Boolean {
        val text = query.lowercase().trim()
        if (text.isBlank()) return false
        val excluded = listOf(
            "tell me a joke", "make me laugh", "tell me a story",
            "write a story", "poem", "pretend", "imagine", "roleplay",
            "what do you think", "should i", "can you help me", "what can you do",
            "what is this"
        )
        if (excluded.any(text::contains)) return false

        val factualTerms = listOf(
            "history", "historical", "biography", "born", "died", "founded",
            "author", "book", "law", "legal", "legislation", "president",
            "war", "attack", "event", "evidence", "fact", "scientist",
            "company", "worked", "difference between", "when did", "where did",
            "who was", "what happened", "how did"
        )
        if (factualTerms.any(text::contains)) return true

        val asksKnowledge = Regex(
            "^(who|what|when|where|why|which)\\b"
        ).containsMatchIn(text)
        val hasEntityShape = Regex(
            "\\b[A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,})+\\b"
        ).containsMatchIn(query) || Regex("\\b[A-Z]{2,}\\b").containsMatchIn(query)
        val asksAboutNamedEntity = text.contains("tell me about") && hasEntityShape
        return (asksKnowledge || asksAboutNamedEntity) && hasEntityShape
    }

    fun isExplicitLookupRequest(query: String): Boolean {
        val text = query.lowercase()
        return listOf(
            "search wikipedia", "search wikimedia", "search wikidata",
            "look up on wikipedia", "look it up on wikipedia",
            "check wikipedia", "verify on wikipedia", "use wikipedia",
            "use wikimedia", "use wikidata", "search the web",
            "look it up", "look this up", "verify this", "check the facts"
        ).any(text::contains)
    }

    suspend fun fetchIfRequested(query: String): ReferenceGrounding? {
        val mediaWiki = requestMediaWiki(query)
        val wikidata = requestWikidata(query)
        val sources = (mediaWiki.sources + wikidata.sources).distinct()
        val evidence = (mediaWiki.context + wikidata.context).trim().take(MAX_EVIDENCE_CHARS)
        if (evidence.isBlank()) return null
        return ReferenceGrounding(
            context = """
                Reference evidence retrieved for the current question:
                Use this evidence as the factual basis for your answer. Distinguish
                verified information from disputed claims. Do not invent details not
                supported by the evidence. If the evidence is insufficient, say so.
                
                $evidence
            """.trimIndent(),
            sources = sources
        )
    }

    private fun requestMediaWiki(query: String): ReferenceGrounding =
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = URL(
                "https://en.wikipedia.org/w/api.php?action=query&list=search" +
                    "&srsearch=$encoded&srnamespace=0&srlimit=3&format=json"
            )
            val search = get(searchUrl).optJSONObject("query")?.optJSONArray("search")
            val parts = mutableListOf<String>()
            val sources = mutableListOf<String>()
            if (search != null) {
                for (index in 0 until minOf(search.length(), 3)) {
                    val item = search.optJSONObject(index) ?: continue
                    val title = item.optString("title")
                    if (title.isBlank()) continue
                    val extract = requestPageExtract(title)
                    val text = extract.take(MAX_EXTRACT_CHARS).ifBlank {
                        item.optString("snippet").replace(Regex("<[^>]+>"), "").trim()
                    }
                    if (text.isNotBlank()) parts += "Wikipedia — $title: $text"
                    sources += "https://en.wikipedia.org/wiki/" + title.replace(" ", "_")
                }
            }
            ReferenceGrounding(parts.joinToString("\n"), sources)
        }.getOrElse { ReferenceGrounding("", emptyList()) }

    private fun requestPageExtract(title: String): String =
        runCatching {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val url = URL(
                "https://en.wikipedia.org/w/api.php?action=query&prop=extracts" +
                    "&explaintext=1&exintro=1&titles=$encodedTitle&format=json"
            )
            val pages = get(url).optJSONObject("query")?.optJSONObject("pages")
            pages?.keys()?.asSequence()?.mapNotNull { key ->
                pages.optJSONObject(key)?.optString("extract")
            }?.firstOrNull { it.isNotBlank() }.orEmpty()
        }.getOrDefault("")

    private fun requestWikidata(query: String): ReferenceGrounding =
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL(
                "https://www.wikidata.org/w/api.php?action=wbsearchentities" +
                    "&search=$encoded&language=en&format=json&limit=3"
            )
            val json = get(url)
            val search = json.optJSONArray("search")
            val parts = mutableListOf<String>()
            val sources = mutableListOf<String>()
            if (search != null) {
                for (index in 0 until minOf(search.length(), 3)) {
                    val item = search.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val label = item.optString("label")
                    val description = item.optString("description")
                    if (id.isBlank()) continue
                    parts += "Wikidata — $label ($id): $description"
                    sources += "https://www.wikidata.org/wiki/$id"
                }
            }
            ReferenceGrounding(parts.joinToString("\n"), sources)
        }.getOrElse { ReferenceGrounding("", emptyList()) }

    private fun get(url: URL): org.json.JSONObject {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 4_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("User-Agent", "JarvisOSV2/0.1 (local assistant)")
        return connection.inputStream.bufferedReader().use { org.json.JSONObject(it.readText()) }
            .also { connection.disconnect() }
    }
}
