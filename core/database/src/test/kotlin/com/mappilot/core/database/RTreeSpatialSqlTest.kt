package com.mappilot.core.database

import com.google.common.truth.Truth.assertThat
import java.sql.DriverManager
import org.junit.Test

/**
 * Proves the production R*Tree spatial SQL against a real SQLite that has the
 * rtree module compiled in (xerial sqlite-jdbc) — the same SQL strings
 * ([MapPilotDatabase.SPATIAL_DDL] + [SpatialQueries.assetBoxSql]) the app runs on
 * device. This validates the index path independently of the Android runtime.
 */
class RTreeSpatialSqlTest {

    private fun connect() = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun seed(conn: java.sql.Connection) {
        conn.createStatement().use { st ->
            // Minimal assets table matching the columns the triggers reference.
            st.execute(
                "CREATE TABLE assets(id INTEGER PRIMARY KEY AUTOINCREMENT, assetClass TEXT, lat REAL, lon REAL)",
            )
            // Only the asset DDL applies here (gnss/landmark base tables not created in this test).
            MapPilotDatabase.SPATIAL_DDL.filter { it.contains("asset") }.forEach(st::execute)
        }
        // Bengaluru-ish points at increasing eastward offset.
        conn.prepareStatement("INSERT INTO assets(assetClass, lat, lon) VALUES (?,?,?)").use { ps ->
            data class P(val cls: String, val lat: Double, val lon: Double)
            listOf(
                P("POTHOLE", 12.9716, 77.5946),       // center
                P("POTHOLE", 12.9716, 77.5951),       // ~54 m east
                P("TRAFFIC_LIGHT", 12.9716, 77.6100), // ~1.7 km east
                P("POTHOLE", 12.9900, 77.5946),       // ~2 km north
            ).forEach { p ->
                ps.setString(1, p.cls); ps.setDouble(2, p.lat); ps.setDouble(3, p.lon); ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Test
    fun `rtree module is available in the test sqlite`() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE VIRTUAL TABLE t USING rtree(id, minX, maxX, minY, maxY)")
            }
        }
    }

    @Test
    fun `rtree bbox query returns only assets inside the box`() {
        connect().use { conn ->
            seed(conn)
            // Box ~500 m around the center.
            val box = SpatialQueries.boundingBox(12.9716, 77.5946, 500.0)
            val minLat = box[0]; val maxLat = box[1]; val minLon = box[2]; val maxLon = box[3]
            val sql = SpatialQueries.assetBoxSql(useRtree = true, withClass = false)
            conn.prepareStatement(sql).use { ps ->
                ps.setDouble(1, minLon); ps.setDouble(2, maxLon); ps.setDouble(3, minLat); ps.setDouble(4, maxLat)
                ps.executeQuery().use { rs ->
                    val ids = generateSequence { if (rs.next()) rs.getLong("id") else null }.toList()
                    // Only the two nearby points (center + 54 m east) fall in the 500 m box.
                    assertThat(ids).hasSize(2)
                }
            }
        }
    }

    @Test
    fun `rtree query with class filter narrows results`() {
        connect().use { conn ->
            seed(conn)
            val sql = SpatialQueries.assetBoxSql(useRtree = true, withClass = true)
            // Wide box covering everything, filter to TRAFFIC_LIGHT.
            conn.prepareStatement(sql).use { ps ->
                ps.setDouble(1, 77.0); ps.setDouble(2, 78.0); ps.setDouble(3, 12.0); ps.setDouble(4, 13.0)
                ps.setString(5, "TRAFFIC_LIGHT")
                ps.executeQuery().use { rs ->
                    var n = 0
                    while (rs.next()) { n++; assertThat(rs.getString("assetClass")).isEqualTo("TRAFFIC_LIGHT") }
                    assertThat(n).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `fallback bbox SQL agrees with rtree results`() {
        connect().use { conn ->
            seed(conn)
            val box = SpatialQueries.boundingBox(12.9716, 77.5946, 500.0)
            fun run(sql: String): Int {
                conn.prepareStatement(sql).use { ps ->
                    ps.setDouble(1, box[2]); ps.setDouble(2, box[3]); ps.setDouble(3, box[0]); ps.setDouble(4, box[1])
                    ps.executeQuery().use { rs ->
                        var n = 0; while (rs.next()) n++; return n
                    }
                }
            }
            assertThat(run(SpatialQueries.assetBoxSql(true, false)))
                .isEqualTo(run(SpatialQueries.assetBoxSql(false, false)))
        }
    }
}
