package net.lichias.opencodechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.lichias.opencodechat.data.Provider

private val FONT_SIZES = listOf(
    0.85f to "S",
    1.0f to "M",
    1.15f to "L",
    1.3f to "XL",
)

@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var key by remember(vm.provider, vm.apiKey) { mutableStateOf(vm.apiKey) }
    var confirmClear by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Provider",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    OutlinedButton(
                        onClick = { providerMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${vm.provider.label} ▾", modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        Provider.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = {
                                    providerMenu = false
                                    vm.changeProvider(p)
                                },
                            )
                        }
                    }
                }
                Text(
                    endpointText(vm.provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (vm.modelsError != null) {
                    Text(
                        vm.modelsError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    "Font size",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FONT_SIZES.forEachIndexed { index, scale ->
                        SegmentedButton(
                            selected = vm.fontScale == scale.first,
                            onClick = { vm.changeFontScale(scale.first) },
                            shape = SegmentedButtonDefaults.itemShape(index, FONT_SIZES.size),
                            label = {
                                Text(
                                    scale.second,
                                    fontSize = (14 * scale.first).sp,
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                if (vm.provider.needsApiKey) {
                    Text(
                        "${vm.provider.label} key",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("${vm.provider.label} API key") },
                        supportingText = {
                            Text(
                                when (vm.provider) {
                                    Provider.OPENROUTER -> "From openrouter.ai/keys. Encrypted on this phone."
                                    else -> "Encrypted with the device secure enclave. Never leaves this phone."
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { vm.saveApiKey(key.trim()) }, enabled = key.trim() != vm.apiKey) {
                        Text(if (vm.apiKey.isBlank()) "Save key" else "Update key")
                    }
                    Text(
                        "Keys are stored per-provider: OpenCode Zen, OpenCode Go and OpenRouter each keep their own key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = { confirmClear = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear all app data")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all app data?") },
            text = { Text("Deletes every chat session and wipes API keys and settings. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
            dismissButton = {
                Button(onClick = { confirmClear = false; vm.clearAllData(); onBack() }) { Text("Clear") }
            },
        )
    }
}

private fun endpointText(p: Provider): String = when (p) {
    Provider.ZEN -> "Endpoint: ${p.baseUrl} (+ /responses, /messages per model)"
    Provider.GO -> "Endpoint: ${p.baseUrl} (+ /responses, /messages per model)"
    Provider.OPENROUTER -> "Endpoint: ${p.baseUrl} (OpenAI-compatible chat)"
}
