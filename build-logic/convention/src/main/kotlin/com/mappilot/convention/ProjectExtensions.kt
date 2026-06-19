package com.mappilot.convention

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Pinned SDK levels for every Android module in MapPilot. */
internal object MapPilotSdk {
    const val COMPILE = 35
    const val MIN = 31      // Android 12 — required for FGS types, SharedCamera, raw GNSS
    const val TARGET = 35
}
