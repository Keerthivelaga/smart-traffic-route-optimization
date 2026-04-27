package com.smarttraffic.core_engine.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.smarttraffic.core_engine.domain.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

@Singleton
class DeviceLocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun observeLocation(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<Result<GeoPoint>> = callbackFlow {
        if (!hasLocationPermission()) {
            trySend(Result.failure(IllegalStateException("Location permission required.")))
            close()
            return@callbackFlow
        }
        if (!isLocationEnabled()) {
            trySend(Result.failure(IllegalStateException("Location services are off. Enable GPS/Location.")))
            close()
            return@callbackFlow
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(500L))
            .setWaitForAccurateLocation(false)
            .build()

        var hasReceivedFirstFix = false
        val firstFixTimeoutJob = launch {
            delay(FIRST_FIX_TIMEOUT_MS)
            if (!hasReceivedFirstFix) {
                trySend(Result.failure(IllegalStateException("Waiting for GPS fix. Move to open sky and keep Location on.")))
            }
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                hasReceivedFirstFix = true
                firstFixTimeoutJob.cancel()
                trySend(
                    Result.success(
                        GeoPoint(
                            latitude = location.latitude,
                            longitude = location.longitude,
                        )
                    )
                )
            }
        }

        runCatching {
            requestLocationUpdates(request, callback)
        }.onFailure { error ->
            firstFixTimeoutJob.cancel()
            trySend(Result.failure(error))
            close(error)
            return@callbackFlow
        }

        awaitClose {
            firstFixTimeoutJob.cancel()
            fusedClient.removeLocationUpdates(callback)
        }
    }.conflate()

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(
        request: LocationRequest,
        callback: LocationCallback,
    ) {
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 2_000L
        const val FIRST_FIX_TIMEOUT_MS = 12_000L
    }
}
