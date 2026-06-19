plugins {
    alias(libs.plugins.mappilot.kotlin.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
