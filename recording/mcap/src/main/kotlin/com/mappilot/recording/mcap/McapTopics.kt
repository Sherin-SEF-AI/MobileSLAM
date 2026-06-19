package com.mappilot.recording.mcap

/** MCAP topic names (§6.2), with the conventional leading slash. */
internal object Topics {
    const val CAMERA = "/camera"
    const val GPS_FIX = "/gps/fix"
    const val GPS_RAW = "/gps/raw"
    const val GPS_SAT = "/gps/sat"
    const val IMU_ACCEL = "/imu/accel"
    const val IMU_GYRO = "/imu/gyro"
    const val IMU_MAG = "/imu/mag"
    const val IMU_LINEAR_ACCEL = "/imu/linear_accel"
    const val IMU_GRAVITY = "/imu/gravity"
    const val IMU_ROTATION = "/imu/rotation_vector"
    const val POSE = "/pose"
    const val POSE_ENU = "/pose/enu"
    const val LANDMARKS = "/landmarks"
    const val ASSETS = "/assets"
    const val EVENTS = "/events"
    const val CALIBRATION = "/calibration"

    const val MESSAGE_ENCODING = "protobuf"
    const val SCHEMA_ENCODING = "protobuf"
}
