package com.example.aiglasses.ui.screens

import android.graphics.BitmapFactory
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                text = "Captured images from your glasses",
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
                Text(
                    text = "${savedImages.size} IMAGE${if (savedImages.size != 1) "S" else ""}",
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
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }
    val timeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box {
        GlassCard(depth = 1, cornerRadius = 12.dp, onClick = onClick) {
            Column {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = image.filename,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(GlassSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("?", fontSize = 24.sp, color = TextTertiary)
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
                        text = "${image.sizeBytes / 1024}KB",
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
                tint = Red,
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
    val bitmap = remember(image.filename) {
        val file = viewModel.getImageFile(image.filename)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
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
                    tint = Red
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
                title = { Text("Delete Image?", color = TextPrimary) },
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
