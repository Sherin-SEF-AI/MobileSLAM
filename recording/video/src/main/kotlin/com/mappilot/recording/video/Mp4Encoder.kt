package com.mappilot.recording.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware H.264 encoder writing an mp4 sidecar. The camera renders directly
 * into [inputSurface]; the camera's per-frame sensor timestamps propagate to the
 * encoder, so muxed sample PTS coincide with the unified-base capture timestamps
 * (the FrameTimestampMap records the exact correspondence).
 *
 * A dedicated drain thread pulls encoded buffers off MediaCodec and muxes them,
 * so encoding never blocks capture.
 */
class Mp4Encoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int = width * height * 4,
) {
    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private val muxerStarted = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null

    lateinit var inputSurface: Surface
        private set

    /** Most recent muxed presentation timestamp in nanoseconds (for the map). */
    @Volatile var lastPresentationNs: Long = 0L
        private set

    fun start(outputPath: String) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()

        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        running.set(true)
        drainThread = Thread({ drainLoop() }, "mp4-drain").apply { start() }
        Log.i(Streams.RECORDING, "Mp4Encoder started ${width}x$height @${frameRate} -> $outputPath")
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            drainOnce(bufferInfo, endOfStream = false)
        }
        // Flush the encoder on stop.
        runCatching { codec.signalEndOfInputStream() }
        drainOnce(bufferInfo, endOfStream = true)
    }

    private fun drainOnce(info: MediaCodec.BufferInfo, endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted.get()) { "format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted.set(true)
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index) ?: continue
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted.get()) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buf, info)
                        lastPresentationNs = info.presentationTimeUs * 1_000
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        drainThread?.join(STOP_TIMEOUT_MS)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted.get()) {
            runCatching { muxer.stop() }
        }
        runCatching { muxer.release() }
        runCatching { inputSurface.release() }
        Log.i(Streams.RECORDING, "Mp4Encoder stopped")
    }

    private companion object {
        const val I_FRAME_INTERVAL_S = 1
        const val TIMEOUT_US = 10_000L
        const val STOP_TIMEOUT_MS = 3_000L
    }
}
