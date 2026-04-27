package com.smarttraffic.core_engine.di

import android.content.Context
import androidx.room.Room
import com.smarttraffic.coreengine.BuildConfig
import com.smarttraffic.core_engine.data.local.LeaderboardDao
import com.smarttraffic.core_engine.data.local.RouteDao
import com.smarttraffic.core_engine.data.local.SmartTrafficDatabase
import com.smarttraffic.core_engine.data.local.TrafficDao
import com.smarttraffic.core_engine.data.local.UserPreferencesStore
import com.smarttraffic.core_engine.data.remote.AuthHeaderInterceptor
import com.smarttraffic.core_engine.data.remote.BackendApi
import com.smarttraffic.core_engine.data.remote.DefaultRouteDirectionsProvider
import com.smarttraffic.core_engine.data.remote.FirebaseIdentityApi
import com.smarttraffic.core_engine.data.remote.InferenceApi
import com.smarttraffic.core_engine.data.remote.MapsDirectionsApi
import com.smarttraffic.core_engine.data.remote.OpenMeteoApi
import com.smarttraffic.core_engine.data.remote.RouteDirectionsProvider
import com.smarttraffic.core_engine.data.remote.ResilienceInterceptor
import com.smarttraffic.core_engine.data.remote.SignatureInterceptor
import com.smarttraffic.core_engine.data.repository.RouteRepositoryImpl
import com.smarttraffic.core_engine.data.repository.TrafficRepositoryImpl
import com.smarttraffic.core_engine.data.repository.UserRepositoryImpl
import com.smarttraffic.core_engine.domain.repository.RouteRepository
import com.smarttraffic.core_engine.domain.repository.TrafficRepository
import com.smarttraffic.core_engine.domain.repository.UserRepository
import com.smarttraffic.core_engine.domain.usecase.FetchPredictionUseCase
import com.smarttraffic.core_engine.domain.usecase.GetProfileUseCase
import com.smarttraffic.core_engine.domain.usecase.GetRoutesUseCase
import com.smarttraffic.core_engine.domain.usecase.ObserveLeaderboardUseCase
import com.smarttraffic.core_engine.domain.usecase.RefreshTrafficUseCase
import com.smarttraffic.core_engine.domain.usecase.ObserveTrafficUseCase
import com.smarttraffic.core_engine.domain.usecase.ReportIncidentUseCase
import com.smarttraffic.core_engine.domain.usecase.SubmitGpsUseCase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object CoreProvisionModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttp(
        authHeaderInterceptor: AuthHeaderInterceptor,
        signatureInterceptor: SignatureInterceptor,
        resilienceInterceptor: ResilienceInterceptor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val backendUrl = BuildConfig.BACKEND_BASE_URL.toHttpUrl()
        val inferenceUrl = BuildConfig.INFERENCE_BASE_URL.toHttpUrl()
        val pins = BuildConfig.CERT_PINS
            .split(",")
            .map { it.trim() }
            .filter { it.startsWith("sha256/") }
            .distinct()
        val hosts = listOf(backendUrl.host, inferenceUrl.host).distinct()

        if (BuildConfig.BUILD_TYPE == "release" && hosts.isNotEmpty() && pins.isEmpty()) {
            throw IllegalStateException("Release build requires at least one CERT_PINS sha256 pin")
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(resilienceInterceptor)
            .addInterceptor(authHeaderInterceptor)
            .addInterceptor(signatureInterceptor)

        if (pins.isNotEmpty()) {
            val pinnerBuilder = CertificatePinner.Builder()
            hosts.forEach { host ->
                pins.forEach { pin -> pinnerBuilder.add(host, pin) }
            }
            builder.certificatePinner(pinnerBuilder.build())
        }

        return builder.build()
    }

    @Provides
    @Singleton
    @Named("backend")
    fun provideBackendRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    @Named("inference")
    fun provideInferenceRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.INFERENCE_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideBackendApi(@Named("backend") retrofit: Retrofit): BackendApi = retrofit.create(BackendApi::class.java)

    @Provides
    @Singleton
    fun provideInferenceApi(@Named("inference") retrofit: Retrofit): InferenceApi = retrofit.create(InferenceApi::class.java)

    @Provides
    @Singleton
    @Named("identity")
    fun provideIdentityClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("firebaseIdentity")
    fun provideFirebaseIdentityRetrofit(@Named("identity") client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://identitytoolkit.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideFirebaseIdentityApi(@Named("firebaseIdentity") retrofit: Retrofit): FirebaseIdentityApi {
        return retrofit.create(FirebaseIdentityApi::class.java)
    }

    @Provides
    @Singleton
    @Named("mapsDirections")
    fun provideMapsDirectionsClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("mapsDirections")
    fun provideMapsDirectionsRetrofit(@Named("mapsDirections") client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideMapsDirectionsApi(@Named("mapsDirections") retrofit: Retrofit): MapsDirectionsApi {
        return retrofit.create(MapsDirectionsApi::class.java)
    }

    @Provides
    @Singleton
    @Named("weather")
    fun provideWeatherClient(resilienceInterceptor: ResilienceInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .addInterceptor(resilienceInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("weather")
    fun provideWeatherRetrofit(@Named("weather") client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenMeteoApi(@Named("weather") retrofit: Retrofit): OpenMeteoApi {
        return retrofit.create(OpenMeteoApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext context: Context): SmartTrafficDatabase {
        return Room.databaseBuilder(context, SmartTrafficDatabase::class.java, "smart_traffic.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideTrafficDao(db: SmartTrafficDatabase): TrafficDao = db.trafficDao()

    @Provides
    fun provideRouteDao(db: SmartTrafficDatabase): RouteDao = db.routeDao()

    @Provides
    fun provideLeaderboardDao(db: SmartTrafficDatabase): LeaderboardDao = db.leaderboardDao()

    @Provides
    @Singleton
    fun providePreferencesStore(@ApplicationContext context: Context): UserPreferencesStore = UserPreferencesStore(context)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    fun provideObserveTrafficUseCase(repository: TrafficRepository) = ObserveTrafficUseCase(repository)

    @Provides
    fun provideRefreshTrafficUseCase(repository: TrafficRepository) = RefreshTrafficUseCase(repository)

    @Provides
    fun provideFetchPredictionUseCase(repository: TrafficRepository) = FetchPredictionUseCase(repository)

    @Provides
    fun provideSubmitGpsUseCase(repository: TrafficRepository) = SubmitGpsUseCase(repository)

    @Provides
    fun provideGetRoutesUseCase(repository: RouteRepository) = GetRoutesUseCase(repository)

    @Provides
    fun provideReportIncidentUseCase(repository: UserRepository) = ReportIncidentUseCase(repository)

    @Provides
    fun provideObserveLeaderboardUseCase(repository: UserRepository) = ObserveLeaderboardUseCase(repository)

    @Provides
    fun provideGetProfileUseCase(repository: UserRepository) = GetProfileUseCase(repository)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindingModule {
    @Binds
    @Singleton
    abstract fun bindRouteDirectionsProvider(impl: DefaultRouteDirectionsProvider): RouteDirectionsProvider

    @Binds
    @Singleton
    abstract fun bindTrafficRepository(impl: TrafficRepositoryImpl): TrafficRepository

    @Binds
    @Singleton
    abstract fun bindRouteRepository(impl: RouteRepositoryImpl): RouteRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository
}

