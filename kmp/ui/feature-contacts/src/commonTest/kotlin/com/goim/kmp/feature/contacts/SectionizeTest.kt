package com.goim.kmp.feature.contacts

import com.goim.kmp.model.Friend
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionizeTest {
    @Test
    fun groupsByInitialWithHashLast() {
        val sections = ContactsViewModel.sectionize(
            listOf(Friend("bob"), Friend("张三"), Friend("alice"), Friend("Amy"), Friend("42")),
        )

        assertEquals(listOf("A", "B", "#"), sections.map { it.letter })
        assertEquals(listOf("alice", "Amy"), sections[0].friends.map { it.uid })
        assertEquals(setOf("张三", "42"), sections[2].friends.map { it.uid }.toSet())
    }
}
