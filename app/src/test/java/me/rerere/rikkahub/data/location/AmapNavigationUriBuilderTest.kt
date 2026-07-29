package me.rerere.rikkahub.data.location

import me.rerere.rikkahub.data.ai.tools.local.NavigationRequest
import me.rerere.rikkahub.data.ai.tools.local.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapNavigationUriBuilderTest {
    @Test
    fun `current AMap SDK only enables native location on an ARM primary ABI`() {
        assertTrue(isAmapLocationTravelAbiSupported(arrayOf("arm64-v8a")))
        assertTrue(isAmapLocationTravelAbiSupported(arrayOf("armeabi-v7a", "arm64-v8a")))
        assertFalse(isAmapLocationTravelAbiSupported(arrayOf("x86_64", "arm64-v8a")))
        assertFalse(isAmapLocationTravelAbiSupported(emptyArray()))
    }

    @Test
    fun `app uri uses GCJ02 destination and maps travel modes`() {
        val request = NavigationRequest(
            destinationName = "上海 博物馆",
            latitude = 31.2303,
            longitude = 121.47,
            placeId = "B000A8UIN8",
            travelMode = TravelMode.WALKING,
        )

        val uri = buildAmapAppNavigationUri(request)

        assertTrue(uri.startsWith("amapuri://route/plan/?"))
        assertTrue(uri.contains("dlat=31.2303"))
        assertTrue(uri.contains("dlon=121.47"))
        assertTrue(uri.contains("dname=%E4%B8%8A%E6%B5%B7%20%E5%8D%9A%E7%89%A9%E9%A6%86"))
        assertTrue(uri.contains("did=B000A8UIN8"))
        assertTrue(uri.contains("t=2"))
        assertTrue(uri.contains("dev=0"))
        assertFalse(uri.contains("slat="))
        assertFalse(uri.contains("slon="))
    }

    @Test
    fun `web fallback is HTTPS and preserves the destination`() {
        val request = NavigationRequest(
            destinationName = "外滩",
            latitude = 31.2400,
            longitude = 121.4900,
            travelMode = TravelMode.DRIVING,
        )

        val uri = buildAmapWebNavigationUri(request)

        assertTrue(uri.startsWith("https://uri.amap.com/navigation?"))
        assertTrue(uri.contains("to=121.49%2C31.24%2C%E5%A4%96%E6%BB%A9"))
        assertTrue(uri.contains("mode=car"))
        assertTrue(uri.contains("coordinate=gaode"))
        assertEquals(
            "bus",
            amapWebMode(TravelMode.TRANSIT),
        )
        assertEquals(
            "ride",
            amapWebMode(TravelMode.CYCLING),
        )
    }
}
