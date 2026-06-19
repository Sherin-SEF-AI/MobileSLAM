package com.mappilot.sensors.imu

import android.hardware.Sensor
import com.mappilot.core.model.ImuChannel
import com.mappilot.core.model.StreamIds

/** Maps the tri-axis IMU channels to their Android sensor type and MCAP stream id. */
internal enum class ImuStream(
    val channel: ImuChannel,
    val sensorType: Int,
    val streamId: String,
) {
    ACCEL(ImuChannel.ACCEL, Sensor.TYPE_ACCELEROMETER, StreamIds.IMU_ACCEL),
    GYRO(ImuChannel.GYRO, Sensor.TYPE_GYROSCOPE, StreamIds.IMU_GYRO),
    MAG(ImuChannel.MAG, Sensor.TYPE_MAGNETIC_FIELD, StreamIds.IMU_MAG),
    LINEAR_ACCEL(ImuChannel.LINEAR_ACCEL, Sensor.TYPE_LINEAR_ACCELERATION, StreamIds.IMU_LINEAR_ACCEL),
    GRAVITY(ImuChannel.GRAVITY, Sensor.TYPE_GRAVITY, StreamIds.IMU_GRAVITY);

    companion object {
        fun forSensorType(type: Int): ImuStream? = entries.firstOrNull { it.sensorType == type }
    }
}
