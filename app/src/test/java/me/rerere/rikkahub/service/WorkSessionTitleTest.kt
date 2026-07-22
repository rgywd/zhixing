package me.rerere.rikkahub.service

import me.rerere.rikkahub.ui.pages.work.fallbackWorkSessionTitle
import me.rerere.rikkahub.ui.pages.work.phoneWorkSessionViewModelKey
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkSessionTitleTest {
    @Test
    fun `normalizes first non-empty generated title line`() {
        assertEquals("修复 Work 会话标题", normalizeWorkSessionTitle("\n“修复 Work 会话标题”\n额外说明"))
    }

    @Test
    fun `rejects empty generated title`() {
        assertNull(normalizeWorkSessionTitle(" \n\t"))
    }

    @Test
    fun `falls back to compact first message line`() {
        assertEquals(
            "修复 Work 首页的会话辨识问题",
            fallbackWorkSessionTitle("#   修复   Work 首页的会话辨识问题\n补充内容", "zhixing"),
        )
    }

    @Test
    fun `falls back to repository name for image-only session`() {
        assertEquals("zhixing", fallbackWorkSessionTitle("", "zhixing"))
    }

    @Test
    fun `work sessions use different view model keys`() {
        assertNotEquals(
            phoneWorkSessionViewModelKey("work-first"),
            phoneWorkSessionViewModelKey("work-second"),
        )
        assertEquals("phone-work-session:new", phoneWorkSessionViewModelKey(""))
    }
}
