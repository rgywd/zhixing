package me.rerere.rikkahub.data.location

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.poisearch.PoiResultV2
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.poisearch.VisualSearchResult
import com.amap.apis.utils.core.api.AMapUtilCoreApi
import java.time.Instant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.ai.tools.local.LocationFix
import me.rerere.rikkahub.data.ai.tools.local.LocationTravelErrorCode
import me.rerere.rikkahub.data.ai.tools.local.LocationTravelException
import me.rerere.rikkahub.data.ai.tools.local.LocationTravelGateway
import me.rerere.rikkahub.data.ai.tools.local.NearbyPlace
import me.rerere.rikkahub.data.ai.tools.local.NearbySearchRequest

private const val AMAP_SUCCESS = 1_000
private const val REQUEST_TIMEOUT_MILLIS = 12_000L

internal class AmapLocationTravelGateway(
    context: Context,
    private val hasPrivacyConsent: () -> Boolean,
) : LocationTravelGateway {
    private val appContext = context.applicationContext

    override suspend fun getCurrentLocation(): LocationFix {
        ensureReady()
        return try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) {
                awaitCurrentLocation()
            }
        } catch (error: TimeoutCancellationException) {
            throw LocationTravelException(LocationTravelErrorCode.TIMEOUT, cause = error)
        }
    }

    override suspend fun searchNearby(request: NearbySearchRequest): List<NearbyPlace> {
        ensureReady()
        val center = getCurrentLocation()
        return try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) {
                awaitNearbySearch(center, request)
            }
        } catch (error: TimeoutCancellationException) {
            throw LocationTravelException(LocationTravelErrorCode.TIMEOUT, cause = error)
        }
    }

    private fun ensureReady() {
        if (!hasPrivacyConsent()) {
            throw LocationTravelException(LocationTravelErrorCode.PRIVACY_CONSENT_REQUIRED)
        }
        if (!isAmapLocationTravelAbiSupported(Build.SUPPORTED_ABIS)) {
            throw LocationTravelException(
                code = LocationTravelErrorCode.EXTERNAL_SERVICE_ERROR,
                diagnosticCode = "UNSUPPORTED_ABI",
            )
        }
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) {
            throw LocationTravelException(LocationTravelErrorCode.NO_PERMISSION)
        }
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            throw LocationTravelException(LocationTravelErrorCode.LOCATION_DISABLED)
        }
        initializePrivacyConsent()
    }

    private fun initializePrivacyConsent() {
        AMapLocationClient.updatePrivacyShow(appContext, true, true)
        AMapLocationClient.updatePrivacyAgree(appContext, true)
        ServiceSettings.updatePrivacyShow(appContext, true, true)
        ServiceSettings.updatePrivacyAgree(appContext, true)
        AMapUtilCoreApi.setCollectInfoEnable(true)
    }

    private suspend fun awaitCurrentLocation(): LocationFix =
        suspendCancellableCoroutine { continuation ->
            val client = try {
                AMapLocationClient(appContext)
            } catch (error: Exception) {
                continuation.resumeWith(
                    Result.failure(
                        LocationTravelException(
                            LocationTravelErrorCode.EXTERNAL_SERVICE_ERROR,
                            cause = error,
                        ),
                    ),
                )
                return@suspendCancellableCoroutine
            }
            fun cleanup() {
                client.stopLocation()
                client.onDestroy()
            }
            continuation.invokeOnCancellation { cleanup() }
            client.setLocationOption(
                AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = true
                    isOnceLocationLatest = true
                    isNeedAddress = true
                    isOffset = true
                    isLocationCacheEnable = false
                    httpTimeOut = 10_000L
                },
            )
            client.setLocationListener { location ->
                if (!continuation.isActive) return@setLocationListener
                cleanup()
                if (location != null && location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                    continuation.resumeWith(Result.success(location.toLocationFix()))
                } else {
                    val errorCode = location?.errorCode
                    val mapped = if (errorCode == AMapLocation.ERROR_CODE_FAILURE_LOCATION_PERMISSION ||
                        errorCode == AMapLocation.ERROR_CODE_FAILURE_COARSE_LOCATION
                    ) {
                        LocationTravelErrorCode.NO_PERMISSION
                    } else {
                        LocationTravelErrorCode.EXTERNAL_SERVICE_ERROR
                    }
                    continuation.resumeWith(
                        Result.failure(
                            LocationTravelException(
                                code = mapped,
                                diagnosticCode = errorCode?.toString(),
                            ),
                        ),
                    )
                }
            }
            client.startLocation()
        }

    private suspend fun awaitNearbySearch(
        center: LocationFix,
        request: NearbySearchRequest,
    ): List<NearbyPlace> = suspendCancellableCoroutine { continuation ->
        val query = PoiSearchV2.Query(request.query, "").apply {
            pageNum = 1
            pageSize = request.limit
            isDistanceSort = true
            showFields = PoiSearchV2.ShowFields(PoiSearchV2.ShowFields.DEFAULT)
        }
        val search = try {
            PoiSearchV2(appContext, query)
        } catch (error: AMapException) {
            continuation.resumeWith(
                Result.failure(
                    LocationTravelException(
                        code = LocationTravelErrorCode.EXTERNAL_SERVICE_ERROR,
                        diagnosticCode = error.errorCode.toString(),
                        cause = error,
                    ),
                ),
            )
            return@suspendCancellableCoroutine
        }
        search.bound = PoiSearchV2.SearchBound(
            LatLonPoint(center.latitude, center.longitude),
            request.radiusMeters,
            true,
        )
        search.setOnPoiSearchListener(
            object : PoiSearchV2.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResultV2?, resultCode: Int) {
                    if (!continuation.isActive) return
                    if (resultCode == AMAP_SUCCESS && result != null) {
                        continuation.resumeWith(
                            Result.success(
                                result.pois.orEmpty()
                                    .asSequence()
                                    .mapNotNull { item -> item.toNearbyPlace(center) }
                                    .take(request.limit)
                                    .toList(),
                            ),
                        )
                    } else {
                        continuation.resumeWith(
                            Result.failure(
                                LocationTravelException(
                                    code = LocationTravelErrorCode.EXTERNAL_SERVICE_ERROR,
                                    diagnosticCode = resultCode.toString(),
                                ),
                            ),
                        )
                    }
                }

                override fun onPoiItemSearched(item: PoiItemV2?, resultCode: Int) = Unit

                override fun onVisualSearched(result: VisualSearchResult?, resultCode: Int) = Unit
            },
        )
        search.searchPOIAsyn()
    }
}

