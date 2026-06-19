plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.assets.extraction"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
}
