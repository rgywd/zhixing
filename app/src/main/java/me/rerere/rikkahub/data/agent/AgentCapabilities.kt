package me.rerere.rikkahub.data.agent

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption

object AgentCapabilities {
    val labels = mapOf("agents" to "智能体与技能管理", "search" to "联网搜索", "skills" to "使用技能", "memory" to "记忆",
        "history" to "历史对话", "workspace" to "工作区文件与命令", "knowledge" to "知识库", "mcp" to "已连接的 MCP",
        "agenda" to "待办与计划", "ledger" to "月度收支", "health" to "健康记录", "life" to "生活上下文",
        "calendar" to "系统日历", "location" to "位置与出行", "clipboard" to "剪贴板", "speech" to "朗读",
        "screen" to "屏幕使用时间", "javascript" to "计算脚本")
    val all = setOf("agents", "search", "skills", "memory", "history", "workspace", "knowledge",
        "mcp", "agenda", "ledger", "health", "life", "calendar", "location", "clipboard", "speech", "screen", "javascript")

    fun permits(assistant: Assistant, tool: String): Boolean {
        if (!assistant.isEnabled) return false
        if (tool in setOf("ask_user", "get_time_info")) return true
        val capability = when {
            tool.startsWith("agent_") -> "agents"
            tool.startsWith("mcp__") -> "mcp"
            tool.startsWith("memory_") -> "memory"
            tool.startsWith("workspace_") || tool == "gh" -> "workspace"
            tool.startsWith("knowledge_") || tool.startsWith("assistant_user_prompt_") -> "knowledge"
            tool.startsWith("task_") || tool.startsWith("plan_") -> "agenda"
            tool.startsWith("calendar_") -> "calendar"
            tool in setOf("search_web", "search_images", "scrape_web") -> "search"
            tool in setOf("recent_chats", "conversation_search", "conversation_read") -> "history"
            tool in setOf("get_current_location", "search_nearby_places", "open_navigation") -> "location"
            tool == "monthly_spending_summary" -> "ledger"
            tool == "health_metrics" -> "health"
            tool == "get_life_context" || tool == "inbox_monitor" -> "life"
            tool == "use_skill" -> "skills"
            tool == "clipboard_tool" -> "clipboard"
            tool == "text_to_speech" -> "speech"
            tool == "get_screen_time" -> "screen"
            tool == "eval_javascript" -> "javascript"
            else -> return assistant.capabilities == null
        }
        return assistant.capabilities?.contains(capability) ?: true
    }

    fun localOptions(assistant: Assistant): List<me.rerere.rikkahub.data.ai.tools.local.LocalToolOption> {
        if (assistant.capabilities == null) return assistant.localTools
        return listOfNotNull(
            LocalToolOption.TimeInfo, LocalToolOption.AskUser,
            LocalToolOption.JavascriptEngine.takeIf { "javascript" in assistant.capabilities },
            LocalToolOption.Clipboard.takeIf { "clipboard" in assistant.capabilities },
            LocalToolOption.Tts.takeIf { "speech" in assistant.capabilities },
            LocalToolOption.ScreenTime.takeIf { "screen" in assistant.capabilities },
            LocalToolOption.Calendar.takeIf { "calendar" in assistant.capabilities },
            LocalToolOption.LocationTravel.takeIf { "location" in assistant.capabilities },
        )
    }

    fun canManage(actor: Assistant, target: Assistant): Boolean = actor.id == target.id ||
        (actor.managedBy == null && permits(actor, "agent_config") && target.managedBy == actor.id)
}
