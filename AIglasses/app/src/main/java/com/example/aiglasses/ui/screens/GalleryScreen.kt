package com.example.aiglasses.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.SavedImage
import com.example.aiglasses.ui.components.AmbientBackground
import com.example.aiglasses.ui.components.GlassCard
import com.example.aiglasses.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(viewModel: MainViewModel) {
    val savedImages by viewModel.savedImages.collectAsStateWithLifecycle()
    val autoSave by viewModel.autoSaveImages.collectAsStateWithLifecycle()
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    var selectedImage by remember { mutableStateOf<SavedImage?>(null) }

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
                text = "Captured images and videos from your glasses",
                fontSize = 15.sp,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            GlassCard(depth = 1, cornerRadius = 14.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Auto-Save Images",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Save camera captures automatically",
                            fontSize = 12.sp,
                            color = TextTertiary
                        )
                    }
                    Switch(
                        checked = autoSave,
                        onCheckedChange = { viewModel.setAutoSaveImages(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Blue,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = GlassSurface
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (savedImages.isEmpty()) {
                GlassCard(depth = 1, cornerRadius = 14.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (autoSave) "No images yet — captures will appear here"
                            else "Enable auto-save to start collecting images",
                            fontSize = 14.sp,
                            color = TextTertiary
                        )
                    }
                }
            } else {
                val videoCount = savedImages.count { it.isVideo }
                val imageCount = savedImages.size - videoCount
                val label = buildString {
                    if (imageCount > 0) append("$imageCount IMAGE${if (imageCount != 1) "S" else ""}")
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
                            onClick = { selectedImage = image },
                            onDelete = { viewModel.deleteImage(image.filename) }
                        )
                    }
                }
            }
        }

        // Full-screen image viewer overlay
        AnimatedVisibility(
            visible = selectedImage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            selectedImage?.let { image ->
                ImageViewer(
                    image = image,
                    viewModel = viewModel,
                    onDismiss = { selectedImage = null },
                    onDelete = {
                        viewModel.deleteImage(image.filename)
                        selectedImage = null
                    }
                )
            }
        }
    }
}

@Composable
private fun GalleryThumbnail(
    image: SavedImage,
    viewModel: MainViewModel,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(image.filename) {
        val file = viewModel.getImageFile(image.filename)
        if (!file.exists()) return@remember null
        if (image.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0)
            } catch (_: Exception) { null }
            finally { retriever.release() }
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }
    val timeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box {
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
                            Text("▶  VIDEO", fontSize = 10.sp, color = Color.White,
                                fontWeight = FontWeight.SemiBold)
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
                        text = if (image.sizeBytes >= 1024 * 1024)
                            "${image.sizeBytes / (1024 * 1024)}MB"
                        else "${image.sizeBytes / 1024}KB",
                        fontSize = 10.sp,
                        color = TextTertiary
                    )
                }
            }
        }

        IconButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp)
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Image?", color = TextPrimary) },
                text = { Text("This cannot be undone.", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
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

@Composable
private fun ImageViewer(
    image: SavedImage,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(image.filename) {
        val file = viewModel.getImageFile(image.filename)
        if (!file.exists()) return@remember null
        if (image.isVideo) {
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(file.absolutePath); retriever.getFrameAtTime(0) }
            catch (_: Exception) { null } finally { retriever.release() }
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.filename,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            )
        }

        if (image.isVideo) {
            Button(
                onClick = {
                    val file = viewModel.getImageFile(image.filename)
                    try {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
                modifier = Modifier.align(Alignment.Center),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
            ) {
                Text("▶  Play Video", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 24.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = TextSecondary
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(if (image.isVideo) "Delete Video?" else "Delete Image?", color = TextPrimary) },
                text = { Text("This cannot be undone.", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = onDelete) {
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
