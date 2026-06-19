plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.sensors.imu"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
