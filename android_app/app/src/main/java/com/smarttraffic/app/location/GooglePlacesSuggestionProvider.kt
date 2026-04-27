package com.smarttraffic.app.location

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class GooglePlacesSuggestionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSuggestionProvider {

    private val placesClient: PlacesClient? by lazy {
        runCatching {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val apiKey = appInfo.metaData?.getString("com.google.android.geo.API_KEY").orEmpty()
            if (apiKey.isBlank()) {
                null
            } else {
                if (!Places.isInitialized()) {
                    Places.initialize(context.applicationContext, apiKey)
                }
                Places.createClient(context)
            }
        }.getOrNull()
    }

    override suspend fun suggestLocations(query: String, exclude: String): Result<List<String>> {
        val normalized = query.trim()
        if (normalized.isBlank()) return Result.success(emptyList())
        val client = placesClient ?: return Result.failure(IllegalStateException("Places client unavailable"))

        return runCatching {
            val request = FindAutocompletePredictionsRequest.builder()
                .setSessionToken(AutocompleteSessionToken.newInstance())
                .setCountries(listOf("IN"))
                .setTypesFilter(listOf("geocode"))
                .setQuery(normalized)
                .build()

            val response = client.findAutocompletePredictions(request).await()
            response.autocompletePredictions
                .asSequence()
                .map { it.getFullText(null).toString().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .filterNot { it.equals(exclude.trim(), ignoreCase = true) }
                .take(MAX_SUGGESTIONS)
                .toList()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 6
    }
}
