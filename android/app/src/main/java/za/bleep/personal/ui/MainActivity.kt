package za.bleep.personal.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.bleep.personal.BleepApp
import za.bleep.personal.net.RegionMismatch
import za.bleep.personal.net.Relay
import za.bleep.personal.protocol.Bytes
import za.bleep.personal.protocol.ChatMsg
import za.bleep.personal.protocol.Crypto
import za.bleep.personal.protocol.Guardian
import za.bleep.personal.protocol.Hit
import za.bleep.personal.protocol.Peer
import za.bleep.personal.protocol.REGION
import za.bleep.personal.protocol.Session
import java.io.File

private val Ink = Color(0xFF101114)
private val Paper = Color(0xFF17191F)
private val Amber = Color(0xFFE0A94A)
private val TextC = Color(0xFFECE8DF)
private val Mute = Color(0xFF9A9488)
private val Bad = Color(0xFFD46767)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BleepApp
        setContent { BleepRoot(app) }
    }
}

@Composable
fun BleepRoot(app: BleepApp) {
    val session = remember { Session(Relay()) }
    var gate by remember { mutableStateOf(if (app.vault.exists()) "lock" else "setup") }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var lastTouch by remember { mutableStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    fun persist() {
        if (pin.isNotEmpty() && session.ready) {
            try { app.vault.save(pin, session.snapshot()) } catch (_: Exception) { }
        }
        tick++
    }

    LaunchedEffect(gate) {
        if (gate != "app") return@LaunchedEffect
        while (true) {
            delay(5000)
            if (System.currentTimeMillis() - lastTouch > 60_000) {
                persist()
                gate = "lock"
                break
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).pointerInput(Unit) {
            detectTapGestures { lastTouch = System.currentTimeMillis() }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("BLEEP · personal", color = Amber, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(REGION.replace("-", " · "), color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        when (gate) {
            "setup" -> SetupPane(err) { name, p, url ->
                err = ""
                pin = p
                session.relayBase(url)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { session.create(name) }
                        app.vault.save(p, session.snapshot())
                        gate = "app"
                    } catch (e: RegionMismatch) {
                        err = e.message ?: "region"
                    } catch (e: Exception) {
                        err = e.message ?: "failed"
                    }
                }
            }
            "lock" -> LockPane(err) { p ->
                err = ""
                pin = p
                scope.launch {
                    try {
                        val snap = app.vault.load(p)
                        withContext(Dispatchers.IO) { session.restore(snap) }
                        gate = "app"
                    } catch (_: Exception) {
                        err = "Wrong PIN or damaged vault."
                    }
                }
            }
            "app" -> AppPane(session, tick, { persist() }) { gate = "lock" }
        }
    }
}

@Composable
fun SetupPane(err: String, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://10.0.2.2:8090") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Create a Bleep ID", color = TextC, fontSize = 26.sp)
        Text("Device-generated keypair. No phone number, no email, no OTP. The vault is this phone.", color = Mute)
        Field("Display name", name) { name = it }
        Field("Bleep lock PIN", pin, password = true) { pin = it }
        Field("Public Bleep (emulator 10.0.2.2)", url) { url = it }
        if (err.isNotEmpty()) Text(err, color = Bad)
        AmberButton("Create ID") { onCreate(name.ifBlank { "me" }, pin, url) }
    }
}

@Composable
fun LockPane(err: String, onUnlock: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bleep is locked", color = TextC, fontSize = 26.sp)
        Field("PIN", pin, password = true) { pin = it }
        if (err.isNotEmpty()) Text(err, color = Bad)
        AmberButton("Unlock") { onUnlock(pin) }
    }
}

@Composable
fun AppPane(session: Session, tick: Int, bump: () -> Unit, onLock: () -> Unit) {
    var tab by remember { mutableStateOf("me") }
    var peer by remember { mutableStateOf<Peer?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(session.token) {
        while (true) {
            delay(2500)
            try {
                val n = withContext(Dispatchers.IO) {
                    session.poll()
                    session.lastIngest
                }
                if (n > 0) Notify.wake(ctx)
                bump()
            } catch (_: Exception) { }
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("me", "chats", "requests").forEach { t ->
                TextButton(onClick = { tab = t; peer = null }) {
                    Text(t.uppercase(), color = if (tab == t) Amber else Mute, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onLock) { Text("LOCK", color = Mute, fontSize = 11.sp) }
        }
        when {
            peer != null -> ThreadPane(session, peer!!, bump)
            tab == "me" -> MePane(session, bump)
            tab == "requests" -> RequestsPane(session, bump)
            else -> ChatsPane(session, bump) { peer = it }
        }
    }
}

@Composable
fun MePane(session: Session, bump: () -> Unit) {
    val code = Crypto.bleepCode(session.id)
    val qr = remember(code) { qrBitmap(code) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as BleepApp
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = app.vault.exportBytes(pass, session.snapshot())
                app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                note = "Export written. Chambers were not a second file. Passphrase cannot be skipped."
            } catch (e: Exception) {
                note = e.message ?: "export failed"
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: error("empty")
                val snap = app.vault.importBytes(pass, text)
                withContext(Dispatchers.IO) { session.restore(snap) }
                app.vault.save(pass.takeIf { it.length >= 4 } ?: "restored", snap)
                note = "Restored. Re-check safety numbers — this is a new device as far as peers know."
                bump()
            } catch (e: Exception) {
                note = e.message ?: "import failed"
            }
        }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(session.id.displayName, color = TextC, fontSize = 22.sp)
        Text("Add a person by this QR or code. First contact is a request, not a hot inbox.", color = Mute)
        Image(qr.asImageBitmap(), "Bleep QR", Modifier.size(200.dp).background(Color.White).padding(8.dp))
        Text(code, color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text("Export / restore (forced passphrase, no cloud)", color = Mute, fontSize = 12.sp)
        Field("Export passphrase 8+", pass, password = true) { pass = it }
        AmberButton("Encrypted export") { create.launch("bleep-export.json") }
        AmberButton("Restore export") { open.launch(arrayOf("application/json", "*/*")) }
        TextButton(onClick = {
            scope.launch {
                try { withContext(Dispatchers.IO) { session.unlink() } } catch (_: Exception) {}
                note = "Mailbox unlinked. Relays drop sealed mail. We cannot wipe their phone."
            }
        }) { Text("Remote unlink", color = Bad) }
        TextButton(onClick = {
            scope.launch {
                try { withContext(Dispatchers.IO) { session.unlink() } } catch (_: Exception) {}
                app.vault.erase()
                note = "This device erased. Create a new ID."
            }
        }) { Text("Erase this device", color = Bad) }
        if (note.isNotEmpty()) Text(note, color = Mute, fontSize = 12.sp)
    }
}

@Composable
fun ChatsPane(session: Session, bump: () -> Unit, open: (Peer) -> Unit) {
    var code by remember { mutableStateOf("") }
    var hello by remember { mutableStateOf("Sawubona — request to message.") }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Paste BLEEP1 code", code) { code = it }
        Field("Request text", hello) { hello = it }
        if (err.isNotEmpty()) Text(err, color = Bad)
        AmberButton("Send request") {
            scope.launch {
                try {
                    val p = session.addByCode(code)
                    withContext(Dispatchers.IO) { session.sendIntro(p, hello) }
                    bump()
                } catch (e: Exception) {
                    err = e.message ?: "failed"
                }
            }
        }
        session.peers.values.filter { it.accepted }.forEach { p ->
            Text(
                p.displayName,
                color = TextC,
                modifier = Modifier.fillMaxWidth().clickable { open(p) }.padding(12.dp),
            )
        }
    }
}

@Composable
fun RequestsPane(session: Session, bump: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        if (session.requests.isEmpty()) Text("No requests.", color = Mute)
        session.peers.values.filter { !it.accepted }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.displayName, color = TextC)
                AmberButton("Accept") { session.accept(p.mailboxId); bump() }
            }
        }
    }
}

