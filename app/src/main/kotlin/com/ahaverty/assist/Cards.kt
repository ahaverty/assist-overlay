package com.ahaverty.assist

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** `![Front garden](ha:camera.front_garden)` on its own line: a picture or card instead of text. */
val HA_EMBED = Regex("""^!\[([^\]]*)]\((ha:[a-z_]+\.[a-z0-9_]+|https?://\S+)\)$""")

@Composable
fun Embed(name: String, target: String) {
    val entity = target.removePrefix("ha:")
    when {
        !target.startsWith("ha:") -> WebImage(name, target)
        entity.startsWith("camera.") -> CameraCard(name, entity)
        else -> StateCard(name, entity)
    }
}

@Composable
private fun CameraCard(name: String, entity: String) {
    var refresh by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(entity, refresh) {
        failed = false
        image = runCatching {
            val bytes = App.ha.get("/api/camera_proxy/$entity")
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }
        }.onFailure { failed = true }.getOrNull() ?: image
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { refresh++ },
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
        if (image == null && !failed) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        if (failed && image == null) Text("Couldn't load $name", style = MaterialTheme.typography.bodySmall)
        Text(
            "$name · tap to refresh",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun WebImage(name: String, url: String) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = runCatching {
            withContext(Dispatchers.IO) {
                App.auth.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    val b = r.body!!.bytes()
                    BitmapFactory.decodeByteArray(b, 0, b.size).asImageBitmap()
                }
            }
        }.getOrNull()
    }
    image?.let { Image(it, contentDescription = name, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.FillWidth) }
}

private data class CardData(val title: String, val value: String, val detail: String, val points: List<Float>)

@Composable
private fun StateCard(name: String, entity: String) {
    var data by remember { mutableStateOf<CardData?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(entity) {
        data = runCatching { loadCard(name, entity) }.onFailure { failed = true }.getOrNull()
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        val d = data
        if (d == null) {
            Text(if (failed) "Couldn't load $name" else "Loading $name…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
            return@Surface
        }
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(d.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                if (d.detail.isNotBlank()) Text(d.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (d.points.size > 2) {
                Spacer(Modifier.width(12.dp))
                Sparkline(d.points, MaterialTheme.colorScheme.primary, Modifier.width(130.dp).height(52.dp))
            }
        }
    }
}

@Composable
private fun Sparkline(points: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val lo = points.min()
        val hi = points.max().coerceAtLeast(lo + 0.1f)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = size.width * i / (points.size - 1)
            val y = size.height * (1 - (v - lo) / (hi - lo))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
        val last = points.last()
        drawCircle(color, 3.5.dp.toPx(), Offset(size.width, size.height * (1 - (last - lo) / (hi - lo))))
    }
}

private suspend fun loadCard(name: String, entity: String): CardData {
    val s = JSONObject(String(App.ha.get("/api/states/$entity")))
    val attrs = s.optJSONObject("attributes") ?: JSONObject()
    val state = s.optString("state")
    val title = name.ifBlank { attrs.optString("friendly_name", entity) }
    if (entity.startsWith("weather.")) {
        val unit = attrs.optString("temperature_unit", "°C")
        val bits = listOfNotNull(
            attrs.optDouble("humidity").takeIf { !it.isNaN() }?.let { "humidity ${it.toInt()}%" },
            attrs.optDouble("wind_speed").takeIf { !it.isNaN() }?.let { "wind ${it.toInt()} ${attrs.optString("wind_speed_unit", "km/h")}" },
        )
        return CardData(title, "${attrs.optDouble("temperature").let { if (it.isNaN()) "" else "${it.toInt()}$unit " }}${state.replace('-', ' ').replace("partlycloudy", "partly cloudy")}", bits.joinToString(", "), emptyList())
    }
    val unit = attrs.optString("unit_of_measurement")
    val since = Instant.now().minus(3, ChronoUnit.HOURS).toString()
    val points = if (state.toFloatOrNull() == null) emptyList() else runCatching {
        val arr = JSONArray(String(App.ha.get("/api/history/period/$since?filter_entity_id=$entity&minimal_response&no_attributes")))
        val series = arr.optJSONArray(0) ?: JSONArray()
        (0 until series.length()).mapNotNull { series.getJSONObject(it).optString("state").toFloatOrNull() }
    }.getOrDefault(emptyList())
    val detail = if (points.size > 2) "last 3 hours, ${"%.1f".format(points.min())} to ${"%.1f".format(points.max())}" else ""
    return CardData(title, "$state${if (unit.isNotBlank()) " $unit" else ""}", detail, points)
}
