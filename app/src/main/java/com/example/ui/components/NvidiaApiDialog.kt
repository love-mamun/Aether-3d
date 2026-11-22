package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.AssistantPreferences
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SmokedGlassElevated
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private val NVIDIA_PRESET_MODELS = listOf(
    "nvidia/nemotron-3.5-bolt-e4b",
    "nvidia/nemotron-3.5-super-vl-120b-a12b",
    "qwen/qwen3-coder-480b-a35b-instruct",
    "meta/llama-3.1-8b-instruct"
)

@Composable
fun NvidiaApiDialog(
    preferences: AssistantPreferences,
    isConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (apiKey: String, model: String, preferPrimary: Boolean) -> Unit,
    onFetchModels: (suspend (overrideKey: String?) -> Result<List<String>>)? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    var apiKeyDraft by remember(preferences.nvidiaApiKey) { mutableStateOf(preferences.nvidiaApiKey) }
    var modelDraft by remember(preferences.nvidiaModel) { mutableStateOf(preferences.nvidiaModel) }
    var preferPrimaryDraft by remember(preferences.preferNvidiaPrimary) {
        mutableStateOf(
            preferences.preferNvidiaPrimary || !isConfigured
        )
    }
    var showKey by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(NVIDIA_PRESET_MODELS) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelFetchStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmokedGlassElevated,
        titleContentColor = HudTextPrimary,
        textContentColor = HudTextPrimary,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = "NVIDIA NIM API Settings",
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = "NVIDIA NIM API & KEY",
                        style = MaterialTheme.typography.titleMedium,
                        color = HudTextPrimary
                    )
                    Text(
                        text = if (isConfigured) "Key active (in-app or Secrets)" else "Enter nvapi-... key below",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConfigured) StealthEmerald else SolarGold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Endpoint: https://integrate.api.nvidia.com/v1/chat/completions",
                    style = MaterialTheme.typography.labelSmall,
                    color = HudTextSecondary
                )

                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    label = { Text("NVIDIA API Key (nvapi-...)") },
                    placeholder = { Text("nvapi-xxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { showKey = !showKey },
                            modifier = Modifier.testTag("toggle_nvidia_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide API key" else "Show API key",
                                tint = HudTextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = accent.copy(alpha = 0.35f),
                        focusedTextColor = HudTextPrimary,
                        unfocusedTextColor = HudTextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nvidia_key_field")
                )

                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it },
                    label = { Text("NVIDIA Model ID") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = accent.copy(alpha = 0.35f),
                        focusedTextColor = HudTextPrimary,
                        unfocusedTextColor = HudTextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nvidia_model_field")
                )

                if (onFetchModels != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modelFetchStatus ?: "Tap Refresh to load live NIM models",
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = !loadingModels,
                            onClick = {
                                scope.launch {
                                    loadingModels = true
                                    modelFetchStatus = "Fetching /v1/models..."
                                    val res = onFetchModels(apiKeyDraft.trim().ifBlank { null })
                                    res.onSuccess { list ->
                                        if (list.isNotEmpty()) {
                                            availableModels = list.take(12)
                                            if (modelDraft.isBlank() || modelDraft == "meta/llama-3.3-70b-instruct") {
                                                modelDraft = list.first()
                                            }
                                            modelFetchStatus = "Loaded ${list.size} live models"
                                        } else {
                                            modelFetchStatus = "No chat models returned"
                                        }
                                    }.onFailure { err ->
                                        modelFetchStatus = err.localizedMessage ?: "Failed to load models"
                                    }
                                    loadingModels = false
                                }
                            },
                            modifier = Modifier.testTag("fetch_nvidia_models_button")
                        ) {
                            if (loadingModels) {
                                CircularProgressIndicator(
                                    color = accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Text("Refresh Models", color = accent)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableModels.forEach { preset ->
                        val selected = modelDraft.trim() == preset
                        FilterChip(
                            selected = selected,
                            onClick = { modelDraft = preset },
                            label = {
                                Text(
                                    text = preset.substringAfter("/"),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.22f),
                                selectedLabelColor = accent
                            )
                        )
                    }
                }

                Surface(
                    color = SmokedGlass,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use NVIDIA API Directly",
                                style = MaterialTheme.typography.titleSmall,
                                color = HudTextPrimary
                            )
                            Text(
                                text = "Skip Gemini and route all commands directly through NVIDIA NIM",
                                style = MaterialTheme.typography.labelSmall,
                                color = HudTextSecondary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = preferPrimaryDraft,
                            onCheckedChange = { preferPrimaryDraft = it },
                            modifier = Modifier.testTag("prefer_nvidia_switch")
                        )
                    }
                }

                if (apiKeyDraft.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            apiKeyDraft = ""
                            preferPrimaryDraft = false
                            onSave("", modelDraft, false)
                            onDismiss()
                        },
                        border = BorderStroke(1.dp, AlertCoral.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nvidia_clear_button")
                    ) {
                        Text("Clear Saved In-App Key", color = AlertCoral)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedKey = apiKeyDraft.trim()
                    val usePrimary = preferPrimaryDraft && (trimmedKey.isNotEmpty() || isConfigured)
                    onSave(trimmedKey, modelDraft.trim(), usePrimary)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier.testTag("nvidia_save_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Save & Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HudTextPrimary)
            }
        }
    )
}
