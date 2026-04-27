plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "androidx.core:core:1.13.1",
            "androidx.core:core-ktx:1.13.1",
        )
    }
}

