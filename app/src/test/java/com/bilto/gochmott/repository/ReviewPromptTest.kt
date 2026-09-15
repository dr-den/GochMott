package com.bilto.gochmott.repository

import com.bilto.gochmott.repository.ReviewPrompt.Companion.MIN_ENTRIES
import com.bilto.gochmott.repository.ReviewPrompt.Companion.MIN_USAGE_MS
import com.bilto.gochmott.repository.ReviewPrompt.Companion.REASK_AFTER_MS
import com.bilto.gochmott.repository.ReviewPrompt.Companion.isDue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Когда пора показывать окно оценки. */
class ReviewPromptTest {

    private val start = 1_000_000_000_000L
    private val later = start + MIN_USAGE_MS

    @Test
    fun `опытному пользователю, которого не спрашивали, — пора`() {
        assertTrue(isDue(MIN_ENTRIES, start, askedAt = 0L, now = later))
    }

    @Test
    fun `мало открытых статей — рано`() {
        assertFalse(isDue(MIN_ENTRIES - 1, start, askedAt = 0L, now = later))
    }

    @Test
    fun `первые дни — рано, сколько бы статей ни открыл`() {
        assertFalse(isDue(MIN_ENTRIES * 10, start, askedAt = 0L, now = later - 1))
    }

    @Test
    fun `статей не открывал — не спрашиваем`() {
        assertFalse(isDue(MIN_ENTRIES, firstEntryOpenedAt = 0L, askedAt = 0L, now = later))
    }

    @Test
    fun `уже спрашивали — ждём срок до повтора`() {
        val asked = later
        assertFalse(isDue(MIN_ENTRIES, start, asked, now = asked + REASK_AFTER_MS - 1))
        assertTrue(isDue(MIN_ENTRIES, start, asked, now = asked + REASK_AFTER_MS))
    }
}
