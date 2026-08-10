package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAt: String,
    val address: String,
    val country: String,
    val province: String,
    val city: String,
    val district: String,
    val street: String,
)

data class NearbySearchRequest(
    val query: String,
    val radiusMeters: Int = 2_000,
    val limit: Int = 5,
)

data class NearbyPlace(
    val placeId: String,
    val name: String,
    val address: String,
    val category: String,
    val distanceMeters: Int?,
    val latitude: Double,
    val longitude: Double,
)

enum class TravelMode {
    DRIVING,
    TRANSIT,
    WALKING,
    CYCLING,
}

data class NavigationRequest(
    val destinationName: String,
    val latitude: Double,
    val longitude: Double,
    val placeId: String? = null,
    val travelMode: TravelMode = TravelMode.DRIVING,
)

enum class NavigationTarget(val value: String) {
    AMAP_APP("amap_app"),
    AMAP_WEB("amap_web"),
}

interface LocationTravelGateway {
    suspend fun getCurrentLocation(): LocationFix

    suspend fun searchNearby(request: NearbySearchRequest): List<NearbyPlace>
}

fun interface NavigationLauncher {
    fun open(request: NavigationRequest): NavigationTarget
}

internal enum class LocationTravelErrorCode {
    CONFIGURATION_REQUIRED,
    PRIVACY_CONSENT_REQUIRED,
    NO_PERMISSION,
    LOCATION_DISABLED,
    TIMEOUT,
    INVALID_ARGUMENT,
    EXTERNAL_SERVICE_ERROR,
    NO_HANDLER,
    EXECUTION_FAILED,
}

internal class LocationTravelException(
    val code: LocationTravelErrorCode,
    val diagnosticCode: String? = null,
    cause: Throwable? = null,
) : Exception(code.name, cause)

internal fun buildLocationTravelTools(
    gateway: LocationTravelGateway,
    navigationLauncher: NavigationLauncher,
    isConfigured: () -> Boolean,
    hasPrivacyConsent: () -> Boolean,
): List<Tool> = listOf(
    buildCurrentLocationTool(gateway, isConfigured, hasPrivacyConsent),
    buildNearbySearchTool(gateway, isConfigured, hasPrivacyConsent),
    buildOpenNavigationTool(navigationLauncher, hasPrivacyConsent),
)