@Composable
fun ThreadPane(session: Session, peer: Peer, bump: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            }
            val name = uri.lastPathSegment ?: "photo.jpg"
            val g = Guardian.scanFile(name, "image/jpeg", bytes)
            if (g.isNotEmpty()) hits = g
            else withContext(Dispatchers.IO) { session.sendMedia(peer, "photo", bytes, "image/jpeg", name) }
            bump()
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val f = File(ctx.cacheDir, "voice.m4a")
            val mr = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(f.absolutePath)
                prepare(); start()
            }
            recorder = mr; recFile = f; recording = true
        }
    }
    val msgs = session.threads[peer.mailboxId].orEmpty()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(peer.displayName + if (peer.verified) " · verified" else " · unverified", color = TextC, fontSize = 18.sp)
        Text(Crypto.safetyNumber(session.id.edPk, peer.edPk), color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        if (peer.safetyChanged) {
            Text("Safety number changed. This is a new key. The composer is blocked until you verify — not a toast.", color = Bad, fontSize = 13.sp)
        }
        Row {
            TextButton(onClick = { session.verifyPeer(peer); bump() }) { Text("Mark verified", color = Amber, fontSize = 12.sp) }
            TextButton(onClick = {
                peer.disappearSec = if (peer.disappearSec == null) 86400 else null
                bump()
            }) { Text(if (peer.disappearSec != null) "Keep history" else "Disappear 24h", color = Mute, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(msgs.reversed()) { m ->
                val bg = if (m.fromMe) Color(0xFF2A2418) else Paper
                Column(
                    Modifier.padding(6.dp).background(bg, RoundedCornerShape(12.dp)).padding(10.dp)
                        .clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) { session.sendReact(peer, m.id) }
                                bump()
                            }
                        },
                ) {
                    if (m.kind == "photo" && m.bytesB64 != null) {
                        val bmp = remember(m.bytesB64) {
                            val b = Bytes.b64d(m.bytesB64)
                            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                        }
                        if (bmp != null) Image(bmp, "photo", Modifier.fillMaxWidth())
                    }
                    Text(m.text + (m.reacted?.let { "  $it" } ?: ""), color = TextC)
                }
            }
        }
        if (hits.isNotEmpty()) {
            hits.forEach { Text(it.reason, color = Amber, fontSize = 13.sp) }
            Row {
                TextButton(onClick = { hits = emptyList() }) { Text("Cancel", color = Mute) }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { session.sendText(peer, text) }
                        text = ""; hits = emptyList(); bump()
                    }
                }) { Text("Send anyway", color = Amber) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { pick.launch("image/*") }) { Text("+", color = Amber, fontSize = 20.sp) }
            TextButton(onClick = {
                if (recording) {
                    try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
                    recorder = null; recording = false
                    val f = recFile
                    if (f != null && f.exists()) {
                        scope.launch {
                            val bytes = f.readBytes()
                            withContext(Dispatchers.IO) { session.sendMedia(peer, "voice", bytes, "audio/mp4", "voice.m4a") }
                            bump()
                        }
                    }
                } else mic.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (recording) "■" else "●", color = if (recording) Bad else Amber) }
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.weight(1f),
                colors = fieldColors(),
            )
            Button(
                onClick = {
                    if (peer.safetyChanged) return@Button
                    val g = Guardian.scan(text)
                    if (g.isNotEmpty()) hits = g
                    else scope.launch {
                        withContext(Dispatchers.IO) { session.sendText(peer, text) }
                        text = ""; bump()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
            ) { Text("Send") }
        }
    }
}

@Composable
fun Field(label: String, value: String, password: Boolean = false, on: (String) -> Unit) {
    OutlinedTextField(
        value, on, label = { Text(label, color = Mute) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = fieldColors(),
    )
}

@Composable
fun AmberButton(label: String, onClick: () -> Unit) {
    Button(
        onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextC,
    unfocusedTextColor = TextC,
    focusedBorderColor = Amber,
    unfocusedBorderColor = Mute,
)

fun qrBitmap(content: String): Bitmap {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.RGB_565)
    for (x in 0 until bits.width) for (y in 0 until bits.height) {
        bmp.setPixel(x, y, if (bits[x, y]) 0xFF101114.toInt() else 0xFFFFFFFF.toInt())
    }
    return bmp
}
