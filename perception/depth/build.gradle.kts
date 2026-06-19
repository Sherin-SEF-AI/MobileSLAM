plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
}

android {
    namespace = "com.mappilot.perception.depth"
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":perception:core"))
    implementation(libs.litert)
}
