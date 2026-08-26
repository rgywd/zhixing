package me.rerere.search

import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearXNGServiceTest {
    @Test
    fun webRequestUsesConfiguredEnginesLanguageAndBasicAuth() {
        val options = SearchServiceOptions.SearXNGOptions(
            url = "https://search.example.com/",
            engines = "bing,brave",
            language = "zh-CN",
            username = "zhixing",
            password = "secret",
        )

        val request = buildSearXNGRequest("北京 医保", options, SearXNGCategory.WEB)

        assertEquals("https", request.url.scheme)
        assertEquals("search.example.com", request.url.host)
        assertEquals("/search", request.url.encodedPath)
        assertEquals("北京 医保", request.url.queryParameter("q"))
        assertEquals("json", request.url.queryParameter("format"))
        assertEquals("bing,brave", request.url.queryParameter("engines"))
        assertEquals("zh-CN", request.url.queryParameter("language"))
        assertNull(request.url.queryParameter("categories"))
        assertEquals(Credentials.basic("zhixing", "secret"), request.header("Authorization"))
    }

    @Test
    fun imageRequestUsesImageCategoryAndServerSelectedImageEngines() {
        val options = SearchServiceOptions.SearXNGOptions(
            url = "https://search.example.com",
            engines = "bing,brave",
            language = "all",
        )

        val request = buildSearXNGRequest("space telescope", options, SearXNGCategory.IMAGES)

        assertEquals("images", request.url.queryParameter("categories"))
        assertNull(request.url.queryParameter("engines"))
        assertEquals("all", request.url.queryParameter("language"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun imageResponseKeepsNetworkImageSourceAndResolution() {
        val response = parseSearXNGResponse(
            """
            {
              "results": [
                {
                  "title": "A real network image",
                  "url": "https://news.example.com/story",
                  "img_src": "https://cdn.example.com/photo.jpg",
                  "thumbnail_src": "https://cdn.example.com/thumb.jpg",
                  "source": "news.example.com",
                  "resolution": "1600 x 900",
                  "engine": "bing images"
                },
                {
                  "title": "Thumbnail fallback",
                  "url": "https://blog.example.com/post",
                  "thumbnail_src": "https://blog.example.com/thumb.png"
                },
                {
                  "title": "No image",
                  "url": "https://example.com/no-image"
                }
              ]
            }
            """.trimIndent()
        )

        val result = response.toImageSearchResult(resultSize = 5)

        assertEquals(2, result.items.size)
        with(result.items.first()) {
            assertEquals("https://cdn.example.com/photo.jpg", imageUrl)
            assertEquals("https://news.example.com/story", sourceUrl)
            assertEquals("A real network image", title)
            assertEquals("news.example.com", siteName)
            assertEquals(1600, width)
            assertEquals(900, height)
        }
        assertEquals("https://blog.example.com/thumb.png", result.items[1].imageUrl)
    }

    @Test
    fun webResponseSkipsEntriesWithoutUsableUrls() {
        val response = parseSearXNGResponse(
            """{"results":[{"title":"Valid","url":"https://example.com","content":"Text"},{"title":"Missing URL"}]}"""
        )

        val result = response.toSearchResult(resultSize = 10)

        assertEquals(1, result.items.size)
        assertEquals("Valid", result.items.single().title)
        assertFalse(result.items.single().text.isBlank())
        assertTrue(result.items.single().url.startsWith("https://"))
    }
}
