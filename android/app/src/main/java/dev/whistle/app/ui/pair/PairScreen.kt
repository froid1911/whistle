package dev.whistle.app.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.whistle.app.protocol.Machine
import dev.whistle.app.protocol.PairInputException
import dev.whistle.app.protocol.PairInvite
import dev.whistle.app.protocol.PairInvites
import dev.whistle.app.ui.components.Dot
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.components.SectionLabel
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.components.WhistleTopBar
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Where the pairing flow is. */
sealed interface PairStep {
    data object Scan : PairStep
    data object Manual : PairStep
    data class Confirm(val invite: PairInvite) : PairStep
    data class Working(val invite: PairInvite) : PairStep
    data class Done(val machine: Machine) : PairStep
}

const val PAIR_COMMAND = "herdr plugin action invoke whistle.bridge.pair"

/**
 * Pair a machine: scan the QR code the bridge's `pair` action prints (CameraX + ZXing, decoded on
 * the phone), or enter the link / address + code. [pair] registers this phone and stores the
 * machine; it throws with a showable message on failure.
 */
@Composable
fun PairScreen(
    onBack: () -> Unit,
    pair: suspend (PairInvite, String) -> Machine,
    onOpenMachine: (Machine) -> Unit,
    defaultDeviceName: String,
    initialStep: PairStep = PairStep.Scan,
    cameraPermitted: () -> Boolean = { false },
    initialLink: String? = null,
) {
    // A link from outside (deep link): same parser as the scanner; never pairs by itself.
    val fromLink = remember { initialLink?.let { runCatching { PairInvites.parse(it) } } }
    var step by remember { mutableStateOf(fromLink?.getOrNull()?.let { PairStep.Confirm(it) } ?: initialStep) }
    var error by remember {
        mutableStateOf(fromLink?.exceptionOrNull()?.let { (it as? PairInputException)?.message ?: "Damaged pairing link" })
    }
    val scope = rememberCoroutineScope()
    val c = Whistle.colors
    fun goBack() {
        error = null
        step = when (step) {
            is PairStep.Confirm, PairStep.Manual -> PairStep.Scan
            else -> return onBack()
        }
    }
    Scaffold(
        containerColor = c.night,
        contentWindowInsets = WindowInsets(0),
        topBar = { WhistleTopBar("Pair a machine", onBack = ::goBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = step) {
                PairStep.Scan -> ScanStep(
                    cameraPermitted = cameraPermitted,
                    error = error,
                    onScanned = { text ->
                        try {
                            step = PairStep.Confirm(PairInvites.parse(text))
                            error = null
                        } catch (e: PairInputException) {
                            error = e.message
                        }
                    },
                    onManual = { error = null; step = PairStep.Manual },
                )
                PairStep.Manual -> ManualStep(error) { link, code ->
                    try {
                        step = PairStep.Confirm(PairInvites.fromInput(link, code))
                        error = null
                    } catch (e: PairInputException) {
                        error = e.message
                    }
                }
                is PairStep.Confirm -> ConfirmStep(s.invite, defaultDeviceName, error, working = false) { name ->
                    error = null
                    step = PairStep.Working(s.invite)
                    scope.launch {
                        step = try {
                            PairStep.Done(pair(s.invite, name))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Pairing failed"
                            PairStep.Confirm(s.invite)
                        }
                    }
                }
                is PairStep.Working -> ConfirmStep(s.invite, defaultDeviceName, null, working = true) {}
                is PairStep.Done -> DoneStep(s.machine) { onOpenMachine(s.machine) }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = WhistleType.secondary, color = Whistle.colors.sage)

@Composable
private fun ErrorText(text: String?) {
    if (text != null) Text(text, style = WhistleType.body, color = Whistle.colors.ember, modifier = Modifier.testTag("pairError"))
}

@Composable
private fun ScanStep(cameraPermitted: () -> Boolean, error: String?, onScanned: (String) -> Unit, onManual: () -> Unit) {
    val c = Whistle.colors
    var granted by remember { mutableStateOf(cameraPermitted()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    WhistleCard {
        Text("On the machine, run", style = WhistleType.body, color = c.wool)
        Spacer(Modifier.height(6.dp))
        Text(PAIR_COMMAND, style = WhistleType.mono, color = c.wool, modifier = Modifier.clip(WhistleShapes.Pill))
        Spacer(Modifier.height(6.dp))
        Hint("It shows a QR code that works once, for 5 minutes. Run it in a herdr shell pane to see it, or read it with herdr plugin log list --plugin whistle.bridge.")
    }
    if (granted) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(WhistleShapes.Card),
            contentAlignment = Alignment.Center,
        ) {
            QrScanner(onScanned, Modifier.fillMaxSize())
        }
        Hint("Point the camera at the code. It is read on this phone; nothing is stored or sent.")
    } else {
        WhistleCard(Modifier.testTag("cameraRationale")) {
            Text("Scan with the camera", style = WhistleType.bodyStrong, color = c.wool)
            Spacer(Modifier.height(4.dp))
            Hint(
                "Whistle uses the camera only while this screen is open, to read the pairing code. " +
                    "Frames are decoded on this phone and never stored or sent.",
            )
            if (denied) {
                Spacer(Modifier.height(4.dp))
                Text("Camera access was declined. You can enter the code instead.", style = WhistleType.secondary, color = c.ember)
            }
            Spacer(Modifier.height(10.dp))
            PillButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) }, style = PillStyle.Wool)
        }
    }
    ErrorText(error)
    PillButton("Enter code instead", onClick = onManual, modifier = Modifier.testTag("enterCode"))
}

