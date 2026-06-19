plugins {
    alias(libs.plugins.mappilot.android.library)
}

android {
    namespace = "com.mappilot.core.database"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
}
