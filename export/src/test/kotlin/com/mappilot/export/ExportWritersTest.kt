package com.mappilot.export

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.Asset
import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.GeoPoint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class ExportWritersTest {

    private val points = listOf(
        ExportPoint(1.0, 2.0, 3.0, 0.9f),
        ExportPoint(-4.5, 5.5, -6.5, 0.4f),
        ExportPoint(0.0, 0.0, 0.0, 1.0f),
    )

    @Test
    fun `ply ascii header declares correct vertex count and properties`() {
        val out = ByteArrayOutputStream()
        PlyWriter.writeAscii(out, points)
        val text = out.toString("US-ASCII")
        val lines = text.lines()
        assertThat(lines[0]).isEqualTo("ply")
        assertThat(lines).contains("format ascii 1.0")
        assertThat(lines).contains("element vertex 3")
        assertThat(lines).contains("property float x")
        assertThat(lines).contains("property float confidence")
        // First data row after end_header
        val dataStart = lines.indexOf("end_header") + 1
        assertThat(lines[dataStart]).isEqualTo("1.0 2.0 3.0 0.9")
    }

    @Test
    fun `ply binary little-endian round-trips coordinates`() {
        val out = ByteArrayOutputStream()
        PlyWriter.writeBinaryLittleEndian(out, points)
        val bytes = out.toByteArray()
        val headerEnd = String(bytes, Charsets.US_ASCII).indexOf("end_header\n") + "end_header\n".length
        val buf = ByteBuffer.wrap(bytes, headerEnd, bytes.size - headerEnd).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buf.float).isWithin(1e-6f).of(1.0f)  // x0
        assertThat(buf.float).isWithin(1e-6f).of(2.0f)  // y0
        assertThat(buf.float).isWithin(1e-6f).of(3.0f)  // z0
        assertThat(buf.float).isWithin(1e-6f).of(0.9f)  // conf0
    }

    @Test
    fun `pcd ascii header is a valid v0_7 header`() {
        val out = ByteArrayOutputStream()
        PcdWriter.writeAscii(out, points)
        val lines = out.toString("US-ASCII").lines()
        assertThat(lines).contains("VERSION 0.7")
        assertThat(lines).contains("FIELDS x y z intensity")
        assertThat(lines).contains("WIDTH 3")
        assertThat(lines).contains("POINTS 3")
        assertThat(lines).contains("DATA ascii")
        val dataStart = lines.indexOf("DATA ascii") + 1
        assertThat(lines[dataStart]).isEqualTo("1.0 2.0 3.0 0.9")
    }

    private fun asset(id: Long) = Asset(
        id = id, assetClass = AssetClass.POTHOLE, geo = GeoPoint(12.97, 77.59, 920.0),
        box = BoundingBox(0f, 0f, 1f, 1f), confidence = 0.8f, sourceFrameId = 5, depthM = 4f, embeddingId = null,
    )

    @Test
    fun `geojson export contains trajectory linestring and asset points`() {
        val json = GeoJsonExporter.export(
            trajectory = listOf(GeoPoint(12.97, 77.59, 920.0), GeoPoint(12.98, 77.60, 921.0)),
            assets = listOf(asset(1)),
        )
        assertThat(json).startsWith("{\"type\":\"FeatureCollection\"")
        assertThat(json).contains("\"type\":\"LineString\"")
        assertThat(json).contains("\"type\":\"Point\"")
        assertThat(json).contains("\"class\":\"POTHOLE\"")
        assertThat(json).contains("[77.59,12.97,920.0]")
    }

    @Test
    fun `csv exporters are well-formed`() {
        val traj = CsvExporter.trajectory(listOf(GeoPoint(12.97, 77.59, 920.0))).trim().lines()
        assertThat(traj[0]).isEqualTo("lat,lon,alt")
        assertThat(traj[1]).isEqualTo("12.97,77.59,920.0")

        val assets = CsvExporter.assets(listOf(asset(7))).trim().lines()
        assertThat(assets[0]).isEqualTo("id,class,lat,lon,alt,confidence,depth_m,source_frame_id")
        assertThat(assets[1]).startsWith("7,POTHOLE,12.97,77.59,920.0,0.8,4.0,5")
    }

    @Test
    fun `cloud-only formats are flagged not device-native`() {
        assertThat(ExportFormat.OPENDRIVE.deviceNative).isFalse()
        assertThat(ExportFormat.LANELET2.deviceNative).isFalse()
        assertThat(ExportFormat.GLTF.deviceNative).isFalse()
        assertThat(ExportFormat.PLY.deviceNative).isTrue()
        assertThat(ExportFormat.deviceFormats).containsExactly(
            ExportFormat.MCAP, ExportFormat.GEOJSON, ExportFormat.PLY, ExportFormat.PCD, ExportFormat.CSV,
        )
    }
}
