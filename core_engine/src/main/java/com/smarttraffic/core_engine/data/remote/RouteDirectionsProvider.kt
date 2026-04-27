package com.smarttraffic.core_engine.data.remote

import com.smarttraffic.coreengine.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

data class RouteDirectionsQuery(
    val origin: String,
    val destination: String,
    val trafficModel: String,
    val avoid: String?,
)

interface RouteDirectionsProvider {
    suspend fun fetchDirections(query: RouteDirectionsQuery): Response<DirectionsResponseDto>
}

@Singleton
class DefaultRouteDirectionsProvider @Inject constructor(
    private val mapsDirectionsApi: MapsDirectionsApi,
) : RouteDirectionsProvider {
    override suspend fun fetchDirections(query: RouteDirectionsQuery): Response<DirectionsResponseDto> {
        val mapsApiKey = BuildConfig.MAPS_API_KEY.trim()
        require(mapsApiKey.isNotBlank()) {
            "MAPS_API_KEY missing. Add it in gradle.properties."
        }
        return mapsDirectionsApi.getDirections(
            origin = query.origin,
            destination = query.destination,
            trafficModel = query.trafficModel,
            avoid = query.avoid,
            apiKey = mapsApiKey,
        )
    }
}
