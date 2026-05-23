package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.EmptyGalleryState
import com.example.FullscreenPhotoViewer
import com.example.GalleryGridView
import com.example.data.ReceivedPhoto
import com.example.sharePhotoFile
import com.example.ui.viewmodel.AirReceiveViewModel
import com.example.ui.viewmodel.ServerState
import com.example.util.GallerySaver
import java.io.File

@Composable
fun GalleryScreen(
    serverState: ServerState,
    photoList: List<ReceivedPhoto>,
    selectedPhotoForView: ReceivedPhoto?,
    onPhotoSelected: (ReceivedPhoto?) -> Unit,
    viewModel: AirReceiveViewModel
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RECEIVED PHOTOS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${photoList.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (photoList.isNotEmpty()) {
                    Text(
                        text = "Clear All",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .testTag("btn_clear_all")
                            .clickable {
                                viewModel.clearAllPhotos()
                                Toast.makeText(context, "Gallery cleared", Toast.LENGTH_SHORT).show()
                            }
                    )
                }
            }

            if (photoList.isEmpty()) {
                EmptyGalleryState(serverState.isRunning)
            } else {
                GalleryGridView(
                    photos = photoList,
                    onPhotoClick = { onPhotoSelected(it) },
                    onShareClick = { sharePhotoFile(context, File(it.filePath), it.mimeType) },
                    onDeleteClick = { viewModel.deletePhoto(it) }
                )
            }
        }

        selectedPhotoForView?.let { photo ->
            FullscreenPhotoViewer(
                photo = photo,
                onDismiss = { onPhotoSelected(null) },
                onSaveToDisk = {
                    val file = File(photo.filePath)
                    val ok = GallerySaver.saveToPublicGallery(
                        context = context,
                        file = file,
                        fileName = photo.fileName,
                        mimeType = photo.mimeType
                    )
                    if (ok) {
                        Toast.makeText(context, "Saved to device Photo Album successfully", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to export photo", Toast.LENGTH_SHORT).show()
                    }
                },
                onSharePhoto = { sharePhotoFile(context, File(photo.filePath), photo.mimeType) },
                onDeletePhoto = {
                    viewModel.deletePhoto(photo)
                    onPhotoSelected(null)
                    Toast.makeText(context, "Photo deleted", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
