package app.gimmiedat.ui

import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val thumbShape = RoundedCornerShape(6.dp)

/** 16:9 thumbnail from the web, dithered while it loads, a glyph if it never does. */
@Composable
fun RemoteThumb(url: String?, modifier: Modifier = Modifier, glyph: String = "▶") {
    val palette = LocalPalette.current
    var failed by remember(url) { mutableStateOf(url == null) }
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(thumbShape)
            .background(rememberDitherBrush(palette.faint))
            .border(1.dp, palette.line, thumbShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { if (it is AsyncImagePainter.State.Error) failed = true },
            )
        }
        if (failed) Text(glyph, style = Type.title, color = palette.gray)
    }
}

private val localThumbs = LruCache<Uri, ImageBitmap>(40)

/**
 * The saved file's own thumbnail (never expires, unlike CDN links), falling back to the
 * remote one, audio files without embedded art end up there.
 */
@Composable
fun FileThumb(uri: Uri?, fallbackUrl: String?, modifier: Modifier = Modifier, glyph: String = "▶") {
    val context = LocalContext.current
    val bitmap by produceState(uri?.let { localThumbs.get(it) }, uri) {
        if (uri == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(480, 270), null).asImageBitmap()
            }.getOrNull()
        }?.also { localThumbs.put(uri, it) }
    }
    val palette = LocalPalette.current
    val image = bitmap
    if (image != null) {
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .aspectRatio(16f / 9f)
                .clip(thumbShape)
                .border(1.dp, palette.line, thumbShape),
        )
    } else {
        RemoteThumb(fallbackUrl, modifier, glyph)
    }
}
