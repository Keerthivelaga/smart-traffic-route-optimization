plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val backendBaseUrl: String = providers.gradleProperty("SMART_TRAFFIC_BACKEND_URL")
    .orElse("https://smart-traffic-backend-jurpys5u5a-uc.a.run.app/")
    .get()
val inferenceBaseUrl: String = providers.gradleProperty("SMART_TRAFFIC_INFERENCE_URL")
    .orElse("https://smart-traffic-inference-jurpys5u5a-uc.a.run.app/")
    .get()
val signingSecret: String = providers.gradleProperty("SMART_TRAFFIC_SIGNING_SECRET")
    .orElse("change_me")
    .get()
val certPins: String = providers.gradleProperty("SMART_TRAFFIC_CERT_PINS")
    .orElse("")
    .get()
val mapsApiKey: String = providers.gradleProperty("MAPS_API_KEY")
    .orElse(providers.environmentVariable("MAPS_API_KEY"))
    .orElse("")
    .get()

android {
    namespace = "com.smarttraffic.coreengine"
    compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "INFERENCE_BASE_URL", "\"$inferenceBaseUrl\"")
        buildConfigField("String", "SIGNING_SECRET", "\"$signingSecret\"")
        buildConfigField("String", "CERT_PINS", "\"$certPins\"")
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    api(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.ksp)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.play.services.location)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