private fun AMapLocation.toLocationFix(): LocationFix = LocationFix(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy.toDouble(),
    observedAt = Instant.ofEpochMilli(time.takeIf { it > 0 } ?: System.currentTimeMillis()).toString(),
    address = address.orEmpty(),
    country = country.orEmpty(),
    province = province.orEmpty(),
    city = city.orEmpty(),
    district = district.orEmpty(),
    street = listOf(street, streetNum).filterNot(String?::isNullOrBlank).joinToString(""),
)

private fun PoiItemV2.toNearbyPlace(center: LocationFix): NearbyPlace? {
    val point = latLonPoint ?: return null
    val distance = FloatArray(1)
    android.location.Location.distanceBetween(
        center.latitude,
        center.longitude,
        point.latitude,
        point.longitude,
        distance,
    )
    return NearbyPlace(
        placeId = poiId.orEmpty(),
        name = title.orEmpty(),
        address = snippet.orEmpty(),
        category = typeDes.orEmpty(),
        distanceMeters = distance.first().toInt().coerceAtLeast(0),
        latitude = point.latitude,
        longitude = point.longitude,
    )
}

internal fun isAmapLocationTravelAbiSupported(supportedAbis: Array<String>): Boolean =
    supportedAbis.firstOrNull() in setOf("arm64-v8a", "armeabi-v7a")
