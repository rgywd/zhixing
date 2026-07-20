package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user questions when you need clarification, missing details, or a decision before proceeding. Rules:
        - Ask at most 7 questions per call. Prefer fewer, well-chosen questions.
        - Each question may provide 2-4 concrete, mutually exclusive options, ordered from most to least likely. Options must be short, specific, and cover the realistic choices. Never add a generic "Other" option.
        - The UI automatically appends "Type something" (free-text answer) and "Chat about this" (mark this question for discussion) to every question; never add such options yourself.
        - Responses are returned as JSON: {"answers": {<questionId>: <answer>}, "discuss": [<questionId>, ...]}. Ids in "discuss" mean the user is unsure and wants to talk that point through instead of picking an answer.
        - For every id in "discuss": do NOT immediately re-ask. Discuss the topic in your normal reply — lay out the trade-offs, state your recommendation, and converge on a concrete decision together with the user. Only call ask_user again for such a topic once the discussion has produced clear candidate choices that merely need confirmation.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("maxItems", 7)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("header", buildJsonObject {
                                put("type", "string")
                                put("description", "Short tab label for this question, max 12 characters (e.g. 'Database', 'UI style')")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of suggested options for the user to choose from"
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
