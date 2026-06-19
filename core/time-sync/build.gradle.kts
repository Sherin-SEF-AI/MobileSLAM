plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.core.timesync"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
