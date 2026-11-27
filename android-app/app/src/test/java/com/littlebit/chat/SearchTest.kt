package com.littlebit.chat

import com.littlebit.chat.data.SettingsStore
import com.littlebit.chat.web.SearchPrompts
import com.littlebit.chat.web.SearchQueryBuilder
import org.junit.Assert.*
import org.junit.Test

class SearchTest {

    @Test fun shortAndReferentialMessagesAreFollowUps() {
        assertTrue(SearchQueryBuilder.isFollowUp("now you can"))
        assertTrue(SearchQueryBuilder.isFollowUp("try again"))
        assertTrue(SearchQueryBuilder.isFollowUp("search it"))
        assertTrue(SearchQueryBuilder.isFollowUp("check now"))
        assertTrue(SearchQueryBuilder.isFollowUp("what about that"))
        assertTrue(SearchQueryBuilder.isFollowUp("do it"))
        assertTrue(SearchQueryBuilder.isFollowUp("and now"))
        assertTrue(SearchQueryBuilder.isFollowUp("search this again"))
        assertFalse(SearchQueryBuilder.isFollowUp("Explain the water cycle in detail with a diagram"))
    }

    @Test fun shortStandaloneSearchesAreNotFollowUps() {
        assertFalse(SearchQueryBuilder.isFollowUp("weather in Varanasi"))
        assertFalse(SearchQueryBuilder.isFollowUp("Bitcoin price today"))
        assertFalse(SearchQueryBuilder.isFollowUp("latest news"))
        assertFalse(SearchQueryBuilder.isFollowUp("best gaming laptop"))
    }

    @Test fun standaloneShortSearchKeepsItsOwnQuery() {
        val history = listOf("Explain photosynthesis in detail for a school project")
        assertEquals("weather in Varanasi", SearchQueryBuilder.build("weather in Varanasi", history))
        assertEquals("Bitcoin price today", SearchQueryBuilder.build("Bitcoin price today", history))
    }

    @Test fun explicitFollowUpStillBorrowsPreviousQuery() {
        val query = SearchQueryBuilder.build("now you can", listOf("weather in Varanasi"))
        assertTrue(query.startsWith("weather in Varanasi"))
        assertTrue(query.endsWith("now you can"))
    }

    @Test fun followUpCombinesWithMostRecentSubstantiveQuestion() {
        val history = listOf(
            "hi",
            "What is the capital of France and why does it matter?",
            "thanks",
        )
        val query = SearchQueryBuilder.build("now you can", history)
        assertTrue(query.startsWith("What is the capital of France and why does it matter?"))
        assertTrue(query.endsWith("now you can"))
    }

    @Test fun substantiveQuestionIsNotRewritten() {
        val history = listOf("What is the capital of France and why does it matter?")
        val current = "Who won the 2026 World Cup final?"
        assertEquals(current, SearchQueryBuilder.build(current, history))
    }

    @Test fun queryIsBounded() {
        assertEquals(100, SearchQueryBuilder.build("x".repeat(500), emptyList(), maxChars = 100).length)
        assertEquals("", SearchQueryBuilder.build("   ", listOf("previous question with enough words")))
    }

    @Test fun systemPromptSwitchesBetweenOfflineAndSearchGrounding() {
        val offline = SearchPrompts.forMode(SettingsStore.DEFAULT_SYSTEM_PROMPT, grounded = false)
        assertTrue(offline.contains(SettingsStore.OFFLINE))
        assertFalse(offline.contains(SearchPrompts.SEARCH_GROUNDED))

        val online = SearchPrompts.forMode(SettingsStore.DEFAULT_SYSTEM_PROMPT, grounded = true)
        assertTrue(online.contains(SearchPrompts.SEARCH_GROUNDED))
        assertFalse(online.contains(SettingsStore.OFFLINE))

        assertEquals("Base", SearchPrompts.basePrompt("Base\n\n" + SettingsStore.OFFLINE))
        assertEquals("Base", SearchPrompts.basePrompt("Base\n" + SearchPrompts.SEARCH_GROUNDED))
    }

    @Test fun blankSystemPromptStaysBlankOffline() {
        assertEquals("", SearchPrompts.forMode("", grounded = false))
        assertEquals("", SearchPrompts.forMode("   ", grounded = false))
    }

    @Test fun blankSystemPromptStillGroundedWhenSearching() {
        assertEquals(SearchPrompts.SEARCH_GROUNDED, SearchPrompts.forMode("", grounded = true))
    }

    @Test fun offlineClauseNoLongerRefusesStaticQuestions() {
        val system = SearchPrompts.forMode("You are helpful.", grounded = false)
        assertTrue(system.contains("training knowledge"))
        assertFalse(system.contains("You cannot search the web"))
        assertTrue(system.contains(SettingsStore.OFFLINE))
    }

    @Test fun customPersonaKeepsItsOwnText() {
        val system = SearchPrompts.forMode("You are a physics tutor.", grounded = false)
        assertTrue(system.startsWith("You are a physics tutor."))
        assertTrue(system.contains(SettingsStore.OFFLINE))
    }

    @Test fun pinnedNotesAreAppendedAsUserContext() {
        val withNotes = SearchPrompts.withNotes("System", listOf("Project: LittleBit", "Answer in metric"))
        assertTrue(withNotes.startsWith("System"))
        assertTrue(withNotes.contains("Pinned context notes"))
        assertTrue(withNotes.contains("- Project: LittleBit"))
        assertEquals("System", SearchPrompts.withNotes("System", emptyList()))
    }
}
