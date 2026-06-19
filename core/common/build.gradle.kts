plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
}

android {
    namespace = "com.mappilot.core.common"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
