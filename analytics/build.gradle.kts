plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.analytics"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
}
