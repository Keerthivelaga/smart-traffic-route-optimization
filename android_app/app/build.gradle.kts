plugins {
    alias(libs.plugins.android.application)
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
val certPins: String = providers.gradleProperty("SMART_TRAFFIC_CERT_PINS")
    .orElse("")
    .get()
val mapsApiKey: String = providers.gradleProperty("MAPS_API_KEY")
    .orElse(providers.environmentVariable("MAPS_API_KEY"))
    .orElse("")
    .get()
val firebaseApiKeyFromProp = providers.gradleProperty("FIREBASE_WEB_API_KEY").orNull?.trim().orEmpty()
val firebaseApiKey: String = if (firebaseApiKeyFromProp.isNotBlank()) {
    firebaseApiKeyFromProp
} else {
    val cfgCandidates = listOf(
        rootProject.projectDir.resolve("google-services.json"),
        rootProject.projectDir.resolve("../google-services.json"),
        projectDir.resolve("google-services.json"),
    )
    val cfg = cfgCandidates.firstOrNull { it.exists() }
    if (cfg != null) {
        val text = cfg.readText()
        Regex("\"current_key\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1).orEmpty()
    } else {
        ""
    }
}

android {
    namespace = "com.smarttraffic.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smarttraffic.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "INFERENCE_BASE_URL", "\"$inferenceBaseUrl\"")
        buildConfigField("String", "CERT_PINS", "\"$certPins\"")
        buildConfigField("String", "FIREBASE_WEB_API_KEY", "\"$firebaseApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":coreengine"))
    implementation(project(":designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.animation)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.google.material)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.places.sdk)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.51.1")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

