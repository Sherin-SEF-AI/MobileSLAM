plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.assets.extraction"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
