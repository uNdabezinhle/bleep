package za.bleep.personal.ui

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
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
import za.bleep.personal.protocol.Erased
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
            val sec = session.autoLockSec
            if (sec > 0 && System.currentTimeMillis() - lastTouch > sec * 1000L) {
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
            "setup" -> AccountPane(session, err) { p ->
                pin = p
                app.vault.save(p, session.snapshot())
                gate = "app"
                err = ""
            }
            "lock" -> LockPane(err) { p ->
                err = ""
                pin = p
                scope.launch {
                    try {
                        val snap = app.vault.load(p)
                        withContext(Dispatchers.IO) {
                            session.restore(snap)
                            session.pullHistory()
                        }
                        gate = "app"
                    } catch (e: Erased) {
                        app.vault.erase()
                        gate = "setup"
                        err = "This device was erased. A phone that never contacts the box keeps its files. This one just did."
                    } catch (_: Exception) {
                        err = "Wrong PIN or damaged vault."
                    }
                }
            }
            "app" -> AppPane(session, tick, { persist() }, { gate = "lock" }, { pin = it }, {
                app.vault.erase()
                gate = "setup"
                err = "This device was erased. A phone that never contacts the box keeps its files. This one just did."
            })
        }
    }
}

@Composable
fun AccountPane(session: Session, errIn: String, onOpen: (String) -> Unit) {
    var mode by remember { mutableStateOf("create") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var resetToken by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://10.0.2.2:8090") }
    var seed by remember { mutableStateOf<ByteArray?>(null) }
    var kept by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf(errIn) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(activity?.intent?.data) {
        val data = activity?.intent?.data ?: return@LaunchedEffect
        val reset = data.getQueryParameter("reset")
        val confirm = data.getQueryParameter("confirm")
        if (!reset.isNullOrBlank()) {
            resetToken = reset
            mode = "reset"
        }
        if (!confirm.isNullOrBlank()) {
            try {
                withContext(Dispatchers.IO) { session.confirmAddress(confirm) }
                notice = "Address confirmed. Sign in with the recovery code. The link did not contain it."
                mode = "signin"
            } catch (e: Exception) {
                err = e.message ?: "confirm failed"
            }
        }
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (mode) {
                "signin" -> "Sign in"
                "reset" -> "New password"
                else -> "Create an account"
            },
            color = TextC,
            fontSize = 26.sp,
        )
        Text("The key is made on this phone. Email is the login. The recovery code is shown once and is not sent to the relay.", color = Mute)
        if (mode != "reset") Field("Email", email) { email = it }
        Field(if (mode == "reset") "New password" else "Password", password, password = true) { password = it }
        if (mode == "create" && seed == null) {
            Field("Password again", again, password = true) { again = it }
            Field("Display name", name) { name = it }
        }
        if (mode != "reset") Field("Bleep lock PIN", pin, password = true) { pin = it }
        if (mode == "signin") {
            Field("Recovery code", recovery) { recovery = it }
            Text("The code opens the key and the sealed history. The password alone does not.", color = Mute, fontSize = 13.sp)
        }
        if (mode == "reset") Field("Reset token from the mail link", resetToken) { resetToken = it }
        Field("This box's URL", url) { url = it }
        if (seed != null && mode == "create") {
            Text("Write this down. It is shown once. A second device needs it. The relay never receives it.", color = Mute, fontSize = 13.sp)
            Text(Crypto.formatRecovery(seed!!), color = Amber, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { kept = !kept }) {
                Text(if (kept) "Recovery code kept" else "I have kept this recovery code", color = Amber)
            }
        }
        if (err.isNotEmpty()) Text(err, color = Bad)
        if (notice.isNotEmpty()) Text(notice, color = Mute)
        AmberButton(
            when {
                busy -> "Working…"
                mode == "signin" -> "Open vault"
                mode == "reset" -> "Update password"
                seed == null -> "Show recovery code"
                else -> "Create account"
            },
        ) {
            if (busy) return@AmberButton
            err = ""
            notice = ""
            if (mode == "create" && seed == null) {
                if (password.length < 8) { err = "Password should be 8 characters or more."; return@AmberButton }
                if (password != again) { err = "Passwords do not match."; return@AmberButton }
                if (pin.length < 4) { err = "PIN should be 4 characters or more."; return@AmberButton }
                if (name.isBlank()) { err = "Add a display name."; return@AmberButton }
                if (email.isBlank()) { err = "Enter an email."; return@AmberButton }
                seed = Crypto.freshRecoverySeed()
                return@AmberButton
            }
            if (mode == "create" && !kept) { err = "Confirm that you have kept the recovery code."; return@AmberButton }
            if (mode == "signin" && pin.length < 4) { err = "PIN should be 4 characters or more."; return@AmberButton }
            busy = true
            session.relayBase(url)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        when (mode) {
                            "reset" -> session.completeReset(resetToken, password)
                            "signin" -> session.signIn(email, password, recovery)
                            else -> session.signUp(email, password, name, seed!!)
                        }
                    }
                    if (mode == "reset") {
                        notice = "Password updated. Sign in with the recovery code to open history."
                        mode = "signin"
                    } else if (mode == "create" && !session.ready) {
                        notice = session.lastDetail.ifBlank { "Check the inbox to confirm this address. The link does not contain the recovery code." }
                        mode = "signin"
                    } else {
                        if (session.lastDetail.isNotBlank()) notice = session.lastDetail
                        onOpen(pin)
                    }
                } catch (e: RegionMismatch) {
                    err = e.message ?: "region"
                } catch (e: Exception) {
                    err = e.message ?: "failed"
                } finally {
                    busy = false
                }
            }
        }
        if (mode == "create") TextButton(onClick = { mode = "signin"; seed = null }) { Text("Already have an account", color = Amber) }
        if (mode == "signin") TextButton(onClick = { mode = "create"; seed = null }) { Text("Create an account", color = Amber) }
        if (mode != "reset") TextButton(onClick = {
            if (email.isBlank()) { err = "Enter the email on the account."; return@TextButton }
            session.relayBase(url)
            scope.launch {
                try {
                    val res = withContext(Dispatchers.IO) { session.requestReset(email) }
                    notice = res.optString("detail")
                } catch (e: Exception) {
                    err = e.message ?: "failed"
                }
            }
        }) { Text("Forgot password", color = Mute) }
        if (mode != "reset") TextButton(onClick = { mode = "reset" }) { Text("I have a reset token", color = Mute) }
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
fun AppPane(session: Session, tick: Int, bump: () -> Unit, onLock: () -> Unit, onPin: (String) -> Unit, onErased: () -> Unit) {
    var tab by remember { mutableStateOf("me") }
    var peer by remember { mutableStateOf<Peer?>(null) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var chamberId by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(session.captureNotice, peer?.mailboxId) {
        val open = peer
        if (Build.VERSION.SDK_INT < 34 || !session.captureNotice || open == null || ctx !is Activity) {
            return@DisposableEffect onDispose { }
        }
        val callback = Activity.ScreenCaptureCallback {
            scope.launch {
                withContext(Dispatchers.IO) { session.sendCapture(open) }
                bump()
            }
        }
        ctx.registerScreenCaptureCallback(ctx.mainExecutor, callback)
        onDispose { ctx.unregisterScreenCaptureCallback(callback) }
    }
    LaunchedEffect(session.token) {
        while (true) {
            delay(2500)
            try {
                val n = withContext(Dispatchers.IO) {
                    session.poll()
                    session.pushHistory()
                    session.lastIngest
                }
                if (n > 0) Notify.wake(ctx)
                session.regionOk = true
                bump()
            } catch (_: Erased) {
                onErased()
            } catch (_: RegionMismatch) {
                session.regionOk = false
                bump()
            } catch (_: Exception) { }
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("me", "chats", "requests", "report").forEach { t ->
                TextButton(onClick = { tab = t; peer = null; groupId = null; chamberId = null }) {
                    Text(t.uppercase(), color = if (tab == t && peer == null && groupId == null && chamberId == null) Amber else Mute, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onLock) { Text("LOCK", color = Mute, fontSize = 11.sp) }
        }
        when {
            peer != null || groupId != null || chamberId != null -> ThreadPane(
                session,
                peer,
                bump,
                groupId,
                chamberId,
                { chamberId = it; peer = null; groupId = null },
                { peer = null; groupId = null; chamberId = null },
            )
            tab == "me" -> MePane(session, bump, onPin, onErased)
            tab == "requests" -> RequestsPane(session, bump, { groupId = it; tab = "chats" }) { chamberId = it; tab = "chats" }
            tab == "report" -> GuardianReport(session, bump, { tab = "me" }, { tab = "chats" })
            else -> ChatsPane(
                session,
                bump,
                { peer = it; groupId = null; chamberId = null },
                { groupId = it; peer = null; chamberId = null },
                { chamberId = it; peer = null; groupId = null },
            )
        }
    }
}

@Composable
fun MePane(session: Session, bump: () -> Unit, onPin: (String) -> Unit, onErased: () -> Unit) {
    val code = Crypto.bleepCode(session.id)
    val short = session.shortId?.let { Crypto.formatShortId(it) }.orEmpty()
    val qr = remember(short.ifBlank { code }) { qrBitmap(short.ifBlank { code }) }
    var showLong by remember { mutableStateOf(false) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as BleepApp
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var currentPass by remember { mutableStateOf("") }
    var nextPass by remember { mutableStateOf("") }
    var nextPin by remember { mutableStateOf("") }
    var deviceRows by remember { mutableStateOf(org.json.JSONArray()) }
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
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val raw = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    shrinkJpeg(raw, 512)
                }
                withContext(Dispatchers.IO) { session.setProfilePhoto(bytes) }
                note = "This photo is sealed to people you accepted. There is no public photo."
                bump()
            } catch (e: Exception) {
                note = e.message ?: "photo failed"
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(session.id.displayName, color = TextC, fontSize = 22.sp)
        JpegFace(session.avatarB64, 96)
        Text("This photo is sealed to people you accepted. There is no public photo.", color = Mute, fontSize = 12.sp)
        TextButton(onClick = { photo.launch("image/*") }) { Text("Choose profile photo", color = Amber) }
        if (!session.avatarB64.isNullOrBlank()) {
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { session.clearProfilePhoto() }
                    note = "Profile photo removed."
                    bump()
                }
            }) { Text("Remove profile photo", color = Mute) }
        }
        Text("Eight characters. A hyphen is optional. First contact is a request.", color = Mute)
        Image(qr.asImageBitmap(), "Bleep QR", Modifier.size(200.dp).background(Color.White).padding(8.dp))
        Text(if (short.isBlank()) "…" else short, color = Amber, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
        TextButton(onClick = { showLong = !showLong }) { Text(if (showLong) "Hide long code" else "Long code", color = Mute) }
        if (showLong) Text(code, color = Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            session.captureNotice = !session.captureNotice
            bump()
        }) { Text(if (session.captureNotice) "Capture note on" else "Capture note off", color = Amber) }
        Text(
            if (session.captureNotice) "If this phone captures the screen, this chat gets a note."
            else "A capture on their side is not reported.",
            color = Mute,
            fontSize = 12.sp,
        )
        TextButton(onClick = {
            session.guardianMode = when (session.guardianMode) {
                "off" -> "warn"
                "warn" -> "strict"
                else -> "off"
            }
            bump()
        }) { Text("Checks ${session.guardianMode}", color = Amber) }
        Text("Off skips the sheet. Warn and strict open it. Hits stay on this phone.", color = Mute, fontSize = 12.sp)
        TextButton(onClick = {
            session.autoLockSec = when (session.autoLockSec) {
                60 -> 300
                300 -> 900
                900 -> 3600
                3600 -> 0
                else -> 60
            }
            bump()
        }) { Text("Lock ${lockLabel(session.autoLockSec)}", color = Amber) }
        if (!session.accountEmail.isNullOrBlank()) {
            Text("Signed in as ${session.accountEmail}", color = Mute, fontSize = 12.sp)
            Text("Erase marks a device. The wipe runs the next time it contacts this box. A device that never comes back keeps its local files. Thread text is shared. File bytes stay in separate sealed rows.", color = Mute, fontSize = 12.sp)
            Field("Current password", currentPass, password = true) { currentPass = it }
            Field("New password", nextPass, password = true) { nextPass = it }
            TextButton(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { session.changePassword(currentPass, nextPass) }
                        note = "Password updated. The recovery code is unchanged."
                    } catch (e: Exception) {
                        note = if (e.message?.contains("Email or password") == true) "Email or password is wrong." else (e.message ?: "password failed")
                    }
                }
            }) { Text("Change password", color = Amber) }
            Field("New Bleep lock PIN", nextPin, password = true) { nextPin = it }
            TextButton(onClick = {
                if (nextPin.length < 4) { note = "PIN should be 4 characters or more."; return@TextButton }
                app.vault.save(nextPin, session.snapshot())
                onPin(nextPin)
                note = "PIN updated on this phone."
            }) { Text("Change PIN", color = Amber) }
            TextButton(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { session.removeHandle() }
                        note = "Handle removed. Directory search no longer returns it."
                    } catch (e: Exception) {
                        note = e.message ?: "remove failed"
                    }
                }
            }) { Text("Remove handle", color = Amber) }
            TextButton(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { session.closeAccount(currentPass) }
                        app.vault.erase()
                        onErased()
                    } catch (e: Exception) {
                        note = if (e.message?.contains("Email or password") == true) "Email or password is wrong." else (e.message ?: "close failed")
                    }
                }
            }) { Text("Close this mailbox", color = Bad) }
            TextButton(onClick = {
                scope.launch {
                    try {
                        deviceRows = withContext(Dispatchers.IO) { session.deviceRows() }
                        note = if (deviceRows.length() == 0) "No devices." else ""
                    } catch (e: Exception) {
                        note = e.message ?: "devices failed"
                    }
                }
            }) { Text("List devices", color = Amber) }
            for (i in 0 until deviceRows.length()) {
                val d = deviceRows.getJSONObject(i)
                val id = d.optString("device_id")
                Text(
                    d.optString("label") +
                        (if (d.optBoolean("this_device")) " · this phone" else "") +
                        (if (d.optBoolean("revoked")) " · revoked" else "") +
                        (if (d.optBoolean("erase_requested")) " · erase waiting" else ""),
                    color = Mute,
                    fontSize = 12.sp,
                )
                if (!d.optBoolean("this_device") && !d.optBoolean("revoked")) {
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { session.revokeDevice(id) }
                                    deviceRows = withContext(Dispatchers.IO) { session.deviceRows() }
                                } catch (e: Exception) {
                                    note = e.message ?: "revoke failed"
                                }
                            }
                        }) { Text("Revoke", color = Amber) }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { session.eraseRemote(id) }
                                    note = "Erase is waiting for that device's next contact."
                                    deviceRows = withContext(Dispatchers.IO) { session.deviceRows() }
                                } catch (e: Exception) {
                                    note = e.message ?: "erase failed"
                                }
                            }
                        }) { Text("Erase", color = Bad) }
                    }
                }
            }
        }
        Text("Export / restore (forced passphrase, no cloud)", color = Mute, fontSize = 12.sp)
        Field("Export passphrase 8+", pass, password = true) { pass = it }
        AmberButton("Encrypted export") { create.launch("bleep-export.json") }
        AmberButton("Restore export") { open.launch(arrayOf("application/json", "*/*")) }
        Text("Erase this device wipes this phone. The mailbox stays for your other devices.", color = Mute, fontSize = 12.sp)
        TextButton(onClick = {
            scope.launch {
                try { withContext(Dispatchers.IO) { session.eraseThisDevice() } } catch (_: Exception) {}
                onErased()
            }
        }) { Text("Erase this device", color = Bad) }
        if (note.isNotEmpty()) Text(note, color = Mute, fontSize = 12.sp)
    }
}

