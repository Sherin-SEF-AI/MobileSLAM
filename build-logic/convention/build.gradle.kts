import org.gradle.api.JavaVersion

plugins {
    `kotlin-dsl`
}

group = "com.mappilot.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "mappilot.android.library"
            implementationClass = "com.mappilot.convention.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "mappilot.android.library.compose"
            implementationClass = "com.mappilot.convention.AndroidLibraryComposeConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "mappilot.kotlin.library"
            implementationClass = "com.mappilot.convention.KotlinLibraryConventionPlugin"
        }
        register("androidHilt") {
            id = "mappilot.android.hilt"
            implementationClass = "com.mappilot.convention.AndroidHiltConventionPlugin"
        }
    }
}
