plugins {
    alias(libs.plugins.mappilot.android.library.compose)
}

android {
    namespace = "com.mappilot.viz.map"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.maplibre)
}
