package com.example.ui.components

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.CameraLensCueState
import com.example.camera.CameraXLensEngine
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SmokedGlassElevated
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraLensOverlaySheet(
    cameraEngine: CameraXLensEngine,
    cueState: CameraLensCueState,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onPickPhotoClick: () -> Unit,
    onAnalyzeCapturedBitmap: (Bitmap, String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            cameraEngine.unbind()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = DeepCockpit,
        contentColor = HudTextPrimary
    ) {
        CameraLensOverlayContent(
            cameraEngine = cameraEngine,
            cueState = cueState,
            hasCameraPermission = hasCameraPermission,
            onRequestCameraPermission = onRequestCameraPermission,
            onPickPhotoClick = onPickPhotoClick,
            onAnalyzeCapturedBitmap = onAnalyzeCapturedBitmap,
            onDismiss = {
                cameraEngine.unbind()
                onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
        )
    }
}

@Composable
fun CameraLensOverlayContent(
    cameraEngine: CameraXLensEngine,
    cueState: CameraLensCueState,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onPickPhotoClick: () -> Unit,
    onAnalyzeCapturedBitmap: (Bitmap, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val lifecycleOwner = LocalLifecycleOwner.current
    var questionDraft by remember { mutableStateOf("") }
    var useBackCamera by remember { mutableStateOf(cueState.lensFacingBack) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(lifecycleOwner, hasCameraPermission, useBackCamera, previewViewRef) {
        val pv = previewViewRef
        if (hasCameraPermission && pv != null) {
            cameraEngine.bindCamera(
                previewView = pv,
                lifecycleOwner = lifecycleOwner,
                useBackCamera = useBackCamera
            )
        }
        onDispose {
            cameraEngine.unbind()
        }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .testTag("camera_lens_sheet")
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "AETHER VISION LENS",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasCameraPermission) {
                    IconButton(
                        onClick = { cameraEngine.toggleTorch() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("lens_torch_button")
                    ) {
                        Icon(
                            imageVector = if (cueState.torchEnabled) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                            contentDescription = "Toggle camera flash torch",
                            tint = if (cueState.torchEnabled) SolarGold else HudTextSecondary
                        )
                    }
                    IconButton(
                        onClick = { useBackCamera = !useBackCamera },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("lens_switch_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cameraswitch,
                            contentDescription = "Switch front or back camera",
                            tint = accent
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lens_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close Vision Lens",
                        tint = HudTextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Camera viewfinder + live ML Kit HUD badges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(VoidObsidian)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Grant Camera permission for live scene & OCR inspection, or pick a photo directly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudTextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onRequestCameraPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("lens_grant_camera_button")
                    ) {
                        Text("Enable Camera Lens")
                    }
                }
            }

            // Live ML Kit scene label chips overlay
            if (cueState.liveLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cueState.liveLabels.forEach { label ->
                        Surface(
                            color = SmokedGlassElevated,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.65f))
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = StealthEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Live OCR strip at bottom of viewfinder
            if (cueState.liveOcrPreview.isNotBlank()) {
                Surface(
                    color = SmokedGlassElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Text(
                        text = "OCR: ${cueState.liveOcrPreview}",
                        style = MaterialTheme.typography.labelSmall,
                        color = HudTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (!cueState.cameraError.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = cueState.cameraError,
                style = MaterialTheme.typography.labelSmall,
                color = AlertCoral
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = questionDraft,
            onValueChange = { questionDraft = it },
            placeholder = { Text("Ask anything (translate, solve, summarize, identify)...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = accent.copy(alpha = 0.35f),
                focusedTextColor = HudTextPrimary,
                unfocusedTextColor = HudTextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lens_question_input")
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val q = questionDraft.trim()
                    cameraEngine.captureSnapshotBitmap(
                        onCaptured = { bmp -> onAnalyzeCapturedBitmap(bmp, q) },
                        onError = { _ -> }
                    )
                },
                enabled = hasCameraPermission && !cueState.isCapturing && !cueState.isAnalyzingAnswer,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("lens_capture_ask_button")
            ) {
                if (cueState.isCapturing || cueState.isAnalyzingAnswer) {
                    CircularProgressIndicator(
                        color = VoidObsidian,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Analyzing...")
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Capture & Ask")
                }
            }

            OutlinedButton(
                onClick = onPickPhotoClick,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lens_pick_photo_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = "Pick image with Android Photo Picker",
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Gallery", color = accent)
            }
        }

        if (cueState.latestAnswer.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = SmokedGlass,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lens_answer_card")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (cueState.latestQuestion.isNotBlank()) {
                        Text(
                            text = "Q: ${cueState.latestQuestion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = cueState.latestAnswer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudTextPrimary
                    )
                }
            }
        }
    }
}
