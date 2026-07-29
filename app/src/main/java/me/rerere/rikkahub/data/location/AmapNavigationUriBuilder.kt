package me.rerere.rikkahub.data.location

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import me.rerere.rikkahub.data.ai.tools.local.NavigationRequest
import me.rerere.rikkahub.data.ai.tools.local.TravelMode

internal fun buildAmapAppNavigationUri(request: NavigationRequest): String {
    val params = buildList {
        add("sourceApplication=${encodeUriComponent("Zhixing")}")
        add("dlat=${request.latitude}")
        add("dlon=${request.longitude}")
        add("dname=${encodeUriComponent(request.destinationName)}")
        request.placeId?.takeIf(String::isNotBlank)?.let {
            add("did=${encodeUriComponent(it)}")
        }
        add("t=${amapAppMode(request.travelMode)}")
        add("dev=0")
    }
    return "amapuri://route/plan/?${params.joinToString("&")}"
}

internal fun buildAmapWebNavigationUri(request: NavigationRequest): String {
    val destination = "${request.longitude},${request.latitude},${request.destinationName}"
    val params = listOf(
        "to=${encodeUriComponent(destination)}",
        "mode=${amapWebMode(request.travelMode)}",
        "coordinate=gaode",
        "callnative=0",
    )
    return "https://uri.amap.com/navigation?${params.joinToString("&")}"
}

private fun amapAppMode(mode: TravelMode): Int = when (mode) {
    TravelMode.DRIVING -> 0
    TravelMode.TRANSIT -> 1
    TravelMode.WALKING -> 2
    TravelMode.CYCLING -> 3
}

internal fun amapWebMode(mode: TravelMode): String = when (mode) {
    TravelMode.DRIVING -> "car"
    TravelMode.TRANSIT -> "bus"
    TravelMode.WALKING -> "walk"
    TravelMode.CYCLING -> "ride"
}

private fun encodeUriComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
