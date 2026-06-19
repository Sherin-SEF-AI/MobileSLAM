import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.mappilot.android.library)
    alias(libs.plugins.mappilot.android.hilt)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.mappilot.recording.mcap"
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                // Full Java runtime (not lite) so messages expose runtime
                // descriptors — needed to build the embedded FileDescriptorSet.
                id("java")
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:time-sync"))
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
