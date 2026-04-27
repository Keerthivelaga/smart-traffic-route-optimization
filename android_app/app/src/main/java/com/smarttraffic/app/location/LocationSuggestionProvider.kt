package com.smarttraffic.app.location

interface LocationSuggestionProvider {
    suspend fun suggestLocations(query: String, exclude: String): Result<List<String>>
}

