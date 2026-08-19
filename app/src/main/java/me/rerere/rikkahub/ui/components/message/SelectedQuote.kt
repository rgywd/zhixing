package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.text.AnnotatedString
import kotlinx.datetime.LocalDateTime

data class SelectedQuote(
    val text: String,
    val modelName: String?,
    val createdAt: LocalDateTime,
)

internal fun buildSelectedQuoteText(selectedTexts: List<AnnotatedString>): String = selectedTexts
    .joinToString(separator = "\n") { it.text }
    .trim()
