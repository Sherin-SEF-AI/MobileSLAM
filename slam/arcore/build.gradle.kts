plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
}

android {
    namespace = "com.mappilot.slam.arcore"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:time-sync"))
    implementation(project(":slam:core"))
    implementation(libs.arcore)
    // Required to call Session.configure() with Geospatial mode enabled.
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
}
