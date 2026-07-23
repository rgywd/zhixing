package me.rerere.rikkahub.data.status

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

internal class AndroidCoarseLocationProvider(
    context: Context,
    private val clock: MyStatusClock = MyStatusClock(System::currentTimeMillis),
) : MyStatusLocationProvider {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun current(): LocalStatusLocation? {
        if (!hasPermission()) return null
        val fresh = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { requestCurrentLocation() }
        val location = fresh ?: lastKnownLocation() ?: return null
        val queryPoint = WeatherQueryPoint.fromRaw(location.latitude, location.longitude)
        val area = reverseGeocode(queryPoint)
        val areaLabel = buildAreaLabel(area ?: GeocodedArea()) ?: "当前位置附近"
        val observedAt = location.time.takeIf { it > 0 } ?: clock.nowEpochMillis()
        return LocalStatusLocation(
            modelContext = MyStatusLocationContext(
                area = areaLabel,
                observedAt = formatInstant(observedAt),
                confidence = when {
                    area == null -> MyStatusConfidence.LOW
                    location.hasAccuracy() && location.accuracy <= 5_000f -> MyStatusConfidence.HIGH
                    else -> MyStatusConfidence.MEDIUM
                },
            ),
            weatherQueryPoint = queryPoint,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocation(): Location? {
        val provider = preferredProvider() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                runCatching {
                    locationManager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        appContext.mainExecutor,
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } else {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) {
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
                runCatching {
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure {
                    locationManager.removeUpdates(listener)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ).mapNotNull { provider ->
        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull(Location::getTime)

    private fun preferredProvider(): String? = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ).firstOrNull { provider ->
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(point: WeatherQueryPoint): GeocodedArea? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(appContext, Locale.getDefault())
                .getFromLocation(point.requestLatitude, point.requestLongitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    GeocodedArea(
                        countryName = address.countryName,
                        adminArea = address.adminArea,
                        subAdminArea = address.subAdminArea,
                        locality = address.locality,
                    )
                }
        }.getOrNull()
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 8_000L
    }
}