@Composable
fun ChatsPane(session: Session, bump: () -> Unit, open: (Peer) -> Unit, openGroup: (String) -> Unit, openChamber: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var hello by remember { mutableStateOf("Sawubona — request to message.") }
    var err by remember { mutableStateOf("") }
    var listMode by remember { mutableStateOf("chats") }
    var statusText by remember { mutableStateOf("") }
    var statusHits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var statusFile by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }
    var groupName by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var making by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val statusPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                }
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                var name = uri.lastPathSegment ?: "file"
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val bytes = if (mime.startsWith("image/")) shrinkJpeg(raw, 1280) else raw
                if (bytes.size > 3_000_000) {
                    err = "Keep status files under 3 MB."
                    return@launch
                }
                val hits = if (session.guardianMode == "off") emptyList() else Guardian.scanFile(name, mime, bytes) + Guardian.scan(statusText)
                if (hits.isNotEmpty()) {
                    statusHits = hits
                    statusFile = Triple(bytes, mime, name)
                } else {
                    withContext(Dispatchers.IO) { session.publishStatus(statusText, bytes, mime, name) }
                    statusText = ""
                    err = "Status sealed for 24 hours. There is no viewer list."
                    bump()
                }
            } catch (e: Exception) {
                err = e.message ?: "status failed"
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            listOf("chats" to "Chats", "chambers" to "Chambers", "groups" to "Groups").forEach { (id, label) ->
                TextButton(onClick = { listMode = id }) {
                    Text(label, color = if (listMode == id) Amber else Mute, fontSize = 13.sp)
                }
            }
        }
        if (listMode == "chambers") {
            if (session.chambers.isEmpty()) {
                Text("No chamber on this phone. A chamber is a separate chat with its own end date. Both people accept. Screenshots are not ours to delete.", color = Mute)
            }
            session.chambers.values.forEach { ch ->
                val whenBurn = java.time.Instant.ofEpochMilli(ch.burnAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Text(ch.title, color = TextC, modifier = Modifier.fillMaxWidth().clickable { openChamber(ch.id) }.padding(vertical = 8.dp))
                Text(
                    (if (ch.unlocked) "Open" else "Locked") + " · burns $whenBurn. Screenshots are not ours to delete.",
                    color = Mute,
                    fontSize = 12.sp,
                )
            }
            Text("Main history does not hold chamber text. The chamber PIN stays in memory only while that chamber is open.", color = Mute, fontSize = 12.sp)
            return@Column
        }
        if (listMode == "groups") {
            if (session.groups.isEmpty()) {
                Text("No groups. A group stays small, and each person joins on purpose.", color = Mute)
            }
            session.groups.values.forEach { g ->
                Text(
                    g.name,
                    color = TextC,
                    modifier = Modifier.fillMaxWidth().clickable { openGroup(g.id) }.padding(12.dp),
                )
            }
            TextButton(onClick = { making = !making }) { Text(if (making) "Hide new group" else "New group", color = Amber) }
            if (making) {
                Field("Group name", groupName) { groupName = it }
                Text("Pick up to 15 people. Join is explicit.", color = Mute, fontSize = 12.sp)
                session.peers.values.filter { it.accepted }.forEach { p ->
                    val on = p.mailboxId in picked
                    TextButton(onClick = {
                        picked = if (on) picked - p.mailboxId else if (picked.size >= 15) picked else picked + p.mailboxId
                    }) { Text((if (on) "✓ " else "") + chatName(p), color = if (on) Amber else TextC) }
                }
                AmberButton("Create group") {
                    scope.launch {
                        try {
                            val id = withContext(Dispatchers.IO) { session.createGroup(groupName, picked.toList()) }
                            groupName = ""
                            picked = emptySet()
                            making = false
                            bump()
                            openGroup(id.removePrefix("g:"))
                        } catch (e: Exception) {
                            err = e.message ?: "group failed"
                        }
                    }
                }
            }
            if (err.isNotEmpty()) Text(err, color = Bad)
            return@Column
        }
        Field("Bleep ID or handle", code) { code = it }
        var query by remember { mutableStateOf("") }
        var hits by remember { mutableStateOf(org.json.JSONArray()) }
        Field("Published handle", query) { query = it }
        AmberButton("Search handles") {
            scope.launch {
                try {
                    hits = withContext(Dispatchers.IO) { session.searchDirectory(query) }
                    if (query.trim().length >= 2 && hits.length() == 0) err = "No published handle with that prefix."
                    else err = ""
                } catch (e: Exception) {
                    err = e.message ?: "failed"
                }
            }
        }
        for (i in 0 until hits.length()) {
            val row = hits.getJSONObject(i)
            val handle = row.optString("handle")
            TextButton(onClick = {
                scope.launch {
                    try {
                        val p = session.addFromDirectory(
                            handle,
                            row.getString("mailbox_id"),
                            row.getString("identity_ed25519_b64"),
                            row.getString("identity_x25519_b64"),
                        )
                        withContext(Dispatchers.IO) { session.sendIntro(p, hello) }
                        bump()
                    } catch (e: Exception) {
                        err = e.message ?: "failed"
                    }
                }
            }) { Text("@$handle", color = Amber) }
        }
        Text("A hit is a published handle. Email is not searchable.", color = Mute, fontSize = 12.sp)
        Field("Request text", hello) { hello = it }
        if (err.isNotEmpty()) Text(err, color = Bad)
        AmberButton("Send request") {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val p = session.addByCode(code)
                        session.sendIntro(p, hello)
                    }
                    code = ""
                    bump()
                } catch (e: Exception) {
                    err = e.message ?: "failed"
                }
            }
        }
        var find by remember { mutableStateOf("") }
        Text("Status", color = TextC, fontSize = 16.sp)
        Text("A status stays for 24 hours. There is no viewer list. Capture is still possible.", color = Mute, fontSize = 12.sp)
        session.statuses.take(8).forEach { st ->
            Text((st.fromName.ifBlank { "Status" }) + (st.text?.let { ": $it" } ?: ""), color = TextC, fontSize = 13.sp)
            if (!st.bytesB64.isNullOrBlank() && (st.mime ?: "").startsWith("image/")) JpegFace(st.bytesB64, 160)
            else if (!st.name.isNullOrBlank() && !st.bytesB64.isNullOrBlank()) Text(st.name, color = Mute, fontSize = 12.sp)
        }
        Field("Status text", statusText) { statusText = it }
        if (statusHits.isNotEmpty()) {
            statusHits.forEach { Text(it.reason, color = Amber, fontSize = 13.sp) }
            Row {
                TextButton(onClick = { statusHits = emptyList(); statusFile = null }) { Text("Cancel", color = Mute) }
                TextButton(onClick = {
                    val file = statusFile
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (file != null && file.second.startsWith("image/") && statusHits.any { it.rule == "exif-gps" }) {
                                    session.publishStatus(statusText, Guardian.stripJpegExif(file.first), file.second, file.third)
                                } else if (file != null) {
                                    session.publishStatus(statusText, file.first, file.second, file.third)
                                } else session.publishStatus(statusText)
                            }
                            statusText = ""
                            statusHits = emptyList()
                            statusFile = null
                            err = "Status sealed for 24 hours. There is no viewer list."
                            bump()
                        } catch (e: Exception) {
                            err = e.message ?: "status failed"
                        }
                    }
                }) { Text("Share anyway", color = Amber) }
            }
        }
        Row {
            TextButton(onClick = { statusPick.launch("*/*") }) { Text("Add photo or file", color = Amber) }
            TextButton(onClick = {
                scope.launch {
                    try {
                        val hits = if (session.guardianMode == "off") emptyList() else Guardian.scan(statusText)
                        if (hits.isNotEmpty()) statusHits = hits
                        else {
                            withContext(Dispatchers.IO) { session.publishStatus(statusText) }
                            statusText = ""
                            err = "Status sealed for 24 hours. There is no viewer list."
                            bump()
                        }
                    } catch (e: Exception) {
                        err = e.message ?: "status failed"
                    }
                }
            }) { Text("Share status", color = Amber) }
        }
        Field("Search this phone", find) { find = it }
        val q = find.trim()
        session.peers.values.filter { it.accepted }.filter { p ->
            if (q.isEmpty()) true
            else {
                val name = p.nickname?.takeIf { it.isNotBlank() } ?: p.displayName
                name.contains(q, true) || session.threads[p.mailboxId].orEmpty().any { m -> m.text.contains(q, true) }
            }
        }.forEach { p ->
            Text(
                chatName(p),
                color = TextC,
                modifier = Modifier.fillMaxWidth().clickable { open(p) }.padding(12.dp),
            )
        }
    }
}

