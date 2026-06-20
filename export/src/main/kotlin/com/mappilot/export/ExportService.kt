package com.mappilot.export

import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.database.MapPilotRepository
import com.mappilot.core.model.GeoPoint
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces export artifacts for a trip. Device-native formats are written inline
 * from real recorded data (DB landmarks/assets + the trajectory sidecar); cloud
 * formats are dispatched as jobs and never fabricated on-device.
 */
@Singleton
class ExportService @Inject constructor(
    private val repository: MapPilotRepository,
) {
    /** Parse the trajectory `[lon,lat,alt]` vertices from a trip's GeoJSON sidecar. */
    private val coord = Regex("""\[\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)(?:\s*,\s*(-?\d+\.?\d*))?\s*]""")

    suspend fun export(tripId: Long, outDir: File, formats: Set<ExportFormat>): ExportResult {
        val trip = repository.tripById(tripId) ?: return ExportResult(emptyList(), emptyList())
        outDir.mkdirs()

        val assets = repository.assetsForTrip(tripId)
        val landmarks = repository.landmarksForTrip(tripId)
        val trajectory = readTrajectory(trip.mcapPath)
        val points = landmarks.toExportPoints(useGeo = false) // metric VIO cloud for CloudCompare/PCL

        val files = ArrayList<String>()
        val jobs = ArrayList<CloudJobDescriptor>()

        for (format in formats) {
            if (!format.deviceNative) {
                jobs.add(dispatchCloud(tripId, format))
                continue
            }
            when (format) {
                ExportFormat.MCAP -> files.add(trip.mcapPath) // already produced; referenced, not rewritten
                ExportFormat.GEOJSON -> write(outDir, "trip_$tripId.geojson", GeoJsonExporter.export(trajectory, assets), files)
                ExportFormat.PLY -> File(outDir, "cloud_$tripId.ply").also {
                    it.outputStream().use { os -> PlyWriter.writeAscii(os, points) }; files.add(it.absolutePath)
                }
                ExportFormat.PCD -> File(outDir, "cloud_$tripId.pcd").also {
                    it.outputStream().use { os -> PcdWriter.writeAscii(os, points) }; files.add(it.absolutePath)
                }
                ExportFormat.CSV -> {
                    write(outDir, "trajectory_$tripId.csv", CsvExporter.trajectory(trajectory), files)
                    write(outDir, "assets_$tripId.csv", CsvExporter.assets(assets), files)
                }
                else -> Unit
            }
        }
        Log.i(Streams.EXPORT, "Exported trip $tripId: ${files.size} files, ${jobs.size} cloud jobs")
        return ExportResult(files, jobs)
    }

    private fun dispatchCloud(tripId: Long, format: ExportFormat): CloudJobDescriptor {
        // Records intent; the actual upload + processing is the cloud pipeline (Phase 8).
        return CloudJobDescriptor(
            format = format,
            tripId = tripId,
            state = "QUEUED",
            note = "${format.name} requires cloud processing; dispatched as a job",
        )
    }

    private fun write(dir: File, name: String, content: String, into: MutableList<String>) {
        val f = File(dir, name)
        f.writeText(content)
        into.add(f.absolutePath)
    }

    private fun readTrajectory(mcapPath: String): List<GeoPoint> {
        val sidecar = File(File(mcapPath).parentFile, "trajectory.geojson")
        if (!sidecar.exists()) return emptyList()
        val text = sidecar.readText()
        val idx = text.indexOf("LineString")
        if (idx < 0) return emptyList()
        return coord.findAll(text.substring(idx)).map { m ->
            GeoPoint(m.groupValues[2].toDouble(), m.groupValues[1].toDouble(), m.groupValues[3].toDoubleOrNull() ?: 0.0)
        }.toList()
    }
}
