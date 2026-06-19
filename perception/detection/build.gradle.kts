plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
}

android {
    namespace = "com.mappilot.perception.detection"

    // Keep the compressed .tflite uncompressed so it memory-maps at runtime.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":perception:core"))
    implementation(libs.litert)
    implementation(libs.litert.gpu)
}
