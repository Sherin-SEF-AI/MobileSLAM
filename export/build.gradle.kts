plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
}

android {
    namespace = "com.mappilot.export"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":geo:trajectory"))
    implementation(libs.kotlinx.coroutines.android)
}
