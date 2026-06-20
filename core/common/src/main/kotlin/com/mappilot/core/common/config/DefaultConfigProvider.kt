package com.mappilot.core.common.config

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.captureConfigStore by preferencesDataStore(name = "capture_config")

/**
 * DataStore-backed [ConfigProvider]. The persisted [CaptureConfig] is loaded once
 * (synchronously) at construction so [current] — called from hot paths like the
 * SyncEngine and RecordingSession — is always a cheap in-memory read. [update]
 * applies immediately in memory and persists asynchronously.
 *
 * Persisted values that would violate [CaptureConfig]'s invariants fall back to
 * defaults rather than crashing (a forward/backward-compatibility safety net).
 */
@Singleton
class DefaultConfigProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConfigProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var config: CaptureConfig = runCatching { runBlocking { load() } }
        .getOrElse {
            Log.e(Streams.SERVICE, it, "Config load failed; using defaults")
            CaptureConfig()
        }

    override fun current(): CaptureConfig = config

    fun update(newConfig: CaptureConfig) {
        config = newConfig
        scope.launch { runCatching { persist(newConfig) } }
    }

    private suspend fun load(): CaptureConfig {
        val p = context.captureConfigStore.data.first()
        val def = CaptureConfig()
        return CaptureConfig(
            videoWidth = p[KEY_VIDEO_W] ?: def.videoWidth,
            videoHeight = p[KEY_VIDEO_H] ?: def.videoHeight,
            targetFps = p[KEY_FPS] ?: def.targetFps,
            imuTargetHz = p[KEY_IMU_HZ] ?: def.imuTargetHz,
            perceptionHz = p[KEY_PERCEPTION_HZ] ?: def.perceptionHz,
            mcapChunkSealIntervalMs = p[KEY_SEAL_MS] ?: def.mcapChunkSealIntervalMs,
            mcapSegmentRolloverBytes = p[KEY_ROLLOVER_BYTES] ?: def.mcapSegmentRolloverBytes,
            inferenceDelegate = p[KEY_DELEGATE]?.let { name ->
                runCatching { InferenceDelegate.valueOf(name) }.getOrNull()
            } ?: def.inferenceDelegate,
        )
    }

    private suspend fun persist(c: CaptureConfig) {
        context.captureConfigStore.edit { p ->
            p[KEY_VIDEO_W] = c.videoWidth
            p[KEY_VIDEO_H] = c.videoHeight
            p[KEY_FPS] = c.targetFps
            p[KEY_IMU_HZ] = c.imuTargetHz
            p[KEY_PERCEPTION_HZ] = c.perceptionHz
            p[KEY_SEAL_MS] = c.mcapChunkSealIntervalMs
            p[KEY_ROLLOVER_BYTES] = c.mcapSegmentRolloverBytes
            p[KEY_DELEGATE] = c.inferenceDelegate.name
        }
    }

    private companion object {
        val KEY_VIDEO_W = intPreferencesKey("video_w")
        val KEY_VIDEO_H = intPreferencesKey("video_h")
        val KEY_FPS = intPreferencesKey("fps")
        val KEY_IMU_HZ = intPreferencesKey("imu_hz")
        val KEY_PERCEPTION_HZ = intPreferencesKey("perception_hz")
        val KEY_SEAL_MS = longPreferencesKey("seal_ms")
        val KEY_ROLLOVER_BYTES = longPreferencesKey("rollover_bytes")
        val KEY_DELEGATE: Preferences.Key<String> = stringPreferencesKey("inference_delegate")
    }
}
