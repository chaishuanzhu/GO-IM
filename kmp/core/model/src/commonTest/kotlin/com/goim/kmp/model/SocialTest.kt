package com.goim.kmp.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SocialTest {
    @Test
    fun friendTitleShowsRemarkThenNickname() {
        assertEquals("老王(Bob)", friendTitle("老王", "Bob"))
        assertEquals("Bob", friendTitle("", "Bob"))
        assertEquals("Bob", friendTitle("  ", "Bob"))
    }

    @Test
    fun sortNamePrefersRemark() {
        assertEquals("老王", Friend("bob", "Bob", remark = "老王").sortName)
        assertEquals("Bob", Friend("bob", "Bob").sortName)
    }
}