private fun buildCurrentLocationTool(
    gateway: LocationTravelGateway,
    isConfigured: () -> Boolean,
    hasPrivacyConsent: () -> Boolean,
) = Tool(
    name = "get_current_location",
    description = """
        Get one foreground location fix after the user has enabled this tool and granted location privacy consent. By default this returns only a structured
        address, accuracy and observation time. Set include_coordinates=true only when exact coordinates are essential
        to the user's request. Never infer that this is a background or continuously updated location.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put(
                    "include_coordinates",
                    buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether the user's GCJ-02 coordinates are essential. Default false.")
                    },
                )
            },
            additionalProperties = false,
        )
    },
    needsApproval = { true },
    execute = { input ->
        val parsed = parseCurrentLocationInput(input)
            ?: return@Tool locationTravelError(LocationTravelErrorCode.INVALID_ARGUMENT)
        if (!isConfigured()) {
            return@Tool locationTravelError(LocationTravelErrorCode.CONFIGURATION_REQUIRED)
        }
        if (!hasPrivacyConsent()) {
            return@Tool locationTravelError(LocationTravelErrorCode.PRIVACY_CONSENT_REQUIRED)
        }
        executeLocationTravel {
            val fix = gateway.getCurrentLocation()
            locationTravelSuccess {
                put("address", fix.address)
                put("country", fix.country)
                put("province", fix.province)
                put("city", fix.city)
                put("district", fix.district)
                put("street", fix.street)
                put("accuracy_meters", fix.accuracyMeters)
                put("observed_at", fix.observedAt)
                put("coordinate_system", "GCJ02")
                if (parsed.includeCoordinates) {
                    put("latitude", fix.latitude)
                    put("longitude", fix.longitude)
                }
            }
        }
    },
)

private fun buildNearbySearchTool(
    gateway: LocationTravelGateway,
    isConfigured: () -> Boolean,
    hasPrivacyConsent: () -> Boolean,
) = Tool(
    name = "search_nearby_places",
    description = """
        Search for nearby places around a one-time foreground location after the user has enabled this tool and granted location privacy consent. The user's search-center
        coordinates are intentionally withheld; returned latitude and longitude identify public destination places and
        may be passed to open_navigation. Results use the GCJ-02 coordinate system.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Required place keyword, such as coffee, hospital or restaurant.")
                    },
                )
                put(
                    "radius_meters",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 100)
                        put("maximum", 50_000)
                        put("description", "Search radius in meters. Default 2000.")
                    },
                )
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 10)
                        put("description", "Maximum returned places. Default 5.")
                    },
                )
            },
            required = listOf("query"),
            additionalProperties = false,
        )
    },
    needsApproval = { true },
    execute = { input ->
        val request = parseNearbySearchInput(input)
            ?: return@Tool locationTravelError(LocationTravelErrorCode.INVALID_ARGUMENT)
        if (!isConfigured()) {
            return@Tool locationTravelError(LocationTravelErrorCode.CONFIGURATION_REQUIRED)
        }
        if (!hasPrivacyConsent()) {
            return@Tool locationTravelError(LocationTravelErrorCode.PRIVACY_CONSENT_REQUIRED)
        }
        executeLocationTravel {
            val places = gateway.searchNearby(request)
            locationTravelSuccess {
                put("query", request.query)
                put("coordinate_system", "GCJ02")
                put(
                    "places",
                    buildJsonArray {
                        places.forEach { place ->
                            add(
                                buildJsonObject {
                                    put("place_id", place.placeId)
                                    put("name", place.name)
                                    put("address", place.address)
                                    put("category", place.category)
                                    place.distanceMeters?.let { put("distance_meters", it) }
                                    put("latitude", place.latitude)
                                    put("longitude", place.longitude)
                                },
                            )
                        }
                    },
                )
            }
        }
    },
)

