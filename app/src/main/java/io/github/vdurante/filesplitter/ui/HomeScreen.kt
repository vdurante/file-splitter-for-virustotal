package io.github.vdurante.filesplitter.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.vdurante.filesplitter.R

/** Two steps: save the VirusTotal API key, then pick an APK. Step 2 unlocks once a key is saved. */
@Composable
fun HomeScreen(apiKey: String, onApiKeySave: (String) -> Unit, onApkPicked: (Uri) -> Unit) {
    val hasKey = apiKey.isNotBlank()
    // Editing starts open only when there's no key yet; "Change" reopens it.
    var editingKey by rememberSaveable { mutableStateOf(!hasKey) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(72.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Scan APKs of any size with VirusTotal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            StepCard(number = 1, title = "Add your VirusTotal API key", done = hasKey && !editingKey) {
                if (hasKey && !editingKey) {
                    SavedKey(onChange = { editingKey = true })
                } else {
                    ApiKeyForm(
                        initial = apiKey,
                        onSave = {
                            onApiKeySave(it)
                            editingKey = false
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            StepCard(number = 2, title = "Pick an APK to scan", enabled = hasKey && !editingKey) {
                Text(
                    "Select an APK file to analyze. Large files are split into multiple APKs so they can fit " +
                        "the 650 MB file size limit of VirusTotal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PickApkButton(enabled = hasKey && !editingKey, onApkPicked = onApkPicked)
            }
        }
    }
}

/** A numbered step. Dimmed while [enabled] is false; shows a check once [done]. */
@Composable
private fun StepCard(
    number: Int,
    title: String,
    enabled: Boolean = true,
    done: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(number, done)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean) {
    val color = if (done) SafeGreen else MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(Icons.Filled.Check, "Done", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
        } else {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun ApiKeyForm(initial: String, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf(initial) }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("VirusTotal API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, API_KEY_URL.toUri())) }) {
            Text("Get a free API key")
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = { onSave(key.trim()) }, enabled = key.isNotBlank()) { Text("Save key") }
    }
}

@Composable
private fun SavedKey(onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "API key saved on this device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text("Change") }
    }
}

@Composable
private fun PickApkButton(enabled: Boolean, onApkPicked: (Uri) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onApkPicked(uri)
    }
    // Some file managers don't tag APKs with the APK MIME type, so also allow any file.
    val pickApk = { picker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }
    // The progress notification needs this on Android 13+; the scan works either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pickApk()
    }
    Button(
        onClick = {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) pickApk() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Select file")
    }
}

/** The launcher icon's artwork on its navy background, as a rounded tile of [size]. */
@Composable
fun AppIcon(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(colorResource(R.color.icon_background)),
        contentAlignment = Alignment.Center,
    ) {
        // The adaptive-icon foreground has 18dp of padding per 108dp; scale it up to fill the tile.
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 108 / 72))
    }
}

/** Where a signed-in VirusTotal user finds their key (sign-up is free). */
private const val API_KEY_URL = "https://www.virustotal.com/gui/my-apikey"

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AppTheme { HomeScreen(apiKey = "", onApiKeySave = {}, onApkPicked = {}) }
}
