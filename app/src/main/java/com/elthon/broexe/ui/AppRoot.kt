package com.elthon.broexe.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elthon.broexe.ai.LlmCatalog
import com.elthon.broexe.ai.ModelManager
import com.elthon.broexe.ai.ModelState
import com.elthon.broexe.memory.FileMemoryStore
import com.elthon.broexe.voice.CrashReporter
import com.elthon.broexe.voice.DetectionConfig
import com.elthon.broexe.voice.DetectionConfigStore
import com.elthon.broexe.voice.DiagnosticsSnapshot
import com.elthon.broexe.voice.InsufficientStorageException
import com.elthon.broexe.voice.ModelIntegrityException
import com.elthon.broexe.voice.VoiceDiagnostics
import com.elthon.broexe.voice.VoiceService
import com.elthon.broexe.voice.VoiceState
import com.elthon.broexe.voice.VoicePaths
import com.elthon.broexe.voice.VoiceTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val TrackBg = Color(0xFFE0E0E0)
private val Grey = Color(0xFF9E9E9E)

/**
 * The whole product: a switch, a truthful diagnostic panel, and one-tap stage
 * tests. Nothing here fake-reports success — if the mic is silent it says so.
 */
@Composable
fun AppRoot() {
    Scaffold { padding ->
        DiagnosticsScreen(Modifier.padding(padding))
    }
}

