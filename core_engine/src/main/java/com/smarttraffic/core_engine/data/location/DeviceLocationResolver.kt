package com.smarttraffic.core_engine.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class DeviceLocationResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    suspend fun resolveCurrentLocationLatLng(): Result<String> = runCatching {
        require(hasLocationPermission()) {
            "Location permission required for Current Location."
        }
        require(isLocationEnabled()) {
            "Location services are off. Enable GPS/Location and try again."
        }
        val location = requestCurrentLocation() ?: requestLastKnownLocation()
        requireNotNull(location) {
            "Unable to determine current location. Turn on Location and try again."
        }
        String.format(Locale.US, "%.6f,%.6f", location.latitude, location.longitude)
    }

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
    private suspend fun requestCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val tokenSource = CancellationTokenSource()
        continuation.invokeOnCancellation { tokenSource.cancel() }
        fusedClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            .addOnCanceledListener {
                if (continuation.isActive) continuation.resume(null)
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestLastKnownLocation(): Location? = suspendCancellableCoroutine { continuation ->
        fusedClient.lastLocation
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            .addOnCanceledListener {
                if (continuation.isActive) continuation.resume(null)
            }
    }
}
