plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.slam.arcore"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
