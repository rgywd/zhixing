package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaDrawerInteractionTest {
    @Test
    fun `top bar actions expand when at least 360dp is available`() {
        assertFalse(shouldExpandChatTopBarActions(359.dp))
        assertTrue(shouldExpandChatTopBarActions(360.dp))
        assertTrue(shouldExpandChatTopBarActions(411.dp))
    }

    @Test
    fun `right drawer uses material motion thresholds without the old trigger gesture`() {
        val source = agendaDrawerSource()

        assertTrue(source.contains("ModalDrawerSheet("))
        assertTrue(source.contains(".agendaDrawerDragGesture("))
        assertTrue(source.contains("AGENDA_DRAWER_POSITIONAL_THRESHOLD = 0.5f"))
        assertTrue(source.contains("AGENDA_DRAWER_ANIMATION_DURATION_MS = 256"))
        assertTrue(source.contains("AGENDA_DRAWER_VELOCITY_THRESHOLD = 400.dp"))
        assertTrue(source.contains("AGENDA_DRAWER_EDGE_ZONE = 32.dp"))
        assertFalse(source.contains("agendaSwipeGesture("))
        assertFalse(source.contains("AnimatedVisibility("))
        assertFalse(source.contains("threshold = 64.dp"))
    }

    @Test
    fun `slow drag settles at the halfway position`() {
        assertFalse(shouldOpenAgendaDrawer(openFraction = 0.49f, velocityX = 0f, velocityThresholdPx = 400f))
        assertTrue(shouldOpenAgendaDrawer(openFraction = 0.5f, velocityX = 0f, velocityThresholdPx = 400f))
    }

    @Test
    fun `fast fling direction overrides the halfway position`() {
        assertTrue(shouldOpenAgendaDrawer(openFraction = 0.1f, velocityX = -401f, velocityThresholdPx = 400f))
        assertFalse(shouldOpenAgendaDrawer(openFraction = 0.9f, velocityX = 401f, velocityThresholdPx = 400f))
    }

    @Test
    fun `closed right drawer claims only leftward horizontal drags`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
                startedInEdgeZone = true,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
                startedInEdgeZone = true,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -10f,
                totalY = 20f,
                touchSlop = 8f,
                startedInEdgeZone = true,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.WAIT,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -6f,
                totalY = 1f,
                touchSlop = 8f,
                startedInEdgeZone = true,
            ),
        )
    }

    @Test
    fun `closed right drawer ignores leftward drags starting outside the edge zone`() {
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
                startedInEdgeZone = false,
            ),
        )
    }

    @Test
    fun `visible right drawer follows drags from anywhere including outside the edge zone`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = false,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
                startedInEdgeZone = false,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = false,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
                startedInEdgeZone = false,
            ),
        )
    }

    @Test
    fun `visible right drawer keeps ownership of horizontal reversal`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = true,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = true,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun `closed right drawer yields horizontal drags blocked by table or left drawer`() {
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = true,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun `disabled opening gesture keeps a closed right drawer closed`() {
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
                openingGestureEnabled = false,
            ),
        )
    }

    @Test
    fun `disabled opening gesture still lets a visible right drawer close`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = false,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
                openingGestureEnabled = false,
            ),
        )
    }

    @Test
    fun `visible right drawer keeps ownership even when content gesture is blocked`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = true,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun `life overview greeting follows the time of day`() {
        assertTrue(lifeOverviewGreeting(7) == "早上好")
        assertTrue(lifeOverviewGreeting(12) == "中午好")
        assertTrue(lifeOverviewGreeting(16) == "下午好")
        assertTrue(lifeOverviewGreeting(21) == "晚上好")
        assertTrue(lifeOverviewGreeting(2) == "夜深了")
    }

    @Test
    fun `drawer gesture exclusion modifier is shared in the context file`() {
        val source = drawerGestureExclusionSource()
        assertTrue(source.contains("internal fun Modifier.excludeDrawerGesturesWhilePressed("))
        assertTrue(source.contains("LocalDrawerGestureExclusion"))
    }

    @Test
    fun `data table does not keep a private copy of the exclusion modifier`() {
        val source = dataTableSource()
        assertFalse(source.contains("private fun Modifier.excludeDrawerGesturesWhilePressed("))
        assertTrue(source.contains(".excludeDrawerGesturesWhilePressed("))
        assertTrue(source.contains("import me.rerere.rikkahub.ui.context.excludeDrawerGesturesWhilePressed"))
    }

    @Test
    fun `horizontally scrollable markdown content excludes drawer gestures`() {
        val expectations = listOf(
            "HighlightCodeBlock.kt" to ".excludeDrawerGesturesWhilePressed(drawerGestureExclusion)",
            "MathBlock.kt" to ".excludeDrawerGesturesWhilePressed(drawerGestureExclusion)",
            "DiffView.kt" to ".excludeDrawerGesturesWhilePressed(drawerGestureExclusion)",
        )
        expectations.forEach { (fileName, marker) ->
            val source = sourceFile("ui/components/richtext/$fileName")
            assertTrue("$fileName should exclude drawer gestures", source.contains(marker))
            assertTrue("$fileName should apply the exclusion before horizontalScroll", source.indexOf(marker) < source.indexOf("horizontalScroll("))
        }
    }

    @Test
    fun `chat suggestions row and input toolbar exclude drawer gestures`() {
        val chatList = sourceFile("ui/pages/chat/ChatList.kt")
        assertTrue(chatList.contains("excludeDrawerGesturesWhilePressed(drawerGestureExclusion)"))
        // ChatSuggestionsRow 是 LazyRow,源码里没有 horizontalScroll 字样,只断言调用点存在
        assertTrue(chatList.indexOf("excludeDrawerGesturesWhilePressed(") > chatList.indexOf("ChatSuggestionsRow("))

        val chatInput = sourceFile("ui/components/ai/ChatInput.kt")
        assertTrue(chatInput.contains("excludeDrawerGesturesWhilePressed(drawerGestureExclusion)"))
        assertTrue(chatInput.indexOf("excludeDrawerGesturesWhilePressed(") < chatInput.indexOf("horizontalScroll("))
    }

    @Test
    fun `mermaid webview excludes drawer gestures`() {
        val source = sourceFile("ui/components/richtext/Mermaid.kt")
        assertTrue(source.contains(".excludeDrawerGesturesWhilePressed(drawerGestureExclusion)"))
    }

    private fun agendaDrawerSource(): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val relativePath = "src/main/java/me/rerere/rikkahub/ui/pages/chat/AgendaDrawer.kt"
        val source = sequenceOf(
            File(workingDirectory, relativePath),
            File(workingDirectory, "app/$relativePath"),
        ).firstOrNull(File::isFile)
        checkNotNull(source) { "Unable to locate AgendaDrawer.kt from $workingDirectory" }
        return source.readText()
    }

    private fun drawerGestureExclusionSource(): String = sourceFile("ui/context/DrawerGestureExclusion.kt")

    private fun dataTableSource(): String = sourceFile("ui/components/table/DataTable.kt")

    private fun sourceFile(relative: String): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val path = "src/main/java/me/rerere/rikkahub/$relative"
        val file = sequenceOf(
            File(workingDirectory, path),
            File(workingDirectory, "app/$path"),
        ).firstOrNull(File::isFile)
        checkNotNull(file) { "Unable to locate $relative from $workingDirectory" }
        return file.readText()
    }
}
