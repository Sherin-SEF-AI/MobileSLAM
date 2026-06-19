plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.perception.detection"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
