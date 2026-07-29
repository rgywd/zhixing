package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTravelToolsTest {
    @Test
    fun `schemas are closed and every location tool requires approval`() {
        val tools = Harness().tools.associateBy { it.name }

        assertEquals(
            setOf("get_current_location", "search_nearby_places", "open_navigation"),
            tools.keys,
        )
        tools.values.forEach { tool ->
            assertEquals(false, (tool.parameters() as InputSchema.Obj).additionalProperties)
            assertTrue(tool.needsApproval(buildJsonObject {}))
        }
        assertEquals(
            listOf("query"),
            (tools.getValue("search_nearby_places").parameters() as InputSchema.Obj).required,
        )
        assertEquals(
            listOf("destination_name", "latitude", "longitude"),
            (tools.getValue("open_navigation").parameters() as InputSchema.Obj).required,
        )
    }

    @Test
    fun `current location omits coordinates unless explicitly requested`() = runBlocking {
        val harness = Harness()
        val tool = harness.tools.single { it.name == "get_current_location" }

        val redacted = tool.execute(buildJsonObject {}).json()
        val explicit = tool.execute(
            buildJsonObject { put("include_coordinates", true) },
        ).json()

        assertTrue(redacted.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(
            setOf(
                "success",
                "address",
                "country",
                "province",
                "city",
                "district",
                "street",
                "accuracy_meters",
                "observed_at",
                "coordinate_system",
            ),
            redacted.keys,
        )
        assertFalse("latitude" in redacted)
        assertFalse("longitude" in redacted)
        assertEquals(31.2304, explicit.getValue("latitude").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(121.4737, explicit.getValue("longitude").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("GCJ02", explicit.getValue("coordinate_system").jsonPrimitive.content)
        assertEquals(2, harness.currentLocationCalls)
    }

    @Test
    fun `nearby search keeps the center private and returns usable destination coordinates`() = runBlocking {
        val harness = Harness()
        val tool = harness.tools.single { it.name == "search_nearby_places" }

        val result = tool.execute(
            buildJsonObject {
                put("query", " 咖啡 ")
                put("radius_meters", 1500)
                put("limit", 3)
            },
        ).json()

        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(setOf("success", "query", "coordinate_system", "places"), result.keys)
        assertFalse(result.toString().contains("\"center_"))
        assertFalse(result.toString().contains("31.2304"))
        assertEquals(
            NearbySearchRequest(query = "咖啡", radiusMeters = 1500, limit = 3),
            harness.searchRequest,
        )
        val place = result.getValue("places").jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "place_id",
                "name",
                "address",
                "category",
                "distance_meters",
                "latitude",
                "longitude",
            ),
            place.keys,
        )
        assertEquals("poi-1", place.getValue("place_id").jsonPrimitive.content)
    }

    @Test
    fun `optional nearby and navigation inputs use the frozen defaults`() = runBlocking {
        val harness = Harness()

        harness.tools.single { it.name == "search_nearby_places" }
            .execute(buildJsonObject { put("query", "药店") })
        harness.tools.single { it.name == "open_navigation" }
            .execute(
                buildJsonObject {
                    put("destination_name", "人民广场")
                    put("latitude", 31.233)
                    put("longitude", 121.475)
                },
            )

        assertEquals(
            NearbySearchRequest(query = "药店", radiusMeters = 2_000, limit = 5),
            harness.searchRequest,
        )
        assertEquals(TravelMode.DRIVING, harness.navigationRequest?.travelMode)
        assertEquals(null, harness.navigationRequest?.placeId)
    }

    @Test
    fun `invalid input is rejected before location or navigation side effects`() = runBlocking {
        val invalidCurrentLocation = listOf(
            buildJsonObject { put("include_coordinates", "true") },
            buildJsonObject { put("unexpected", true) },
        )
        val invalidNearby = listOf(
            buildJsonObject {},
            buildJsonObject { put("query", " ") },
            buildJsonObject { put("query", "咖啡"); put("radius_meters", 99) },
            buildJsonObject { put("query", "咖啡"); put("radius_meters", 50_001) },
            buildJsonObject { put("query", "咖啡"); put("radius_meters", "1000") },
            buildJsonObject { put("query", "咖啡"); put("limit", 0) },
            buildJsonObject { put("query", "咖啡"); put("limit", 11) },
            buildJsonObject { put("query", "咖啡"); put("limit", "5") },
            buildJsonObject { put("query", "咖啡"); put("raw_location", "private") },
        )
        val invalidNavigation = listOf(
            buildJsonObject { put("destination_name", " "); put("latitude", 31.0); put("longitude", 121.0) },
            buildJsonObject { put("destination_name", "外滩"); put("latitude", 91.0); put("longitude", 121.0) },
            buildJsonObject { put("destination_name", "外滩"); put("latitude", 31.0); put("longitude", 181.0) },
            buildJsonObject {
                put("destination_name", "外滩")
                put("latitude", 31.0)
                put("longitude", 121.0)
                put("travel_mode", "flying")
            },
            buildJsonObject {
                put("destination_name", "外滩")
                put("latitude", 31.0)
                put("longitude", 121.0)
                put("place_id", 123)
            },
            buildJsonObject {
                put("destination_name", "外滩")
                put("latitude", 31.0)
                put("longitude", 121.0)
                put("travel_mode", 1)
            },
        )
        val harness = Harness()
        val currentLocation = harness.tools.single { it.name == "get_current_location" }
        val nearby = harness.tools.single { it.name == "search_nearby_places" }
        val navigation = harness.tools.single { it.name == "open_navigation" }

        (invalidCurrentLocation.map { currentLocation.execute(it).json() } +
            invalidNearby.map { nearby.execute(it).json() } +
            invalidNavigation.map { navigation.execute(it).json() }).forEach { result ->
            assertFalse(result.getValue("success").jsonPrimitive.content.toBoolean())
            assertEquals("INVALID_ARGUMENT", result.getValue("error_code").jsonPrimitive.content)
        }
        assertEquals(0, harness.currentLocationCalls)
        assertEquals(null, harness.searchRequest)
        assertEquals(null, harness.navigationRequest)
    }

    @Test
    fun `configuration and privacy failures are stable and make no gateway call`() = runBlocking {
        val noKey = Harness(configured = false)
        val noConsent = Harness(privacyConsent = false)
        val input = buildJsonObject {}

        val missingKey = noKey.tools.first().execute(input).json()
        val missingConsent = noConsent.tools.first().execute(input).json()
        val navigationWithoutConsent = noConsent.tools
            .single { it.name == "open_navigation" }
            .execute(
                buildJsonObject {
                    put("destination_name", "外滩")
                    put("latitude", 31.0)
                    put("longitude", 121.0)
                },
            )
            .json()

        assertEquals("CONFIGURATION_REQUIRED", missingKey.getValue("error_code").jsonPrimitive.content)
        assertEquals(
            "PRIVACY_CONSENT_REQUIRED",
            missingConsent.getValue("error_code").jsonPrimitive.content,
        )
        assertEquals(
            "PRIVACY_CONSENT_REQUIRED",
            navigationWithoutConsent.getValue("error_code").jsonPrimitive.content,
        )
        assertEquals(0, noKey.currentLocationCalls)
        assertEquals(0, noConsent.currentLocationCalls)
        assertEquals(null, noConsent.navigationRequest)
    }

    @Test
    fun `gateway failures are redacted and cancellation-safe`() = runBlocking {
        val harness = Harness(locationFailure = LocationTravelException(
            code = LocationTravelErrorCode.EXTERNAL_SERVICE_ERROR,
            diagnosticCode = "10001",
            cause = IllegalStateException("secret coordinate and vendor response"),
        ))
        val result = harness.tools.first().execute(buildJsonObject {}).json()

        assertEquals("EXTERNAL_SERVICE_ERROR", result.getValue("error_code").jsonPrimitive.content)
        assertEquals("10001", result.getValue("diagnostic_code").jsonPrimitive.content)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("coordinate"))
    }

    @Test
    fun `valid navigation routes the approved typed request`() = runBlocking {
        val harness = Harness()
        val tool = harness.tools.single { it.name == "open_navigation" }

        val result = tool.execute(
            buildJsonObject {
                put("destination_name", "上海博物馆")
                put("latitude", 31.2303)
                put("longitude", 121.4700)
                put("place_id", "B000A8UIN8")
                put("travel_mode", "walking")
            },
        ).json()

        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(
            NavigationRequest(
                destinationName = "上海博物馆",
                latitude = 31.2303,
                longitude = 121.4700,
                placeId = "B000A8UIN8",
                travelMode = TravelMode.WALKING,
            ),
            harness.navigationRequest,
        )
        assertEquals("amap_app", result.getValue("target").jsonPrimitive.content)
    }

    private class Harness(
        configured: Boolean = true,
        privacyConsent: Boolean = true,
        private val locationFailure: LocationTravelException? = null,
    ) {
        var currentLocationCalls = 0
        var searchRequest: NearbySearchRequest? = null
        var navigationRequest: NavigationRequest? = null

        private val gateway = object : LocationTravelGateway {
            override suspend fun getCurrentLocation(): LocationFix {
                currentLocationCalls += 1
                locationFailure?.let { throw it }
                return LocationFix(
                    latitude = 31.2304,
                    longitude = 121.4737,
                    accuracyMeters = 16.5,
                    observedAt = "2026-07-29T08:00:00Z",
                    address = "上海市黄浦区人民大道",
                    country = "中国",
                    province = "上海市",
                    city = "上海市",
                    district = "黄浦区",
                    street = "人民大道",
                )
            }

            override suspend fun searchNearby(request: NearbySearchRequest): List<NearbyPlace> {
                searchRequest = request
                return listOf(
                    NearbyPlace(
                        placeId = "poi-1",
                        name = "示例咖啡",
                        address = "人民大道 1 号",
                        category = "餐饮服务;咖啡厅",
                        distanceMeters = 320,
                        latitude = 31.2310,
                        longitude = 121.4750,
                    ),
                )
            }
        }
        private val launcher = object : NavigationLauncher {
            override fun open(request: NavigationRequest): NavigationTarget {
                navigationRequest = request
                return NavigationTarget.AMAP_APP
            }
        }

        val tools = buildLocationTravelTools(
            gateway = gateway,
            navigationLauncher = launcher,
            isConfigured = { configured },
            hasPrivacyConsent = { privacyConsent },
        )
    }
}

private fun List<UIMessagePart>.json(): JsonObject {
    val text = (single() as UIMessagePart.Text).text
    return Json.parseToJsonElement(text).jsonObject
}
