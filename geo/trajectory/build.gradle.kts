plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.geo.trajectory"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