@Composable
fun RequestsPane(session: Session, bump: () -> Unit, onJoined: (String) -> Unit, onChamber: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.requests.isEmpty() && session.peers.values.none { !it.accepted }) Text("No requests.", color = Mute)
        session.requests.forEach { req ->
            val who = session.peers[req.peerId]?.displayName ?: "Request"
            if (!req.groupId.isNullOrBlank()) {
                Text("$who invited you to ${req.groupName ?: "a group"}. Join is explicit.", color = TextC)
                AmberButton("Join") {
                    session.acceptGroup(req.peerId, req.groupId, req.groupName ?: "Group")
                    bump()
                    onJoined(req.groupId)
                }
            } else if (req.chamberInvite) {
                Text("$who wants a chamber. Both of you accept. Screenshots are not ours to delete.", color = TextC)
                AmberButton("Accept chamber") {
                    session.acceptChamber(req.peerId, req.burnAt)
                    bump()
                    onChamber(req.peerId)
                }
            }
        }
        session.peers.values.filter { peer ->
            !peer.accepted && session.requests.any { it.peerId == peer.mailboxId && it.groupId.isNullOrBlank() && !it.chamberInvite }
        }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.displayName, color = TextC)
                AmberButton("Accept") { session.accept(p.mailboxId); bump() }
            }
        }
        session.peers.values.filter { peer -> !peer.accepted && session.requests.none { it.peerId == peer.mailboxId } }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.displayName, color = TextC)
                AmberButton("Accept") { session.accept(p.mailboxId); bump() }
            }
        }
    }
}

