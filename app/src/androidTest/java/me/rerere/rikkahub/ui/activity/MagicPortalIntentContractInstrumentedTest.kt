package me.rerere.rikkahub.ui.activity

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayList
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MagicPortalIntentContractInstrumentedTest {
    @Test
    fun readsTextAndSingleImageFromStandardSendExtras() {
        val image = Uri.parse("content://magic-portal/images/1")
        val candidate = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "分析这张图")
            putExtra(Intent.EXTRA_STREAM, image)
        }.toMagicPortalCandidate()

        assertEquals(Intent.ACTION_SEND, candidate.action)
        assertEquals("image/png", candidate.declaredMimeType)
        assertEquals("分析这张图", candidate.text)
        assertEquals(listOf(image.toString()), candidate.streamUris)
    }

    @Test
    fun readsMultipleImagesAndDeduplicatesClipDataUris() {
        val first = Uri.parse("content://magic-portal/images/1")
        val second = Uri.parse("content://magic-portal/images/2")
        val candidate = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(listOf(first, second)))
            clipData = ClipData.newRawUri("duplicate", first)
        }.toMagicPortalCandidate()

        assertEquals(listOf(first.toString(), second.toString()), candidate.streamUris)
    }
}
