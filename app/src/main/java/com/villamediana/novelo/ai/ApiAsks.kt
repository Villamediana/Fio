package com.villamediana.novelo.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * How to ask a chat API so it takes the request, spending little: JSON mode, little reasoning and
 * a light look at the photo (its text is already read on the phone) first; a server that refuses
 * something gets the request again without it, one thing at a time. What turned out to be refused
 * is remembered per server and model, so the next answers don't pay for the refusal again. The
 * photo itself is always tried: one photo refused says little about the next.
 */
class ApiAsks {
    /** What a request can do without, in the order it is given up. */
    enum class Part { EFFORT, DETAIL, IMAGE, JSON }

    /** One way of asking: [out] = the parts left out. */
    data class Ask(val effort: Boolean, val lowDetail: Boolean, val image: Boolean, val json: Boolean, val out: Set<Part>)

    private val refused = ConcurrentHashMap<String, Set<Part>>()

    /** The ways to try for [key] (server + model), in order; without a photo the photo's parts don't count. */
    fun plan(key: String, withImage: Boolean): List<Ask> {
        val known = refused[key].orEmpty()
        val asks = (0..Part.entries.size).map { k ->
            val out = Part.entries.take(k).toSet() + known
            val image = withImage && Part.IMAGE !in out
            Ask(
                effort = Part.EFFORT !in out,
                lowDetail = image && Part.DETAIL !in out,
                image = image,
                json = Part.JSON !in out,
                out = out,
            )
        }
        // Ways that end up the same request are tried once.
        return asks.distinctBy { listOf(it.effort, it.lowDetail, it.image, it.json) }
    }

    /**
     * [worked] went through right after [before] was refused: what it newly left out was the
     * problem — kept for [key]. (Only what mattered for this request: the detail only with a photo,
     * the photo never.)
     */
    fun learn(key: String, before: Ask, worked: Ask) {
        val dropped = (worked.out - before.out).filter { p ->
            when (p) {
                Part.IMAGE -> false
                Part.DETAIL -> before.image && worked.image
                else -> true
            }
        }
        // The last thing given up is what made it work; anything else given up with it is tried again next time.
        val culprit = dropped.lastOrNull() ?: return
        refused.merge(key, setOf(culprit)) { a, b -> a + b }
    }

    fun refusedFor(key: String): Set<Part> = refused[key].orEmpty()
}
