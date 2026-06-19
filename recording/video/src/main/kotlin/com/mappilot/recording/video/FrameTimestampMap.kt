package com.mappilot.recording.video

import java.io.Writer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The authoritative mapping between a camera frame, its video presentation
 * timestamp, and its unified-base capture timestamp. Lets a consumer seek the
 * mp4 to the exact frame a pose/detection refers to.
 *
 * `video_pts_ns` and `timestamp_ns` are both in the camera's sensor timeline,
 * which the encoder preserves end-to-end (no resampling), so they coincide; the
 * map records them explicitly rather than assuming it.
 */
class FrameTimestampMap {
    data class Entry(val frameId: Long, val timestampNs: Long, val videoPtsNs: Long)

    private val entries = ConcurrentLinkedQueue<Entry>()

    fun add(frameId: Long, timestampNs: Long, videoPtsNs: Long) {
        entries.add(Entry(frameId, timestampNs, videoPtsNs))
    }

    val size: Int get() = entries.size

    fun snapshot(): List<Entry> = entries.toList()

    /** Persist as CSV: `frame_id,timestamp_ns,video_pts_ns`. */
    fun writeCsv(writer: Writer) {
        writer.write("frame_id,timestamp_ns,video_pts_ns\n")
        for (e in entries) {
            writer.write("${e.frameId},${e.timestampNs},${e.videoPtsNs}\n")
        }
        writer.flush()
    }
}