@Composable
fun ThreadPane(
    session: Session,
    peer: Peer?,
    bump: () -> Unit,
    groupId: String? = null,
    chamberId: String? = null,
    onOpenChamber: (String) -> Unit = {},
    onClose: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var pending by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var viewOnce by remember { mutableStateOf(false) }
    var chamberPin by remember(chamberId) { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val chamber = chamberId?.let { session.chambers[it] }
    val talk = if (chamberId != null) chamber?.peerMailboxId?.let { session.peers[it] } else peer
    val threadKey = when {
        chamberId != null -> "ch:$chamberId"
        groupId != null -> "g:$groupId"
        else -> peer!!.mailboxId
    }
    val group = groupId?.let { session.groups[it] }
    var reply by remember(threadKey) { mutableStateOf<ChatMsg?>(null) }
    fun safetyBlocked(): Boolean {
        if (groupId != null) {
            return session.groups[groupId]?.members?.any { session.peers[it.mailboxId]?.safetyChanged == true } == true
        }
        return talk?.safetyChanged == true
    }
    fun deliverText(body: String, rid: String?, rtext: String?, once: Boolean) {
        when {
            chamberId != null -> session.sendChamberText(chamberId, body, rid, rtext, once)
            groupId != null -> session.sendGroupText(groupId, body, rid, rtext, once)
            else -> session.sendText(talk!!, body, rid, rtext, once)
        }
    }
    fun deliverMedia(kind: String, bytes: ByteArray, mime: String, name: String, rid: String?, rtext: String?, once: Boolean) {
        when {
            chamberId != null -> session.sendChamberMedia(chamberId, kind, bytes, mime, name, rid, rtext, once)
            groupId != null -> session.sendGroupMedia(groupId, kind, bytes, mime, name, rid, rtext, once)
            else -> session.sendMedia(talk!!, kind, bytes, mime, name, rid, rtext, once)
        }
    }
    fun quoteOf(m: ChatMsg?): Pair<String?, String?> {
        if (m == null) return null to null
        return m.id to replyLine(m)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            }
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            var name = uri.lastPathSegment ?: "file"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val kind = when {
                mime.startsWith("video/") -> "file"
                mime.startsWith("image/") -> "photo"
                else -> "file"
            }
            val verified = if (group != null) group.members.all { m -> m.mailboxId == session.id.mailboxId || session.peers[m.mailboxId]?.verified == true } else talk?.verified == true
            val several = group != null && group.members.size > 2
            val g = if (session.guardianMode == "off") emptyList() else Guardian.scanFile(name, mime, bytes) + Guardian.scan(text, verified, several)
            if (g.isNotEmpty()) {
                hits = g
                pending = Triple(bytes, mime, name)
            } else {
                val (rid, rtext) = quoteOf(reply)
                withContext(Dispatchers.IO) { deliverMedia(kind, bytes, mime, name, rid, rtext, viewOnce) }
                reply = null
                viewOnce = false
            }
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
    val msgs = when {
        chamberId != null -> session.chamberThreads[threadKey]
        groupId != null -> session.groupThreads[threadKey]
        else -> session.threads[threadKey]
    }.orEmpty()
    var nick by remember(threadKey) { mutableStateOf(peer?.nickname.orEmpty()) }
    var showMedia by remember(threadKey) { mutableStateOf(false) }
    val dm = peer != null && groupId == null && chamberId == null
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (dm && peer != null) JpegFace(peer.avatarB64, 40)
        Text(
            when {
                chamber != null -> chamber.title
                group != null -> group.name + " · ${group.members.size}"
                peer != null -> chatName(peer) + if (peer.verified) " · verified" else " · unverified"
                else -> "Chamber"
            },
            color = TextC,
            fontSize = 18.sp,
        )
        if (dm && peer != null && !peer.nickname.isNullOrBlank()) Text(peer.displayName, color = Mute, fontSize = 12.sp)
        if (chamber != null) {
            val whenBurn = java.time.Instant.ofEpochMilli(chamber.burnAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            Text("Burns $whenBurn. Screenshots are not ours to delete.", color = Mute, fontSize = 12.sp)
            Text("Main history does not hold chamber text.", color = Mute, fontSize = 12.sp)
        } else {
            Text(
                if (session.captureNotice) "If this phone captures the screen, this chat gets a note."
                else "A capture on their side is not reported.",
                color = Mute,
                fontSize = 12.sp,
            )
        }
        if (talk != null && groupId == null) {
            Text(Crypto.safetyNumber(session.id.edPk, talk.edPk), color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        if (safetyBlocked()) {
            Text("Safety number changed. This is a new key. The composer is blocked until you verify — not a toast.", color = Bad, fontSize = 13.sp)
        }
        if (chamberId != null && (chamber == null || !chamber.unlocked)) {
            Text(
                if (chamber == null) "No chamber on this phone."
                else "This chamber is locked. A chamber PIN stays in memory only while it is open.",
                color = Mute,
                fontSize = 13.sp,
            )
            if (chamber != null) {
                Field("Chamber PIN", chamberPin, password = true) { chamberPin = it }
                AmberButton("Unlock") {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { session.unlockChamber(chamber.id, chamberPin) }
                        note = if (ok) "" else "That PIN did not open this chamber."
                        if (ok) chamberPin = ""
                        bump()
                    }
                }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { session.burnChamber(chamber.id) }
                        bump()
                        onClose()
                    }
                }) { Text("Burn", color = Bad) }
            }
            if (note.isNotEmpty()) Text(note, color = Bad, fontSize = 12.sp)
            return@Column
        }
        Text("Press and hold to reply. Capture is still possible.", color = Mute, fontSize = 12.sp)
        Row {
            if (dm && peer != null) {
                TextButton(onClick = { session.verifyPeer(peer); bump() }) { Text("Mark verified", color = Amber, fontSize = 12.sp) }
                TextButton(onClick = {
                    peer.disappearSec = nextDisappearSec(peer.disappearSec)
                    bump()
                }) { Text(disappearLabel(peer.disappearSec), color = Mute, fontSize = 12.sp) }
                TextButton(onClick = { cyclePeerMute(peer); bump() }) { Text(muteLabel(peer), color = Mute, fontSize = 12.sp) }
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val id = withContext(Dispatchers.IO) { session.startChamber(peer) }
                            bump()
                            onOpenChamber(id)
                        } catch (e: Exception) {
                            note = e.message ?: "chamber failed"
                            bump()
                        }
                    }
                }) { Text("Start a chamber", color = Amber, fontSize = 12.sp) }
            }
            if (chamber != null) {
                TextButton(onClick = { session.lockChamber(chamber.id); bump() }) { Text("Lock", color = Amber, fontSize = 12.sp) }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { session.burnChamber(chamber.id) }
                        bump()
                        onClose()
                    }
                }) { Text("Burn", color = Bad, fontSize = 12.sp) }
            }
            TextButton(onClick = { showMedia = !showMedia }) { Text(if (showMedia) "Hide photos" else "Photos and files", color = Mute, fontSize = 12.sp) }
        }
        if (dm && peer != null) {
            Field("Private name", nick) { nick = it }
            TextButton(onClick = { peer.nickname = nick.trim().ifBlank { null }; bump() }) { Text("Save private name", color = Amber, fontSize = 12.sp) }
        }
        if (note.isNotEmpty()) Text(note, color = Bad, fontSize = 12.sp)
        if (showMedia) {
            msgs.filter { it.kind == "photo" || it.kind == "file" || it.kind == "voice" }.forEach { m ->
                Text(if (m.kind == "voice") "Voice note" else (m.name ?: m.kind), color = TextC, fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(msgs.reversed()) { m ->
                val bg = if (m.fromMe) Color(0xFF2A2418) else Paper
                val hidden = m.viewOnce && !m.fromMe && !m.viewed && !m.deleted
                Column(
                    Modifier.padding(6.dp).background(bg, RoundedCornerShape(12.dp)).padding(10.dp)
                        .pointerInput(m.id, hidden, m.viewed) {
                            detectTapGestures(
                                onLongPress = { reply = m },
                                onTap = {
                                    if (!hidden) return@detectTapGestures
                                    session.openViewOnce(threadKey, m.id)
                                    bump()
                                    scope.launch {
                                        delay(8000)
                                        session.sweep()
                                        bump()
                                    }
                                },
                            )
                        },
                ) {
                    if (hidden) {
                        Text("Open once", color = Amber)
                        Text("Capture is still possible.", color = Mute, fontSize = 12.sp)
                    } else if (m.deleted) {
                        Text("Deleted", color = Mute)
                    } else {
                        if (m.fileId != null && m.bytesB64 == null) {
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { session.openAttachment(threadKey, m.id) }
                                    bump()
                                }
                            }) { Text("Open ${m.name ?: "file"}", color = Amber) }
                        }
                        if (m.kind == "photo" && m.bytesB64 != null) {
                            val bmp = remember(m.bytesB64) {
                                val b = Bytes.b64d(m.bytesB64)
                                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                            }
                            if (bmp != null) Image(bmp, "photo", Modifier.fillMaxWidth())
                        }
                        if (!m.replyText.isNullOrBlank()) Text(m.replyText, color = Mute, fontSize = 12.sp)
                        Text(
                            m.text + (if (m.viewOnce) " · Open once" else "") + (m.reacted?.let { "  $it" } ?: ""),
                            color = TextC,
                        )
                        if (m.viewOnce && m.viewed && !m.fromMe) Text("Capture is still possible.", color = Mute, fontSize = 12.sp)
                    }
                    Row {
                        TextButton(onClick = { reply = m }) { Text("Reply", color = Mute, fontSize = 12.sp) }
                        TextButton(onClick = { session.hideForMe(threadKey, m.id); bump() }) { Text("×", color = Mute, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (hits.isNotEmpty()) {
            val canStrip = hits.any { it.rule == "exif-gps" }
            hits.forEach { Text(it.reason, color = Amber, fontSize = 13.sp) }
            Row {
                TextButton(onClick = { hits = emptyList(); pending = null }) { Text("Cancel", color = Mute) }
                if (canStrip) TextButton(onClick = {
                    val file = pending
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val (rid, rtext) = quoteOf(reply)
                            if (file != null) deliverMedia(if (file.second.startsWith("image/")) "photo" else "file", file.first, file.second, file.third, rid, rtext, viewOnce)
                            else deliverText(text, rid, rtext, viewOnce)
                        }
                        text = ""; reply = null; viewOnce = false; hits = emptyList(); pending = null; bump()
                    }
                }) { Text("Send with location", color = Mute) }
                TextButton(onClick = {
                    val file = pending
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (file != null) {
                                val bytes = if (canStrip && file.second.startsWith("image/")) Guardian.stripJpegExif(file.first) else file.first
                                val (rid, rtext) = quoteOf(reply)
                                deliverMedia(if (file.second.startsWith("image/")) "photo" else "file", bytes, file.second, file.third, rid, rtext, viewOnce)
                            } else {
                                val (rid, rtext) = quoteOf(reply)
                                deliverText(text, rid, rtext, viewOnce)
                            }
                        }
                        text = ""; reply = null; viewOnce = false; hits = emptyList(); pending = null; bump()
                    }
                }) { Text(if (canStrip) "Send without location" else "Send anyway", color = Amber) }
            }
        }
        if (reply != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(replyLine(reply!!), color = Mute, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { reply = null }) { Text("Cancel", color = Mute) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { viewOnce = !viewOnce }) {
                Text(if (viewOnce) "Open once on" else "Open once", color = if (viewOnce) Amber else Mute)
            }
            TextButton(onClick = { pick.launch("*/*") }) { Text("+", color = Amber, fontSize = 20.sp) }
            TextButton(onClick = {
                if (recording) {
                    try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
                    recorder = null; recording = false
                    val f = recFile
                    if (f != null && f.exists()) {
                        val (rid, rtext) = quoteOf(reply)
                        scope.launch {
                            val bytes = f.readBytes()
                            withContext(Dispatchers.IO) { deliverMedia("voice", bytes, "audio/mp4", "voice.m4a", rid, rtext, viewOnce) }
                            reply = null
                            viewOnce = false
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
                    if (safetyBlocked()) return@Button
                    val verified = if (group != null) group.members.all { m -> m.mailboxId == session.id.mailboxId || session.peers[m.mailboxId]?.verified == true } else talk?.verified == true
                    val several = group != null && group.members.size > 2
                    val g = if (session.guardianMode == "off") emptyList() else Guardian.scan(text, verified, several)
                    if (g.isNotEmpty()) hits = g
                    else scope.launch {
                        val (rid, rtext) = quoteOf(reply)
                        withContext(Dispatchers.IO) {
                            deliverText(text, rid, rtext, viewOnce)
                        }
                        text = ""; reply = null; viewOnce = false; bump()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
            ) { Text("Send") }
        }
    }
}

@Composable
fun GuardianReport(session: Session, bump: () -> Unit, onMe: () -> Unit, onChats: () -> Unit) {
    var panel by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf(org.json.JSONArray()) }
    var deviceErr by remember { mutableStateOf(false) }
    var devicesLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val labels = listOf("Overview", "Device", "Account", "People", "Chambers", "Next")
    LaunchedEffect(session.deviceId) {
        try {
            rows = withContext(Dispatchers.IO) { session.deviceRows() }
            deviceErr = false
        } catch (_: Exception) {
            deviceErr = true
        }
        devicesLoaded = true
    }
    var mineLabel: String? = null
    var mineErase = false
    var othersLive = 0
    for (i in 0 until rows.length()) {
        val d = rows.getJSONObject(i)
        if (d.optBoolean("this_device")) {
            mineLabel = d.optString("label")
            mineErase = d.optBoolean("erase_requested")
        } else if (!d.optBoolean("revoked")) {
            othersLive++
        }
    }
    val threads = session.threads.size
    val blocked = session.blocked.size
    val verified = session.peers.values.count { it.verified }
    val counts = listOf(
        "Threads" to threads,
        "Requests" to session.requests.size,
        "Blocked" to blocked,
        "Verified" to verified,
    )
    val chambers = session.chambers.values.toList()
    val openChambers = chambers.filter { it.unlocked || (session.chamberPins[it.id]?.length ?: 0) >= 4 }
    val lockedCount = chambers.size - openChambers.size
    val pinInMemory = chambers.any { (session.chamberPins[it.id]?.length ?: 0) >= 4 }
    val lockNever = session.autoLockSec == 0
    val timer = when (session.autoLockSec) {
        0 -> "Never"
        60 -> "60 seconds"
        300 -> "5 minutes"
        900 -> "15 minutes"
        3600 -> "1 hour"
        else -> "${session.autoLockSec} seconds"
    }
    val risks = mutableListOf<String>()
    if (!session.regionOk) risks += "Region pin does not match, so sending is stopped."
    if (lockNever) risks += "Lock is Never."
    if (othersLive > 0) risks += "A device you do not recognise is still live."
    if (!session.handle.isNullOrBlank()) risks += "Handle is published."
    if (openChambers.isNotEmpty()) risks += "A chamber is unlocked on this screen."
    if (!session.webPocketKnown) risks += "Another device has not pulled shared history yet, so its pocket is not on this phone."
    val unverifiedPeer = session.peers.values.any { it.accepted && !it.verified && !it.blocked }
    val chamberLine = if (chambers.isEmpty()) "None" else "$lockedCount locked · ${openChambers.size} open"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This report uses this phone and this account. The relay still stores sealed ciphertext, the email, device rows, and published handles. It does not score chats, store directory searches, or recommend people.", color = Mute, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                TextButton(onClick = { panel = i }) {
                    Text(label, color = if (panel == i) Amber else Mute, fontSize = 12.sp)
                }
            }
        }
        when (panel) {
            0 -> {
                counts.forEach { (label, n) -> Text("$n  $label", color = TextC) }
                Text(if (session.regionOk) "ZA-JHB · Region pin" else "Stopped · Fail closed", color = if (session.regionOk) Amber else Bad)
                Text(if (lockNever) "$timer · Open state" else "$timer · Lock timer", color = if (lockNever) Bad else TextC)
                Text("$chamberLine · Chambers", color = if (openChambers.isNotEmpty()) Bad else TextC)
                Text("${risks.size} risk ${if (risks.size == 1) "sentence applies" else "sentences apply"}.", color = Mute, fontSize = 12.sp)
            }
            1 -> {
                Text(if (session.regionOk) "Region pin: ZA-JHB." else "Region pin does not match. This client is fail-closed and is not sending.", color = TextC)
                Text(if (lockNever) "Lock timer: Never. Never is the open state." else "Lock timer: $timer.", color = TextC)
                Text(
                    if (mineLabel != null) "This device’s label is $mineLabel." else "This device’s label loads with the device list.",
                    color = TextC,
                )
                Text(
                    if (mineErase) "A remote erase is waiting for the next contact." else "A remote erase is not waiting on this device.",
                    color = TextC,
                )
                Text("A device that never talks to the relay keeps its local copy.", color = Mute, fontSize = 12.sp)
            }
            2 -> {
                if (session.accountEmail.isNullOrBlank()) {
                    Text("This phone has no email on the account row.", color = TextC)
                } else {
                    Text("Signed in. This page does not say whether the address is confirmed.", color = TextC)
                }
                if (session.handle.isNullOrBlank()) {
                    Text("No handle is published.", color = TextC)
                } else {
                    Text("Handle @${session.handle} is published. The prefix directory can return the handle and the identity public keys. It does not return the email.", color = TextC)
                }
                for (i in 0 until rows.length()) {
                    val d = rows.getJSONObject(i)
                    Text(
                        d.optString("label") + " · hour band " + d.optString("created_band") +
                            (if (d.optBoolean("this_device")) " · this phone" else "") +
                            (if (d.optBoolean("revoked")) " · revoked" else "") +
                            (if (d.optBoolean("erase_requested")) " · erase requested" else ""),
                        color = TextC,
                        fontSize = 13.sp,
                    )
                }
                if (!devicesLoaded && !deviceErr) Text("Loading device rows.", color = Mute, fontSize = 12.sp)
                if (devicesLoaded && rows.length() == 0 && !deviceErr) Text("No device rows loaded.", color = Mute, fontSize = 12.sp)
                if (deviceErr) Text("Device list did not load.", color = Bad)
                Text("The recovery code was shown once at signup. It is not on this page. It is not on the relay.", color = Mute, fontSize = 12.sp)
            }
            3 -> {
                counts.forEach { (label, n) -> Text("$n  $label", color = TextC) }
                Text("Counts only. No message text, no attachment bytes, and no ranking of who you talk to.", color = Mute, fontSize = 12.sp)
                Text("Blocked entries stay in the local set that shared history round-trips. The directory skips them on this device.", color = Mute, fontSize = 12.sp)
            }
            4 -> {
                Text(
                    if (chambers.isEmpty()) "No chamber on this device." else "$lockedCount locked. ${openChambers.size} unlocked.",
                    color = TextC,
                )
                Text(
                    if (pinInMemory) "A chamber PIN is only in memory while it is open."
                    else "No chamber PIN is in memory. A chamber PIN is only kept in memory while that chamber is open.",
                    color = TextC,
                )
                Text("Opening Bleep does not open a chamber. The chamber blob uses the chamber PIN, separate from the account recovery key.", color = TextC)
                Text("Main history does not hold chamber text.", color = TextC)
            }
            else -> {
                if (risks.isNotEmpty()) {
                    Text("Risks", color = TextC, fontSize = 16.sp)
                    risks.forEach { Text(it, color = TextC) }
                }
                Text("Recommendations", color = TextC, fontSize = 16.sp)
                if (lockNever) {
                    TextButton(onClick = { session.autoLockSec = 300; bump() }) { Text("Set a lock timer", color = Amber) }
                }
                if (!session.handle.isNullOrBlank()) {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { session.removeHandle() }
                            } catch (_: Exception) {}
                            bump()
                        }
                    }) { Text("Remove the handle", color = Amber) }
                }
                if (othersLive > 0) TextButton(onClick = onMe) { Text("Revoke or erase a device", color = Amber) }
                if (unverifiedPeer) TextButton(onClick = onChats) { Text("Verify a safety number", color = Amber) }
                if (openChambers.isNotEmpty()) {
                    TextButton(onClick = {
                        openChambers.forEach { session.lockChamber(it.id) }
                        bump()
                    }) { Text("Lock the chamber", color = Amber) }
                }
                if (!session.accountEmail.isNullOrBlank()) {
                    TextButton(onClick = onMe) { Text("Erase this device", color = Amber) }
                    TextButton(onClick = onMe) { Text("Close this mailbox", color = Amber) }
                }
                Text("Erase this device wipes this vault and leaves the shared mailbox for the others. Close this mailbox is the separate action: every device loses it, and the email can be used again.", color = Mute, fontSize = 12.sp)
                Text("Verify a safety number before treating a peer as checked. Lock the chamber before you leave the screen.", color = Mute, fontSize = 12.sp)
                Text("What this page does not show", color = TextC, fontSize = 16.sp)
                Text("No behaviour score, no activity feed, and no risky contacts.", color = TextC)
                Text("No email search, no display-name search, and no recommendation list.", color = TextC)
                Text("No copy of the recovery seed, device credential, or chamber PIN.", color = TextC)
            }
        }
    }
}

