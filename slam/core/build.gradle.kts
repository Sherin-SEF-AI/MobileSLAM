plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.slam.core"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
}
