package com.mappilot.export

import com.mappilot.core.model.Asset
import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.GeoPoint
import java.io.File
import kotlin.random.Random
import org.junit.Test

/** Writes reference export files to disk for validation by external tooling. */
class GoldenExportTest {

    @Test
    fun `write golden ply pcd geojson for reference-tool validation`() {
        val rnd = Random(7)
        val points = List(500) {
            ExportPoint(rnd.nextDouble(-10.0, 10.0), rnd.nextDouble(-10.0, 10.0), rnd.nextDouble(-2.0, 2.0), rnd.nextFloat())
        }
        val tmp = System.getProperty("java.io.tmpdir")
        File(tmp, "mappilot-golden.ply").outputStream().use { PlyWriter.writeAscii(it, points) }
        File(tmp, "mappilot-golden-bin.ply").outputStream().use { PlyWriter.writeBinaryLittleEndian(it, points) }
        File(tmp, "mappilot-golden.pcd").outputStream().use { PcdWriter.writeAscii(it, points) }

        val assets = List(8) { i ->
            Asset(i.toLong(), AssetClass.entries[i % AssetClass.entries.size], GeoPoint(12.97 + i * 0.001, 77.59 + i * 0.001, 920.0),
                BoundingBox(0f, 0f, 1f, 1f), 0.8f, i.toLong(), 5f, null)
        }
        val traj = (0..20).map { GeoPoint(12.97 + it * 0.0005, 77.59 + it * 0.0005, 920.0) }
        File(tmp, "mappilot-golden.geojson").writeText(GeoJsonExporter.export(traj, assets))
        File(tmp, "mappilot-golden-traj.csv").writeText(CsvExporter.trajectory(traj))
        println("GOLDEN_EXPORT_DIR=$tmp")
    }
}
