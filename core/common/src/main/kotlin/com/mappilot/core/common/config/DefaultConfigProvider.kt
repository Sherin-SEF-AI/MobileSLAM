package com.mappilot.core.common.config

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the active config in memory. A DataStore-backed implementation in the
 * Settings phase will replace the seed value; the interface stays stable.
 */
@Singleton
class DefaultConfigProvider @Inject constructor() : ConfigProvider {
    @Volatile
    private var config: CaptureConfig = CaptureConfig()

    override fun current(): CaptureConfig = config

    fun update(newConfig: CaptureConfig) {
        config = newConfig
    }
}