private fun buildOpenNavigationTool(
    navigationLauncher: NavigationLauncher,
    hasPrivacyConsent: () -> Boolean,
) = Tool(
    name = "open_navigation",
    description = """
        Open an explicitly approved destination in AMap navigation. Coordinates must be GCJ-02 destination coordinates,
        normally copied from search_nearby_places. This opens an external app or browser and never starts navigation
        silently.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("destination_name", stringSchema("Required human-readable destination name."))
                put("latitude", numberSchema("Required GCJ-02 destination latitude.", -90, 90))
                put("longitude", numberSchema("Required GCJ-02 destination longitude.", -180, 180))
                put("place_id", stringSchema("Optional AMap POI id from search_nearby_places."))
                put(
                    "travel_mode",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("driving")
                                add("transit")
                                add("walking")
                                add("cycling")
                            },
                        )
                        put("description", "Navigation mode. Default driving.")
                    },
                )
            },
            required = listOf("destination_name", "latitude", "longitude"),
            additionalProperties = false,
        )
    },
    needsApproval = { true },
    execute = { input ->
        val request = parseNavigationInput(input)
            ?: return@Tool locationTravelError(LocationTravelErrorCode.INVALID_ARGUMENT)
        if (!hasPrivacyConsent()) {
            return@Tool locationTravelError(LocationTravelErrorCode.PRIVACY_CONSENT_REQUIRED)
        }
        executeLocationTravel {
            val target = navigationLauncher.open(request)
            locationTravelSuccess {
                put("target", target.value)
                put("destination_name", request.destinationName)
                put("travel_mode", request.travelMode.name.lowercase())
                put("coordinate_system", "GCJ02")
            }
        }
    },
)

private data class CurrentLocationInput(val includeCoordinates: Boolean)

private fun parseCurrentLocationInput(input: JsonElement): CurrentLocationInput? {
    val params = input as? JsonObject ?: return null
    if (params.keys.any { it != "include_coordinates" }) return null
    val includeCoordinates = params["include_coordinates"]?.let { value ->
        val primitive = value as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.takeUnless { primitive.isString } ?: return null
    } ?: false
    return CurrentLocationInput(includeCoordinates)
}

private fun parseNearbySearchInput(input: JsonElement): NearbySearchRequest? {
    val params = input as? JsonObject ?: return null
    if (params.keys.any { it !in setOf("query", "radius_meters", "limit") }) return null
    val query = params.strictString("query")?.trim().orEmpty()
    if (query.isBlank()) return null
    val radius = if ("radius_meters" in params) {
        params.strictInt("radius_meters") ?: return null
    } else {
        2_000
    }
    val limit = if ("limit" in params) {
        params.strictInt("limit") ?: return null
    } else {
        5
    }
    if (radius !in 100..50_000 || limit !in 1..10) return null
    return NearbySearchRequest(query, radius, limit)
}

private fun parseNavigationInput(input: JsonElement): NavigationRequest? {
    val params = input as? JsonObject ?: return null
    if (params.keys.any {
            it !in setOf("destination_name", "latitude", "longitude", "place_id", "travel_mode")
        }
    ) {
        return null
    }
    val destinationName = params.strictString("destination_name")?.trim().orEmpty()
    val latitude = params.strictDouble("latitude") ?: return null
    val longitude = params.strictDouble("longitude") ?: return null
    if (destinationName.isBlank() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
        return null
    }
    val placeId = if ("place_id" in params) {
        params.strictString("place_id")?.trim()?.takeIf(String::isNotBlank) ?: return null
    } else {
        null
    }
    val travelModeValue = if ("travel_mode" in params) {
        params.strictString("travel_mode")?.lowercase() ?: return null
    } else {
        "driving"
    }
    val travelMode = when (travelModeValue) {
        "driving" -> TravelMode.DRIVING
        "transit" -> TravelMode.TRANSIT
        "walking" -> TravelMode.WALKING
        "cycling" -> TravelMode.CYCLING
        else -> return null
    }
    return NavigationRequest(destinationName, latitude, longitude, placeId, travelMode)
}

private fun JsonObject.strictString(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { primitive.isString }
}

private fun JsonObject.strictInt(name: String): Int? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.intOrNull?.takeUnless { primitive.isString }
}

private fun JsonObject.strictDouble(name: String): Double? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.doubleOrNull
        ?.takeUnless { primitive.isString }
        ?.takeIf(Double::isFinite)
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun numberSchema(description: String, minimum: Int, maximum: Int) = buildJsonObject {
    put("type", "number")
    put("minimum", minimum)
    put("maximum", maximum)
    put("description", description)
}

private suspend fun executeLocationTravel(
    block: suspend () -> List<UIMessagePart>,
): List<UIMessagePart> = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: LocationTravelException) {
    locationTravelError(error.code, error.diagnosticCode)
} catch (_: Exception) {
    locationTravelError(LocationTravelErrorCode.EXECUTION_FAILED)
}

private fun locationTravelSuccess(
    content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("success", true)
            content()
        }.toString(),
    ),
)

private fun locationTravelError(
    code: LocationTravelErrorCode,
    diagnosticCode: String? = null,
) = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("success", false)
            put("error_code", code.name)
            put("message", code.userMessage())
            diagnosticCode?.takeIf(String::isNotBlank)?.let { put("diagnostic_code", it) }
        }.toString(),
    ),
)

private fun LocationTravelErrorCode.userMessage(): String = when (this) {
    LocationTravelErrorCode.CONFIGURATION_REQUIRED ->
        "This app build has not configured the required AMap Android key."
    LocationTravelErrorCode.PRIVACY_CONSENT_REQUIRED ->
        "Enable Location & Travel and accept its privacy notice before using location services."
    LocationTravelErrorCode.NO_PERMISSION ->
        "Foreground location permission is required."
    LocationTravelErrorCode.LOCATION_DISABLED ->
        "Turn on the device location service and try again."
    LocationTravelErrorCode.TIMEOUT ->
        "The location request timed out. Try again in an open area."
    LocationTravelErrorCode.INVALID_ARGUMENT ->
        "The tool input does not match the Location & Travel contract."
    LocationTravelErrorCode.EXTERNAL_SERVICE_ERROR ->
        "AMap could not complete this request."
    LocationTravelErrorCode.NO_HANDLER ->
        "No application can open AMap navigation on this device."
    LocationTravelErrorCode.EXECUTION_FAILED ->
        "Location & Travel could not complete this request."
}
