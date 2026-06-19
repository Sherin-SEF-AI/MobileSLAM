package com.mappilot.sensors.camera

import com.mappilot.core.model.CameraIntrinsics

/**
 * Pure mapping from Camera2 calibration metadata to the domain [CameraIntrinsics],
 * including scaling from the sensor's pre-correction active array to the actual
 * output resolution. Side-effect-free and unit-tested — intrinsics are real
 * device calibration, never guessed.
 */
internal object CameraIntrinsicsMapper {

    /**
     * @param calibration `LENS_INTRINSIC_CALIBRATION` = [fx, fy, cx, cy, s] in
     *   pixels of the pre-correction active array. Null when the device does not
     *   report calibrated intrinsics.
     * @param distortion `LENS_DISTORTION` = [κ1, κ2, κ3, κ4, κ5] (radial κ1–κ3,
     *   tangential κ4, κ5). Mapped to OpenCV order [k1, k2, p1, p2, k3].
     * @param arrayWidth/[arrayHeight] pre-correction active array size.
     * @param outputWidth/[outputHeight] the resolution frames are delivered at.
     *
     * Returns null when [calibration] is absent — the caller surfaces
     * "intrinsics unavailable" rather than fabricating a pinhole model.
     */
    fun map(
        calibration: FloatArray?,
        distortion: FloatArray?,
        arrayWidth: Int,
        arrayHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): CameraIntrinsics? {
        if (calibration == null || calibration.size < 4) return null
        if (arrayWidth <= 0 || arrayHeight <= 0) return null

        val sx = outputWidth.toDouble() / arrayWidth
        val sy = outputHeight.toDouble() / arrayHeight

        return CameraIntrinsics(
            fx = calibration[0] * sx,
            fy = calibration[1] * sy,
            cx = calibration[2] * sx,
            cy = calibration[3] * sy,
            k1 = distortion?.getOrNull(0)?.toDouble() ?: 0.0,
            k2 = distortion?.getOrNull(1)?.toDouble() ?: 0.0,
            p1 = distortion?.getOrNull(3)?.toDouble() ?: 0.0,
            p2 = distortion?.getOrNull(4)?.toDouble() ?: 0.0,
            k3 = distortion?.getOrNull(2)?.toDouble() ?: 0.0,
            imageWidth = outputWidth,
            imageHeight = outputHeight,
        )
    }
}