fun chatName(p: Peer) = p.nickname?.takeIf { it.isNotBlank() } ?: p.displayName

fun replyLine(m: ChatMsg): String {
    val t = m.text.trim()
    if (t.isNotEmpty()) return t.take(80)
    return when (m.kind) {
        "photo" -> "Photo"
        "voice" -> "Voice note"
        "file" -> "File"
        else -> "Message"
    }
}

fun nextDisappearSec(sec: Int?): Int? = when (sec) {
    null -> 86400
    86400 -> 604800
    604800 -> 7776000
    else -> null
}

fun disappearLabel(sec: Int?) = when (sec) {
    null -> "Disappear 24h"
    86400 -> "Disappear 7 days"
    604800 -> "Disappear 90 days"
    else -> "Keep history"
}

fun cyclePeerMute(peer: Peer) {
    val now = System.currentTimeMillis()
    val until = peer.mutedUntil
    if (until != null && until > 0 && until <= now) peer.mutedUntil = null
    val current = peer.mutedUntil
    val active = current != null && (current < 0 || current > now)
    peer.mutedUntil = when {
        !active -> now + 8 * 3600_000
        current != null && current > now && current - now <= 8 * 3600_000 + 120_000 -> now + 7 * 86400_000
        current != null && current > now -> -1L
        else -> null
    }
}

