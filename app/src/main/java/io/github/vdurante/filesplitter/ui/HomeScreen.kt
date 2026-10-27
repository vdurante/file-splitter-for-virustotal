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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.vdurante.filesplitter.R

/** API key field (masked, saved by the caller) and the button that opens the file picker. */
@Composable
fun HomeScreen(apiKey: String, onApiKeyChange: (String) -> Unit, onApkPicked: (Uri) -> Unit) {
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(96.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Scans APKs with VirusTotal, including ones over its 650 MB limit: the largest files " +
                    "are moved into bundle APKs and scanned next to a slimmed-down APK.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("VirusTotal API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, API_KEY_URL.toUri())) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Get a free API key")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) pickApk() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Select APK file")
            }
        }
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
    AppTheme { HomeScreen(apiKey = "", onApiKeyChange = {}, onApkPicked = {}) }
}