@Composable
private fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by VoiceService.state.collectAsStateWithLifecycle()
    val detail by VoiceService.detail.collectAsStateWithLifecycle()
    val snap by VoiceDiagnostics.snapshot.collectAsStateWithLifecycle()
    val events by VoiceDiagnostics.events.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            VoiceService.start(context)
        }
    }

    val isOn = state != VoiceState.OFF

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bro.exe", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Switch(
            checked = isOn,
            onCheckedChange = { wantOn ->
                if (wantOn) {
                    val micGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (micGranted) {
                        VoiceService.start(context)
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        )
                    }
                } else {
                    VoiceService.stop(context)
                }
            },
        )
        Text(
            text = if (isOn) "Voice Mode ON" else "Voice Mode OFF",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail ?: state.label(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))
        MicMeter(snap)
        Spacer(Modifier.height(16.dp))

        DetectionStatus(snap)
        Spacer(Modifier.height(16.dp))

        DetectionSettingsSection(locked = isOn)
        Spacer(Modifier.height(16.dp))

        ModelStatusCard()
        Spacer(Modifier.height(16.dp))

        MemorySection()
        Spacer(Modifier.height(16.dp))

        Text("Stage tests (turn Voice Mode off first)", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestButton("Mic", !isOn, Modifier.weight(1f)) { VoiceService.test(context, VoiceTest.MIC) }
            TestButton("Detect", !isOn, Modifier.weight(1f)) { VoiceService.test(context, VoiceTest.DETECT) }
            TestButton("Speech", !isOn, Modifier.weight(1f)) { VoiceService.test(context, VoiceTest.ASR) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestButton("Wake (KWS)", !isOn, Modifier.weight(1f)) { VoiceService.test(context, VoiceTest.WAKE) }
            TestButton("AI", !isOn, Modifier.weight(1f)) { VoiceService.test(context, VoiceTest.AI) }
            TestButton("Voice", !isOn, Modifier.weight(1f)) { VoiceService.test(context, VoiceTest.TTS) }
        }
        Spacer(Modifier.height(8.dp))
        TestButton("Full pipeline", !isOn, Modifier.fillMaxWidth()) { VoiceService.test(context, VoiceTest.FULL) }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                runCatching {
                    val crash = runCatching { CrashReporter.read(context) }.getOrNull()
                    cm.setPrimaryClip(
                        ClipData.newPlainText(
                            "Bro.exe diagnostics",
                            VoiceDiagnostics.report(crash),
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy report")
        }

        Spacer(Modifier.height(16.dp))
        CrashLogPanel()
        Spacer(Modifier.height(16.dp))
        Text("Live log", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            if (events.isEmpty()) {
                Text("(no events yet)", style = MaterialTheme.typography.bodySmall, color = Grey)
            } else {
                for (e in events.asReversed()) {
                    Text(
                        text = "${fmtClock(e.at)} ${e.tag}: ${e.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF333333),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetectionStatus(snap: DiagnosticsSnapshot) {
    Column(Modifier.fillMaxWidth()) {
        Text("Detection", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "activations=${snap.activations}  rejected=${snap.rejectedUtterances}  " +
                "candidates=${snap.candidateUtterances}  barge-ins=${snap.bargeIns}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "last: score=${"%.2f".format(Locale.US, snap.lastConfidence)} " +
                "band=${snap.lastBand ?: "-"} reason=${snap.lastActivationReason ?: snap.lastRejectReason ?: "-"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "echo cancellation: ${if (snap.echoCancellation) "on" else "off"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (snap.echoCancellation) Green else Grey,
        )
    }
}

@Composable
private fun DetectionSettingsSection(locked: Boolean) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(DetectionConfigStore.load(context)) }

    fun update(next: DetectionConfig) {
        cfg = next
        DetectionConfigStore.save(context, next)
    }

    Text("Detection settings (open vocabulary)", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))

    ToggleRow("Standalone \u201cBro\u201d", cfg.standaloneBro, !locked) {
        update(cfg.copy(standaloneBro = it))
    }
    ToggleRow("Barge-in (interrupt Bro)", cfg.bargeIn, !locked) {
        update(cfg.copy(bargeIn = it))
    }
    ToggleRow("Ask when ambiguous", cfg.ambiguousClarify, !locked) {
        update(cfg.copy(ambiguousClarify = it))
    }
    ToggleRow("Keyword-spotter hint", cfg.keywordHint, !locked) {
        update(cfg.copy(keywordHint = it))
    }

    Spacer(Modifier.height(8.dp))
    SliderRow("Activate threshold", cfg.highThreshold, 0.30f..0.90f, !locked) {
        update(cfg.copy(highThreshold = it))
    }
    SliderRow("Ambiguous threshold", cfg.ambiguousThreshold, 0.20f..0.80f, !locked) {
        update(cfg.copy(ambiguousThreshold = it))
    }
    SliderRow(
        "Pause tolerance (s)",
        (cfg.continuationGraceMs / 1000f).coerceIn(0.2f, 3.0f),
        0.2f..3.0f,
        !locked,
    ) {
        update(cfg.copy(continuationGraceMs = (it * 1000f).toLong()))
    }
    Text(
        text = "Ask for a question, or just say \u201cBro\u201d — anything addressed to Bro works. " +
            "High confidence starts a conversation; the middle band is decided by the policy above.",
        style = MaterialTheme.typography.bodySmall,
        color = Grey,
    )
}

/**
 * The single AI-model panel. Bro.exe ships exactly one model inside the APK; on first
 * open it is extracted into app storage exactly once and then reused. There is no
 * download button and no chooser — just an honest [ModelState]:
 *
 *   BUNDLED     packaged in the APK, not written to storage yet
 *   INSTALLING  a one-time local extraction from the APK (NOT a network download)
 *   READY       extracted + checksum-verified; usable fully offline
 *   ERROR       extraction failed; the real message is shown with a retry
 */
@Composable
private fun ModelStatusCard() {
    val context = LocalContext.current
    val manager = remember {
        ModelManager.forContext(context, VoicePaths.forFilesDir(context.filesDir).llm)
    }
    val model = manager.model

    var attempt by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf(manager.status().state) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        if (phase == ModelState.READY) return@LaunchedEffect
        phase = ModelState.INSTALLING
        error = null
        progress = 0f
        try {
            withContext(Dispatchers.IO) {
                VoicePaths.ensureExtracted(context, llmSha256 = model.sha256) { copied, total ->
                    progress = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f
                }
            }
            phase = ModelState.READY
        } catch (e: InsufficientStorageException) {
            error = "not enough storage \u2014 ${e.message}"
            phase = ModelState.ERROR
        } catch (e: ModelIntegrityException) {
            error = "packaged model is corrupt \u2014 ${e.message}"
            phase = ModelState.ERROR
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            phase = ModelState.ERROR
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text("AI model", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${model.displayName}  ${model.sizeLabel()}  [${model.license}]",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        when (phase) {
            ModelState.BUNDLED, ModelState.INSTALLING, ModelState.LOADING -> {
                Text(
                    text = if (phase == ModelState.INSTALLING) {
                        "Installing on-device (one time)\u2026 ${(progress * 100).toInt()}%"
                    } else {
                        "Bundled in the APK \u2014 installing on first launch\u2026"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Grey,
                )
                LinearProgressIndicator(
                    progress = { if (phase == ModelState.INSTALLING) progress else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
            ModelState.READY -> Text(
                text = "Ready \u2014 extracted and verified. Runs fully offline; no download needed.",
                style = MaterialTheme.typography.bodySmall,
                color = Green,
            )
            ModelState.ERROR -> {
                Text(
                    text = "Setup failed: ${error ?: "unknown error"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Red,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun MemorySection() {
    val context = LocalContext.current
    val store = remember { FileMemoryStore(File(context.filesDir, "memory.txt")) }
    var refreshKey by remember { mutableStateOf(0) }
    val facts = remember(refreshKey) { store.all() }

    Text("Memory", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (facts.isEmpty()) {
            "Nothing remembered yet. Say \"remember that ...\" to teach Bro a fact."
        } else {
            "${facts.size} remembered fact(s):"
        },
        style = MaterialTheme.typography.bodySmall,
        color = Grey,
    )
    for (fact in facts) {
        Text("\u2022 ${fact.text}", style = MaterialTheme.typography.bodyMedium)
    }
    if (facts.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { store.clear(); refreshKey++ }) {
            Text("Clear memory")
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "$label  ${"%.2f".format(Locale.US, value)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
        )
    }
}

/**
 * Shows the crash log the reporter captured on the last launch, and lets the user
 * copy it in one tap. Reading it never throws: a failure to load is shown as text,
 * never as a crash.
 */
@Composable
private fun CrashLogPanel() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val result = runCatching { withContext(Dispatchers.IO) { CrashReporter.read(context) } }
        crash = result.getOrNull()
        loadError = result.exceptionOrNull()?.message
    }

    val text = crash
    val err = loadError

    Column(Modifier.fillMaxWidth()) {
        Text("Crash log", style = MaterialTheme.typography.labelLarge, color = Red)
        Spacer(Modifier.height(4.dp))

        when {
            err != null -> Text("Could not read crash log: $err", style = MaterialTheme.typography.bodySmall, color = Grey)
            text.isNullOrBlank() -> Text("No crash recorded.", style = MaterialTheme.typography.bodySmall, color = Grey)
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (expanded) 320.dp else 96.dp)
                        .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF333333),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) "Collapse crash log" else "Expand crash log")
                }
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        runCatching { cm.setPrimaryClip(ClipData.newPlainText("Bro.exe crash log", text)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy crash log")
                }
            }
        }
    }
}

@Composable
private fun MicMeter(snap: DiagnosticsSnapshot) {
    val fraction = (snap.micLevel * 4f).coerceIn(0f, 1f)
    val verdict = when {
        snap.micFrames == 0L -> "Waiting for audio…" to Grey
        snap.hasSignal -> "Microphone signal OK (peak ${"%.3f".format(Locale.US, snap.micPeak)})" to Green
        else -> "No microphone signal — check ColorOS mic permission" to Red
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(TrackBg, RoundedCornerShape(7.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .background(if (snap.hasSignal) Green else Grey, RoundedCornerShape(7.dp)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "frames=${snap.micFrames}  level=${"%.3f".format(Locale.US, snap.micLevel)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(text = verdict.first, style = MaterialTheme.typography.bodyMedium, color = verdict.second)
    }
}

@Composable
private fun TestButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label, fontSize = 13.sp)
    }
}

private fun fmtClock(at: Long): String {
    val sec = at / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", (sec / 3600) % 24, (sec / 60) % 60, sec % 60)
}
