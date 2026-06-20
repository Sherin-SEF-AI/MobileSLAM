package com.mappilot.viz.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mappilot.core.model.Asset
import com.mappilot.core.model.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Renders the recorded trajectory + assets on a MapLibre Native map. Layers are
 * built from real GeoJSON ([MapGeoJson]); the matte dark style uses no external
 * tiles so it works fully offline. Asset density is shown as a heatmap; assets
 * as colour-coded circles; the trajectory as a signal-green line.
 */
@Composable
fun MapLibreMapView(
    trajectory: List<GeoPoint>,
    assets: List<Asset>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(modifier = modifier, factory = { mapView }, update = { view ->
        view.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(MapGeoJson.DARK_STYLE)) { style ->
                applyLayers(style, trajectory, assets)
                centerOn(map, trajectory, assets)
            }
        }
    })
}

private fun applyLayers(style: Style, trajectory: List<GeoPoint>, assets: List<Asset>) {
    style.addSource(GeoJsonSource(SRC_TRAJ, MapGeoJson.trajectoryLineString(trajectory)))
    style.addLayer(
        LineLayer(LAYER_TRAJ, SRC_TRAJ).withProperties(
            PropertyFactory.lineColor("#00E676"),
            PropertyFactory.lineWidth(3f),
        ),
    )
    val assetGeoJson = MapGeoJson.assetsFeatureCollection(assets)
    style.addSource(GeoJsonSource(SRC_ASSETS, assetGeoJson))
    style.addLayer(
        HeatmapLayer(LAYER_HEAT, SRC_ASSETS).withProperties(
            PropertyFactory.heatmapRadius(24f),
            PropertyFactory.heatmapOpacity(0.6f),
        ),
    )
    style.addLayer(
        CircleLayer(LAYER_ASSETS, SRC_ASSETS).withProperties(
            PropertyFactory.circleColor("#FFB300"),
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleStrokeColor("#0A0A0A"),
            PropertyFactory.circleStrokeWidth(1f),
        ),
    )
}

private fun centerOn(map: org.maplibre.android.maps.MapLibreMap, trajectory: List<GeoPoint>, assets: List<Asset>) {
    val pts = trajectory.map { LatLng(it.latitude, it.longitude) } +
        assets.map { LatLng(it.geo.latitude, it.geo.longitude) }
    when {
        pts.size >= 2 -> map.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().includes(pts).build(), 80,
            ),
        )
        pts.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(pts.first(), 17.0))
        else -> Unit
    }
}

private const val SRC_TRAJ = "src-trajectory"
private const val SRC_ASSETS = "src-assets"
private const val LAYER_TRAJ = "layer-trajectory"
private const val LAYER_ASSETS = "layer-assets"
private const val LAYER_HEAT = "layer-asset-heatmap"