fun muteLabel(peer: Peer): String {
    val now = System.currentTimeMillis()
    val until = peer.mutedUntil
    if (until == null || (until > 0 && until <= now)) return "Mute 8 hours"
    if (until < 0) return "Unmute"
    if (until - now <= 8 * 3600_000 + 120_000) return "Mute 1 week"
    return "Mute until I turn it off"
}

fun lockLabel(sec: Int): String = when (sec) {
    0 -> "never"
    60 -> "60 seconds"
    300 -> "5 minutes"
    900 -> "15 minutes"
    3600 -> "1 hour"
    else -> "$sec seconds"
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

@Composable
fun JpegFace(b64: String?, edge: Int) {
    if (b64.isNullOrBlank()) return
    val bmp = remember(b64) {
        try {
            val raw = Bytes.b64d(b64)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bmp != null) Image(bmp, "Profile photo", Modifier.size(edge.dp))
}

fun shrinkJpeg(bytes: ByteArray, maxEdge: Int): ByteArray {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val longest = maxOf(bmp.width, bmp.height).coerceAtLeast(1)
    val scale = maxEdge.toFloat() / longest
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    } else bmp
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
    return out.toByteArray()
}

fun qrBitmap(content: String): Bitmap {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.RGB_565)
    for (x in 0 until bits.width) for (y in 0 until bits.height) {
        bmp.setPixel(x, y, if (bits[x, y]) 0xFF101114.toInt() else 0xFFFFFFFF.toInt())
    }
    return bmp
}
