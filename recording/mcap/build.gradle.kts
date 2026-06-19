plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.recording.mcap"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
