package me.rerere.rikkahub.data.workflow

private val headTag = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val htmlTag = Regex("<html(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)

/**
 * 给 Claude 生成的 HTML 加一层本地只读安全策略。WebView 侧还会关闭 JavaScript、文件访问和网络，
 * 这里的 CSP 是第二道防线；报告内容只允许内联样式和 data 图片。
 */
internal fun secureHtmlReportDocument(title: String, html: String): String {
    val securityHead = """
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
          body { margin: 0; padding: 20px; line-height: 1.55; overflow-wrap: anywhere; }
          img, table, pre { max-width: 100%; }
          pre { overflow-x: auto; }
        </style>
    """.trimIndent()
    val head = headTag.find(html)
    if (head != null) return html.replaceRange(head.range.last + 1, head.range.last + 1, securityHead)
    val root = htmlTag.find(html)
    if (root != null) {
        val insertion = "<head><title>${title.escapeHtml()}</title>$securityHead</head>"
        return html.replaceRange(root.range.last + 1, root.range.last + 1, insertion)
    }
    return """
        <!doctype html>
        <html><head><title>${title.escapeHtml()}</title>$securityHead</head>
        <body>$html</body></html>
    """.trimIndent()
}

private fun String.escapeHtml(): String = buildString(length) {
    this@escapeHtml.forEach { char ->
        append(
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> char
            }
        )
    }
}
