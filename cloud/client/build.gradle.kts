plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mappilot.cloud.client"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
