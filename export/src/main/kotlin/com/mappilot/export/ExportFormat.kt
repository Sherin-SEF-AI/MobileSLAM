package com.mappilot.export

/**
 * Export formats. Device-native formats are written on-device; cloud-only formats
 * require the offline pipeline (mesh reconstruction, map generation) and are
 * **dispatched as jobs, never fabricated locally** (§1, §7).
 */
enum class ExportFormat(val extension: String, val deviceNative: Boolean) {
    MCAP("mcap", true),
    GEOJSON("geojson", true),
    PLY("ply", true),
    PCD("pcd", true),
    CSV("csv", true),

    // Cloud-only: produced by the backend pipeline, dispatched as jobs.
    OBJ("obj", false),
    GLTF("gltf", false),
    OPENDRIVE("xodr", false),
    LANELET2("osm", false),
    MBTILES("mbtiles", false),
    PARQUET("parquet", false);

    companion object {
        val deviceFormats get() = entries.filter { it.deviceNative }
        val cloudFormats get() = entries.filter { !it.deviceNative }
    }
}

/** A dispatched cloud-export job. Carries no fabricated geometry — only intent + state. */
data class CloudJobDescriptor(
    val format: ExportFormat,
    val tripId: Long,
    val state: String = "QUEUED",
    val note: String,
)

/** The result of an export request. */
data class ExportResult(
    val deviceFiles: List<String>,
    val cloudJobs: List<CloudJobDescriptor>,
)
