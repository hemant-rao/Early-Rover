package com.example.alarm.opengl

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Loads and serves scientifically accurate facts about Solar System bodies.
 *
 * Facts are bundled as JSON in assets/planet_facts.json. The file is parsed
 * lazily on first access and cached for the lifetime of the process. Each call
 * to [nextFact] returns a different fact than the previous one for that body
 * (when more than one fact exists), so repeated taps keep showing new facts.
 */
object PlanetFacts {

    data class BodyFacts(val emoji: String, val facts: List<String>)

    private const val ASSET_NAME = "planet_facts.json"

    // Parsed JSON cache. null = not yet loaded, empty = load failed.
    @Volatile
    private var cache: Map<String, BodyFacts>? = null

    // Last-shown fact index per body, used to avoid immediate repeats.
    private val lastIndex = mutableMapOf<String, Int>()

    private fun load(context: Context): Map<String, BodyFacts> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val result = mutableMapOf<String, BodyFacts>()
            try {
                val json = context.applicationContext.assets.open(ASSET_NAME)
                    .bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key) ?: continue
                    val emoji = obj.optString("emoji", "")
                    val factsArray = obj.optJSONArray("facts")
                    val facts = mutableListOf<String>()
                    if (factsArray != null) {
                        for (i in 0 until factsArray.length()) {
                            val fact = factsArray.optString(i, "")
                            if (fact.isNotBlank()) facts.add(fact)
                        }
                    }
                    result[key] = BodyFacts(emoji, facts)
                }
            } catch (e: Exception) {
                // IO or parse failure: fall back to an empty cache so callers
                // get graceful empty results instead of crashing.
            }
            cache = result
            return result
        }
    }

    /** Resolves the JSON key for [body] using a case-insensitive match. */
    private fun resolve(map: Map<String, BodyFacts>, body: String): String? {
        if (map.containsKey(body)) return body
        return map.keys.firstOrNull {
            it.equals(body, ignoreCase = true)
        }
    }

    /** Returns the emoji for [body], or an empty string if unknown. */
    fun emojiFor(context: Context, body: String): String {
        return try {
            val map = load(context)
            val key = resolve(map, body) ?: return ""
            map[key]?.emoji ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Returns the next fact for [body], guaranteed to differ from the previously
     * returned fact when the body has more than one fact. Returns an empty
     * string if the body is unknown or has no facts.
     */
    fun nextFact(context: Context, body: String): String {
        return try {
            val map = load(context)
            val key = resolve(map, body) ?: return ""
            val facts = map[key]?.facts ?: return ""
            if (facts.isEmpty()) return ""
            if (facts.size == 1) {
                lastIndex[key] = 0
                return facts[0]
            }
            val previous = lastIndex[key]
            var index = (facts.indices).random()
            while (index == previous) {
                index = (facts.indices).random()
            }
            lastIndex[key] = index
            facts[index]
        } catch (e: Exception) {
            ""
        }
    }
}
