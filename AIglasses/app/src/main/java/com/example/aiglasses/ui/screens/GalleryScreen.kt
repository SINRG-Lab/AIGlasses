package com.example.aiglasses.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.SavedImage
import com.example.aiglasses.ui.components.AmbientBackground
import com.example.aiglasses.ui.components.GlassCard
import com.example.aiglasses.ui.theme.BgMid
import com.example.aiglasses.ui.theme.GlassSurface
import com.example.aiglasses.ui.theme.Red
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextSecondary
import com.example.aiglasses.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(viewModel: MainViewModel) {
    val savedImages by viewModel.savedImages.collectAsStateWithLifecycle()
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(connectionState = glassesStatus.connectionState)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 56.dp, bottom = 100.dp)
        ) {
            Text(
                text = "Gallery",
                style = MaterialTheme.typography.displayMedium,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Photos captured by your glasses",
                fontSize = 15.sp,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            if (savedImages.isEmpty()) {
                GlassCard(depth = 1, cornerRadius = 14.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No photos yet — press the button on your glasses to capture one",
                            fontSize = 14.sp,
                            color = TextTertiary
                        )
                    }
                }
            } else {
                val videoCount = savedImages.count { it.isVideo }
                val imageCount = savedImages.size - videoCount
                val label = buildString {
                    if (imageCount > 0) append("$imageCount PHOTO${if (imageCount != 1) "S" else ""}")
                    if (imageCount > 0 && videoCount > 0) append("  ·  ")
                    if (videoCount > 0) append("$videoCount VIDEO${if (videoCount != 1) "S" else ""}")
                }
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = TextTertiary,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(savedImages, key = { it.filename }) { image ->
                        GalleryThumbnail(
                            image = image,
                            viewModel = viewModel,
                            onClick = {
                                val idx = savedImages.indexOfFirst { it.filename == image.filename }
                                if (idx >= 0) viewerIndex = idx
                            }
                        )
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
}

@Composable
private fun GalleryThumbnail(
    image: SavedImage,
    viewModel: MainViewModel,
    onClick: () -> Unit
) {
    // Decode off the main thread — full-size decodes in composition jank the grid.
    val bitmap = produceState<Bitmap?>(initialValue = null, image.filename) {
        value = withContext(Dispatchers.IO) { loadBitmap(viewModel, image) }
    }.value
    val timeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    GlassCard(depth = 1, cornerRadius = 12.dp, onClick = onClick) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = image.filename,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(GlassSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (image.isVideo) "▶" else "?",
                            fontSize = 24.sp,
                            color = TextTertiary
                        )
                    }
                }
                if (image.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "▶  VIDEO", fontSize = 10.sp, color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormat.format(Date(image.timestamp)),
                    fontSize = 10.sp,
                    color = TextTertiary
                )
                Text(
                    text = formatSize(image.sizeBytes),
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }
        }
    }
}

/**
 * Full-screen pager viewer: swipe left/right between items, swipe down (or
 * back / the close button) to dismiss. Renders in its own window (Dialog) so
 * it covers the bottom dock.
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
                .background(Color.Black.copy(alpha = 0.96f))
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

            // Top bar: position + actions
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
                    text = "${pagerState.currentPage + 1} of ${images.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { current?.let { shareItem(context, viewModel, it) } }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = TextSecondary)
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TextSecondary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
            }

            // Bottom labels: date, size, dimensions
            current?.let { item ->
                val dims = remember(item.filename) {
                    if (item.isVideo) null else imageDimensions(viewModel, item)
                }
                val dateFormat = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = dateFormat.format(Date(item.timestamp)),
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = buildString {
                            append(formatSize(item.sizeBytes))
                            if (dims != null) append("  ·  ${dims.first} × ${dims.second}")
                            if (item.isVideo) append("  ·  video")
                        },
                        fontSize = 12.sp,
                        color = TextTertiary
                    )
                }
            }

            if (showDeleteConfirm && current != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = {
                        Text(if (current.isVideo) "Delete Video?" else "Delete Photo?", color = TextPrimary)
                    },
                    text = { Text("This cannot be undone.", color = TextSecondary) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteImage(current.filename)
                            showDeleteConfirm = false
                            onDismiss()
                        }) {
                            Text("Delete", color = Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel", color = TextTertiary)
                        }
                    },
                    containerColor = BgMid,
                    shape = RoundedCornerShape(16.dp)
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
            Button(
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
                modifier = Modifier.align(Alignment.Center),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
            ) {
                Text("▶  Play Video", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Helpers ──

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
