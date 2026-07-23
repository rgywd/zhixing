package me.rerere.rikkahub.data.status

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenMeteoWeatherProviderTest {
    @Test
    fun sendsOnlyCoarsenedCoordinateAndParsesCurrentWeather() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "current": {
                        "temperature_2m": 31.4,
                        "apparent_temperature": 34.2,
                        "relative_humidity_2m": 72,
                        "precipitation": 0.3,
                        "weather_code": 61,
                        "wind_speed_10m": 11.2
                      }
                    }
                    """.trimIndent()
                )
        )
        val provider = OpenMeteoWeatherProvider(
            client = OkHttpClient(),
            endpoint = server.url("/v1/forecast").toString(),
            clock = MyStatusClock { 1_774_406_400_000L },
        )

        val weather = provider.current(WeatherQueryPoint.fromRaw(30.2741, 120.1551))
        val request = server.takeRequest()

        assertEquals("30.3", request.requestUrl?.queryParameter("latitude"))
        assertEquals("120.2", request.requestUrl?.queryParameter("longitude"))
        assertFalse(request.path.orEmpty().contains("30.2741"))
        assertFalse(request.path.orEmpty().contains("120.1551"))
        assertEquals("有雨", weather.condition)
        assertEquals(34.2, weather.apparentTemperatureCelsius, 0.0)
        server.shutdown()
    }
}
