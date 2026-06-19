plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.perception.depth"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