@Composable
private fun ManualStep(error: String?, onNext: (String, String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val isLink = link.trim().startsWith("whistle:", ignoreCase = true)
    SectionLabel("Enter the pairing code")
    WhistleCard {
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Pairing link or address") },
            placeholder = { Text("100.x.y.z:8787 or whistle://pair?…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = whistleFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("pairLink"),
        )
        if (!isLink) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code") },
                placeholder = { Text("ABCD-EFGH-…") },
                singleLine = true,
                colors = whistleFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pairCode"),
            )
        }
        Spacer(Modifier.height(8.dp))
        Hint(
            if (isLink) "The link carries the machine's key fingerprint, so Whistle can check it's really your machine."
            else "Address and code can't prove the machine's identity: Whistle trusts the key it presents now. Pasting the whistle:// link is safer.",
        )
    }
    ErrorText(error)
    PillButton("Next", onClick = { onNext(link, code) }, style = PillStyle.Wool, enabled = link.isNotBlank() && (isLink || code.isNotBlank()), modifier = Modifier.testTag("pairNext"))
}

@Composable
private fun ConfirmStep(invite: PairInvite, defaultName: String, error: String?, working: Boolean, onPair: (String) -> Unit) {
    val c = Whistle.colors
    var name by rememberSaveable { mutableStateOf(defaultName) }
    WhistleCard {
        Text("Pair with ${invite.displayName}", style = WhistleType.name, color = c.wool)
        Text(invite.address, style = WhistleType.secondary, color = c.sage)
        Spacer(Modifier.height(8.dp))
        if (invite.fingerprint != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(c.diffAddFg)
                Spacer(Modifier.width(8.dp))
                Text("Bridge key ${invite.fingerprint.take(12)}… will be pinned", style = WhistleType.secondary, color = c.sage)
            }
        } else {
            Text("Entered by hand: the bridge's key can't be checked against a QR code.", style = WhistleType.secondary, color = c.ember)
        }
    }
    WhistleCard {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(64) },
            label = { Text("This phone's name on ${invite.displayName}") },
            singleLine = true,
            enabled = !working,
            colors = whistleFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("deviceName"),
        )
        Spacer(Modifier.height(8.dp))
        Hint("Whistle creates a key that never leaves this phone and signs every request with it. Remove the phone on the machine any time.")
    }
    ErrorText(error)
    if (working) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.sky)
            Spacer(Modifier.width(10.dp))
            Text("Pairing…", style = WhistleType.body, color = c.sky)
        }
    } else {
        PillButton("Pair this phone", onClick = { onPair(name) }, style = PillStyle.Wool, enabled = name.isNotBlank(), modifier = Modifier.testTag("pairConfirm"))
    }
}

@Composable
private fun DoneStep(machine: Machine, onOpen: () -> Unit) {
    val c = Whistle.colors
    WhistleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(c.diffAddFg, size = 10.dp)
            Spacer(Modifier.width(10.dp))
            Text("Paired with ${machine.name}", style = WhistleType.name, color = c.wool)
        }
        Spacer(Modifier.height(4.dp))
        Text("Paired as ${machine.deviceName}", style = WhistleType.secondary, color = c.sage)
        Spacer(Modifier.height(8.dp))
        Hint("Once every phone you use is paired, the owner can switch the bridge to paired devices only (auth devices in auth.conf).")
    }
    PillButton("Open ${machine.name}", onClick = onOpen, style = PillStyle.Wool, modifier = Modifier.testTag("pairOpen"))
}

@Composable
fun whistleFieldColors(): TextFieldColors {
    val c = Whistle.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.wool, unfocusedTextColor = c.wool, disabledTextColor = c.sage,
        focusedBorderColor = c.wool, unfocusedBorderColor = c.line, disabledBorderColor = c.line,
        focusedLabelColor = c.wool, unfocusedLabelColor = c.sage, disabledLabelColor = c.sage,
        focusedPlaceholderColor = c.sage, unfocusedPlaceholderColor = c.sage,
        cursorColor = c.wool, errorBorderColor = c.ember, errorLabelColor = c.ember,
    )
}

/** Camera preview + on-device QR analysis; [onResult] runs on the main thread, once per distinct text. */
@Composable
private fun QrScanner(onResult: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView({ preview }, modifier)
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val stopped = AtomicBoolean(false)
        val decoder = QrDecoder()
        var last: String? = null
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (stopped.get()) return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    val text = decoder.decode(bytes, image.width, image.height, plane.rowStride)
                    if (text != null && text != last) {
                        last = text
                        main.post { if (!stopped.get()) onResult(text) }
                    }
                } finally {
                    image.close()
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            stopped.set(true)
            runCatching { if (future.isDone) future.get().unbindAll() }
            executor.shutdown()
        }
    }
}

/** Whether the app may use the camera. */
fun cameraGranted(ctx: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
