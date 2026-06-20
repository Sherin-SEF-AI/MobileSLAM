plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
}

android {
    namespace = "com.mappilot.search"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.android)
}
