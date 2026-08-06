package com.example.aiglasses.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.SavedImage
import com.example.aiglasses.ui.components.Eyebrow
import com.example.aiglasses.ui.components.GlassesGlyph
import com.example.aiglasses.ui.theme.ErrorRed
import com.example.aiglasses.ui.theme.MonoData
import com.example.aiglasses.ui.theme.OutlineGray
import com.example.aiglasses.ui.theme.PanelHigh
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextSecondary
import com.example.aiglasses.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One capture-day section of the grid. */
private data class DayGroup(val label: String, val items: List<SavedImage>)

@Composable
fun GalleryScreen(viewModel: MainViewModel) {
    val savedImages by viewModel.savedImages.collectAsStateWithLifecycle()
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    val groups = remember(savedImages) { groupByDay(savedImages) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Text(
            text = "Gallery",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp)
        )

        if (savedImages.isEmpty()) {
            EmptyGallery()
        } else {
            // Edge-to-edge 3-column grid, 2dp gutters, no thumb chrome (§6).
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)
            ) {
                groups.forEach { group ->
                    item(key = "header_${group.label}", span = { GridItemSpan(maxLineSpan) }) {
                        Eyebrow(
                            text = group.label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    group.items.forEachIndexed { indexInGroup, image ->
                        item(key = image.filename) {
                            GalleryThumbnail(
                                image = image,
                                viewModel = viewModel,
                                appearIndex = indexInGroup,
                                onClick = {
                                    val idx = savedImages.indexOfFirst { it.filename == image.filename }
                                    if (idx >= 0) viewerIndex = idx
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    viewerIndex?.let { startIndex ->
        GalleryViewer(
            images = savedImages,
            startIndex = startIndex,
            viewModel = viewModel,
            onDismiss = { viewerIndex = null }
        )
    }
}

@Composable
private fun EmptyGallery() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlassesGlyph(color = OutlineGray, width = 96.dp, height = 36.dp)
            Text(
                text = "No photos yet",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun GalleryThumbnail(
    image: SavedImage,
    viewModel: MainViewModel,
    appearIndex: Int,
    onClick: () -> Unit
) {
    // Decode off the main thread — full-size decodes in composition jank the grid.
    val bitmap = produceState<Bitmap?>(initialValue = null, image.filename) {
        value = withContext(Dispatchers.IO) { loadBitmap(viewModel, image) }
    }.value

    // Thumb appear: fade 220ms + scale 0.94→1 spring, staggered ≤6 items (§6).
    val appearAlpha = remember { Animatable(0f) }
    val appearScale = remember { Animatable(0.94f) }
    LaunchedEffect(image.filename) {
        delay((appearIndex.coerceAtMost(6)) * 30L)
        launch { appearAlpha.animateTo(1f, tween(220)) }
        appearScale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f))
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .alpha(appearAlpha.value)
            .scale(appearScale.value)
            .background(PanelHigh)
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.filename,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (image.isVideo) {
            Icon(
                Icons.Outlined.PlayArrow,
                contentDescription = "Video",
                tint = TextPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(4.dp)
            )
        }
    }
}

/**
 * Full-screen viewer: pure black, immersive, metadata in mono (§6). Swipe
 * left/right between items, swipe down (or the close button) to dismiss.
 * Renders in its own window (Dialog) so it covers the bottom dock.
 */
@Composable
private fun GalleryViewer(
    images: List<SavedImage>,
    startIndex: Int,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, images.size - 1)
    ) { images.size }
    var dragOffset by remember { mutableStateOf(0f) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // The list can shrink underneath us (delete elsewhere) — never index past it.
    val current = images.getOrNull(pagerState.currentPage.coerceIn(0, images.size - 1))

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragOffset > 220f) onDismiss() else dragOffset = 0f },
                        onDragCancel = { dragOffset = 0f },
                        onVerticalDrag = { _, amount ->
                            dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                        }
                    )
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = dragOffset }
            ) { page ->
                images.getOrNull(page)?.let { item ->
                    ViewerPage(item = item, viewModel = viewModel)
                }
            }

            // Top bar: position (mono) + actions
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pagerState.currentPage + 1}/${images.size}",
                    style = MonoData,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { current?.let { shareItem(context, viewModel, it) } }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share", tint = TextSecondary)
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = TextSecondary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
            }

            // Bottom metadata — mono telemetry
            current?.let { item ->
                val dims = remember(item.filename) {
                    if (item.isVideo) null else imageDimensions(viewModel, item)
                }
                val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = dateFormat.format(Date(item.timestamp)),
                        style = MonoData,
                        color = TextSecondary
                    )
                    Text(
                        text = buildString {
                            append(formatSize(item.sizeBytes))
                            if (dims != null) append(" · ${dims.first}x${dims.second}")
                            if (item.isVideo) append(" · video")
                        },
                        style = MonoData,
                        color = TextTertiary
                    )
                }
            }

            if (showDeleteConfirm && current != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = {
                        Text(
                            text = if (current.isVideo) "Delete video?" else "Delete photo?",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    },
                    text = {
                        Text(
                            "This cannot be undone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteImage(current.filename)
                            showDeleteConfirm = false
                            onDismiss()
                        }) {
                            Text("Delete", color = ErrorRed)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    },
                    containerColor = PanelHigh,
                    shape = MaterialTheme.shapes.large
                )
            }
        }
    }
}

@Composable
private fun ViewerPage(
    item: SavedImage,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val bitmap = produceState<Bitmap?>(initialValue = null, item.filename) {
        value = withContext(Dispatchers.IO) { loadBitmap(viewModel, item) }
    }.value

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.filename,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 72.dp)
            )
        }
        if (item.isVideo) {
            OutlinedButton(
                onClick = {
                    val file = viewModel.getImageFile(item.filename)
                    try {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Play video", color = TextPrimary)
            }
        }
    }
}

// ── Helpers ──

private fun groupByDay(images: List<SavedImage>): List<DayGroup> {
    if (images.isEmpty()) return emptyList()
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = dayFormat.format(Date())
    val yesterday = dayFormat.format(
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
    )
    return images
        .groupBy { dayFormat.format(Date(it.timestamp)) }
        .entries
        .sortedByDescending { it.key }
        .map { (day, items) ->
            val label = when (day) {
                today -> "Today · $day"
                yesterday -> "Yesterday · $day"
                else -> day
            }
            DayGroup(label, items)
        }
}

private fun loadBitmap(viewModel: MainViewModel, image: SavedImage): Bitmap? {
    val file = viewModel.getImageFile(image.filename)
    if (!file.exists()) return null
    return if (image.isVideo) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    } else {
        try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }
}

/** Pixel dimensions without decoding the full bitmap. */
private fun imageDimensions(viewModel: MainViewModel, image: SavedImage): Pair<Int, Int>? {
    val file = viewModel.getImageFile(image.filename)
    if (!file.exists()) return null
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    } catch (_: Exception) {
        null
    }
}

private fun formatSize(bytes: Int): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else "${bytes / 1024} KB"

private fun shareItem(context: android.content.Context, viewModel: MainViewModel, item: SavedImage) {
    try {
        val file = viewModel.getImageFile(item.filename)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (item.isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    } catch (_: Exception) {
    }
}
