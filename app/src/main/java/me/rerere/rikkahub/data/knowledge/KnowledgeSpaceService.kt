package me.rerere.rikkahub.data.knowledge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.KnowledgeImportResult
import java.io.File
import java.io.InputStream
import kotlin.uuid.Uuid

class KnowledgeSpaceService(
    private val context: Context,
    private val repository: WorkspaceRepository,
) {
    suspend fun importDocument(
        workspaceId: String,
        fileName: String,
        mimeType: String?,
        inputStream: InputStream,
    ): KnowledgeImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "knowledge_import").apply { mkdirs() }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val tempFile = File(tempDir, "${Uuid.random()}${extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()}")
        try {
            inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            val normalized = normalizeKnowledgeDocument(tempFile, extension, mimeType)
            tempFile.inputStream().use { input ->
                repository.importKnowledgeSource(
                    id = workspaceId,
                    fileName = fileName,
                    inputStream = input,
                    normalizedText = normalized,
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    suspend fun importUpload(
        workspaceId: String,
        uploadName: String,
        mimeType: String? = null,
    ): KnowledgeImportResult {
        val uploadDir = File(context.filesDir, FileFolders.UPLOAD).canonicalFile
        val upload = File(uploadDir, uploadName).canonicalFile
        require(upload.parentFile == uploadDir && upload.isFile) { "Upload file not found: $uploadName" }
        return upload.inputStream().use { input ->
            importDocument(workspaceId, upload.name, mimeType, input)
        }
    }

}

internal fun normalizeKnowledgeDocument(file: File, extension: String, mimeType: String?): String? {
    if (file.length() > MAX_NORMALIZE_BYTES) return null
    return when {
        mimeType == "application/pdf" || extension == "pdf" -> PdfParser.parserPdf(file)
        mimeType == DOCX_MIME || extension == "docx" -> DocxParser.parse(file)
        mimeType == PPTX_MIME || extension == "pptx" -> PptxParser.parse(file)
        mimeType == "application/epub+zip" || extension == "epub" -> EpubParser.parse(file)
        extension in TEXT_EXTENSIONS || mimeType?.startsWith("text/") == true -> file.readText()
        else -> null
    }
}

private const val MAX_NORMALIZE_BYTES = 32L * 1024 * 1024
private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm",
    "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "css", "scss", "sql", "sh",
    "yaml", "yml", "toml", "ini", "properties", "log",
)
