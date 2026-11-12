package com.villamediana.novelo.ai

import com.villamediana.novelo.ai.ApiAsks.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiAsksTest {
    private val key = "https://x/v1|gpt-x"

    @Test fun everythingFirstThenOneThingLessAtATime() {
        val plan = ApiAsks().plan(key, withImage = true)
        assertEquals(listOf(emptySet(), setOf(Part.EFFORT), setOf(Part.EFFORT, Part.DETAIL), setOf(Part.EFFORT, Part.DETAIL, Part.IMAGE), Part.entries.toSet()), plan.map { it.out })
        val first = plan.first()
        assertTrue(first.effort && first.lowDetail && first.image && first.json)
        // Without a photo the photo's steps are the same request: tried once.
        assertEquals(listOf(emptySet(), setOf(Part.EFFORT), Part.entries.toSet()), ApiAsks().plan(key, withImage = false).map { it.out })
    }

    @Test fun aRefusalIsPaidOnceForThatServerAndModel() {
        val asks = ApiAsks()
        val plan = asks.plan(key, withImage = false)
        // The reasoning level was refused, the next way went through.
        asks.learn(key, before = plan[0], worked = plan[1])
        assertEquals(setOf(Part.EFFORT), asks.refusedFor(key))
        assertTrue(asks.plan(key, withImage = false).first().let { !it.effort && it.json })
        // A photo still goes with the light look; another server/model starts from everything.
        assertTrue(asks.plan(key, withImage = true).first().let { it.image && it.lowDetail && !it.effort })
        assertTrue(asks.plan("https://y/v1|other", withImage = false).first().effort)
    }

    @Test fun onlyWhatMadeItWorkIsKeptAndNeverThePhoto() {
        val asks = ApiAsks()
        val plan = asks.plan(key, withImage = true)
        // Refused until the light look was given up (the reasoning level was given up on the way too):
        // only the detail is kept; the reasoning level is tried again next time.
        asks.learn(key, before = plan[1], worked = plan[2])
        assertEquals(setOf(Part.DETAIL), asks.refusedFor(key))
        // A photo refused (a model that doesn't see, or that one photo): never kept.
        asks.learn(key, before = plan[2], worked = plan[3])
        assertEquals(setOf(Part.DETAIL), asks.refusedFor(key))
        val again = asks.plan(key, withImage = true).first()
        assertTrue(again.image && !again.lowDetail && again.effort)
    }
}
