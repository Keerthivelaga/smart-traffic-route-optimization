package com.smarttraffic.app.di

import com.smarttraffic.app.location.GooglePlacesSuggestionProvider
import com.smarttraffic.app.location.LocationSuggestionProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    @Singleton
    abstract fun bindLocationSuggestionProvider(
        impl: GooglePlacesSuggestionProvider,
    ): LocationSuggestionProvider
}

