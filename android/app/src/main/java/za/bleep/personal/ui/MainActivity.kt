package za.bleep.personal.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import za.bleep.personal.protocol.LaterQueue
import za.bleep.personal.protocol.Peer
import za.bleep.personal.protocol.Polls
import za.bleep.personal.protocol.QuietHours
import za.bleep.personal.protocol.optionsOf
import za.bleep.personal.protocol.votesOf
import za.bleep.personal.protocol.REGION
import za.bleep.personal.protocol.Session
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File

private val Ink = Color(0xFF101827)
private val Paper = Color(0xFF1B273A)
private val Amber = Color(0xFF77BAFF)
private val TextC = Color(0xFFF1F5FF)
private val Mute = Color(0xFFB3C2D9)
private val Bad = Color(0xFFD46767)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun persist() {
        if (pin.isNotEmpty() && session.ready) {
            app.live = session
            app.livePin = pin
            try { app.vault.save(pin, session.snapshot()) } catch (_: Exception) { }
        }
        tick++
    }
    LaunchedEffect(gate, session.listenBackground) {
        if (gate == "app" && session.listenBackground && pin.isNotEmpty()) {
            app.live = session
            app.livePin = pin
            ListenService.start(ctx)
        }
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

    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life, session) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            session.uiResumed = event == androidx.lifecycle.Lifecycle.Event.ON_RESUME
            if (session.uiResumed) Notify.clearRing(ctx)
        }
        life.lifecycle.addObserver(obs)
        session.uiResumed = life.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    val call = remember { PhoneCall(ctx.applicationContext, session) }
    DisposableEffect(session) {
        session.onCall = { who, payload -> call.onSignal(who, payload) }
        onDispose {
            session.onCall = null
            call.hangup()
        }
    }
    var pendingCall by remember { mutableStateOf<Triple<Peer, String, Boolean>?>(null) }
    val askCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val job = pendingCall
        pendingCall = null
        if (job == null) return@rememberLauncherForActivityResult
        val (who, kind, answering) = job
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true
        val camOk = kind != "video" || result[Manifest.permission.CAMERA] == true
        if (!micOk || !camOk) {
            call.error = "The microphone stays off until you allow it."
            return@rememberLauncherForActivityResult
        }
        if (answering) call.accept() else call.start(who, kind)
    }
    fun beginCall(who: Peer, kind: String, answering: Boolean) {
        val needCam = kind == "video"
        val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = !needCam || ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (micOk && camOk) {
            if (answering) call.accept() else call.start(who, kind)
        } else {
            pendingCall = Triple(who, kind, answering)
            askCall.launch(
                if (needCam) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                else arrayOf(Manifest.permission.RECORD_AUDIO),
            )
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
        if (call.phase != "idle") CallSheet(call, { beginCall(it, call.media, true) }, { call.hangup() })
        if (call.error.isNotBlank() && call.phase == "idle") Text(call.error, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        when (gate) {
            "setup" -> AccountPane(session, err) { p ->
                pin = p
                app.live = session
                app.livePin = p
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
                        app.live = session
                        app.livePin = p
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
            "app" -> AppPane(session, tick, { persist() }, { gate = "lock" }, { pin = it }, { who, kind -> beginCall(who, kind, false) }, {
                call.hangup()
                session.listenBackground = false
                ListenService.stop(ctx)
                app.live = null
                app.livePin = ""
                app.vault.erase()
                gate = "setup"
                err = "This device was erased. A phone that never contacts the box keeps its files. This one just did."
            })
        }
    }
}

@Composable
fun AccountPane(session: Session, errIn: String, onOpen: (String) -> Unit) {
    var mode by remember { mutableStateOf("signin") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var resetToken by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://10.0.2.2:8090") }
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
                notice = "Address confirmed. Sign in to finish setting up your vault."
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
        Text("The key is made on this phone. Save your recovery code later from You → Account recovery.", color = Mute)
        if (mode != "reset") {
            Row {
                TextButton(onClick = { mode = "signin" }) {
                    Text("Sign in", color = if (mode == "signin") Amber else Mute)
                }
                TextButton(onClick = { mode = "create" }) {
                    Text("Create an account", color = if (mode == "create") Amber else Mute)
                }
            }
        }
        if (mode != "reset") Field("Email", email) { email = it }
        Field(if (mode == "reset") "New password" else "Password", password, password = true) { password = it }
        if (mode == "create") {
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
        if (err.isNotEmpty()) Text(err, color = Bad)
        if (notice.isNotEmpty()) Text(notice, color = Mute)
        AmberButton(
            when {
                busy -> "Working…"
                mode == "signin" -> "Open vault"
                mode == "reset" -> "Update password"
                else -> "Create account"
            },
        ) {
            if (busy) return@AmberButton
            err = ""
            notice = ""
            if (mode == "create") {
                if (password.length < 8) { err = "Password should be 8 characters or more."; return@AmberButton }
                if (password != again) { err = "Passwords do not match."; return@AmberButton }
                if (pin.length < 4) { err = "PIN should be 4 characters or more."; return@AmberButton }
                if (name.isBlank()) { err = "Add a display name."; return@AmberButton }
                if (email.isBlank()) { err = "Enter an email."; return@AmberButton }
            }
            if (mode == "signin" && pin.length < 4) { err = "PIN should be 4 characters or more."; return@AmberButton }
            busy = true
            session.relayBase(url)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        when (mode) {
                            "reset" -> session.completeReset(resetToken, password)
                            "signin" -> session.signIn(email, password, recovery)
                            else -> session.signUp(email, password, name, Crypto.freshRecoverySeed())
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
fun AppPane(session: Session, tick: Int, bump: () -> Unit, onLock: () -> Unit, onPin: (String) -> Unit, onStartCall: (Peer, String) -> Unit, onErased: () -> Unit) {
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
                withContext(Dispatchers.IO) {
                    session.poll()
                    session.pushHistory()
                }
                Notify.afterPoll(ctx, session)
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
                onStartCall,
            )
            tab == "me" -> MePane(session, bump, onPin, onErased)
            tab == "requests" -> RequestsPane(
                session,
                bump,
                { groupId = it; tab = "chats" },
                { chamberId = it; tab = "chats" },
                { id -> peer = session.peers[id]; tab = "chats" },
            )
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
        RecoveryCodePane(session)
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
        val listenCtx = androidx.compose.ui.platform.LocalContext.current
        val askListen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) return@rememberLauncherForActivityResult
            session.listenBackground = true
            app.live = session
            ListenService.start(listenCtx)
            bump()
        }
        TextButton(onClick = {
            if (session.listenBackground) {
                session.listenBackground = false
                ListenService.stop(listenCtx)
                bump()
                return@TextButton
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val perm = android.Manifest.permission.POST_NOTIFICATIONS
                if (listenCtx.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    askListen.launch(perm)
                    return@TextButton
                }
            }
            session.listenBackground = true
            app.live = session
            ListenService.start(listenCtx)
            bump()
        }) { Text(if (session.listenBackground) "Stop listening" else "Listen in the background", color = Amber) }
        Text("Checks for mail about every 20 seconds while Bleep stays in the background. The notice says Bleep. It does not include the message or the sender. Listening stops if the phone stops the app. A call rings on this phone while Bleep is listening. It does not ring after the app is stopped.", color = Mute, fontSize = 12.sp)
        Text("Alert sound plays on this phone. It does not say who sent the message.", color = Mute, fontSize = 12.sp)
        TextButton(onClick = {
            session.alertSound = !session.alertSound
            bump()
        }) { Text(if (session.alertSound) "Alert sound on" else "Alert sound off", color = Amber) }
        var quietFrom by remember { mutableStateOf(QuietHours.clock(session.quietStartMin)) }
        var quietUntil by remember { mutableStateOf(QuietHours.clock(session.quietEndMin)) }
        Text("Quiet hours silence the alert and the ring. Messages still arrive. These hours stay on this phone.", color = Mute, fontSize = 12.sp)
        Field("Quiet from", quietFrom) { quietFrom = it }
        Field("Quiet until", quietUntil) { quietUntil = it }
        TextButton(onClick = {
            session.quietStartMin = QuietHours.parseClock(quietFrom)
            session.quietEndMin = QuietHours.parseClock(quietUntil)
            bump()
        }) { Text("Save quiet hours", color = Amber) }
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
    var leaving by remember { mutableStateOf<String?>(null) }
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
            listOf("chats" to "Chats", "chambers" to "Chambers", "groups" to "Groups", "channels" to "Channels", "starred" to "Starred").forEach { (id, label) ->
                TextButton(onClick = { listMode = id }) {
                    Text(label, color = if (listMode == id) Amber else Mute, fontSize = 13.sp)
                }
            }
        }
        if (listMode == "starred") {
            val rows = (session.threads.flatMap { (key, list) -> list.map { key to it } } +
                session.groupThreads.flatMap { (key, list) -> list.map { key to it } })
                .filter { it.second.starred && !it.second.deleted }
            if (rows.isEmpty()) Text("No starred messages on this phone.", color = Mute)
            rows.forEach { (key, m) ->
                Text(
                    (m.pollQuestion ?: m.text).ifBlank { m.kind },
                    color = TextC,
                    modifier = Modifier.fillMaxWidth().clickable {
                        when {
                            key.startsWith("g:") -> openGroup(key.removePrefix("g:"))
                            else -> session.peers[key]?.let { open(it) }
                        }
                    }.padding(vertical = 8.dp),
                )
            }
            return@Column
        }
        if (listMode == "channels") {
            ChannelsBlock(session, bump)
            return@Column
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
                val last = session.groupThreads["g:${g.id}"].orEmpty().lastOrNull { !it.deleted }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(g.name, color = TextC, modifier = Modifier.fillMaxWidth().clickable { openGroup(g.id) })
                    if (last != null) Text(last.text.ifBlank { last.kind }, color = Mute, fontSize = 12.sp, maxLines = 1)
                    if (leaving == g.id) {
                        Text("Leave this group? People already in it keep their copy.", color = Mute, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { session.leaveGroup(g.id) }
                                        leaving = null
                                        bump()
                                    } catch (e: Exception) {
                                        err = e.message ?: "Could not leave the group."
                                    }
                                }
                            }) { Text("Leave group", color = Bad) }
                            TextButton(onClick = { leaving = null }) { Text("Stay", color = TextC) }
                        }
                    } else {
                        TextButton(onClick = { leaving = g.id }) { Text("Leave group", color = Amber) }
                    }
                }
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
                        open(p)
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
                    val p = withContext(Dispatchers.IO) {
                        val added = session.addByCode(code)
                        session.sendIntro(added, hello)
                        added
                    }
                    code = ""
                    bump()
                    open(p)
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
            val last = session.threads[p.mailboxId].orEmpty().lastOrNull { !it.deleted }
            Column(Modifier.fillMaxWidth().clickable { open(p) }.padding(vertical = 8.dp)) {
                Text(chatName(p), color = TextC)
                if (last != null) {
                    val line = last.text.ifBlank {
                        when (last.kind) {
                            "photo" -> "Photo"
                            "voice" -> "Voice note"
                            else -> last.kind
                        }
                    }
                    Text(line, color = Mute, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun RequestsPane(
    session: Session,
    bump: () -> Unit,
    onJoined: (String) -> Unit,
    onChamber: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.requests.isEmpty() && session.peers.values.none { !it.accepted }) Text("No requests.", color = Mute)
        session.requests.forEach { req ->
            val who = session.peers[req.peerId]?.displayName ?: "Request"
            if (!req.groupId.isNullOrBlank()) {
                Text("$who invited you to ${req.groupName ?: "a group"}. Join is explicit.", color = TextC)
                if (req.text.isNotBlank()) Text(req.text, color = Mute, fontSize = 13.sp)
                AmberButton("Join") {
                    session.acceptGroup(req.peerId, req.groupId, req.groupName ?: "Group")
                    bump()
                    onJoined(req.groupId)
                }
            } else if (req.chamberInvite) {
                Text("$who wants a chamber. Both of you accept. Screenshots are not ours to delete.", color = TextC)
                if (req.text.isNotBlank()) Text(req.text, color = Mute, fontSize = 13.sp)
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
            val hello = session.requests.lastOrNull { it.peerId == p.mailboxId && it.groupId.isNullOrBlank() && !it.chamberInvite }?.text
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(p.displayName, color = TextC)
                Text(hello?.ifBlank { null } ?: "Request to message", color = Mute, fontSize = 13.sp)
                AmberButton("Accept") {
                    session.accept(p.mailboxId)
                    bump()
                    onOpen(p.mailboxId)
                }
            }
        }
        session.peers.values.filter { peer -> !peer.accepted && session.requests.none { it.peerId == peer.mailboxId } }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.displayName, color = TextC)
                AmberButton("Accept") {
                    session.accept(p.mailboxId)
                    bump()
                    onOpen(p.mailboxId)
                }
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
    onStartCall: (Peer, String) -> Unit = { _, _ -> },
) {
    var text by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var pending by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var viewOnce by remember { mutableStateOf(false) }
    var laterWhen by remember { mutableStateOf("") }
    var showLater by remember { mutableStateOf(false) }
    var showPoll by remember { mutableStateOf(false) }
    var pollQ by remember { mutableStateOf("") }
    var pollA by remember { mutableStateOf("") }
    var pollB by remember { mutableStateOf("") }
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
        if (dm && peer != null) {
            val seen = session.presence[peer.mailboxId]
            Text(
                if (seen?.first == true) "Online. The time is the last mail fetch."
                else if (seen?.second != null) "Last seen ${preciseSeen(seen.second!!)}."
                else "No precise status yet.",
                color = Mute,
                fontSize = 12.sp,
            )
            LiveLocationButton(session, peer, bump)
        }
        Text("The system screenshot is blocked while Bleep is open. A photograph of the screen still works.", color = Mute, fontSize = 12.sp)
        Row {
            if (dm && peer != null) {
                TextButton(onClick = { onStartCall(peer, "audio") }) { Text("Call", color = Amber, fontSize = 12.sp) }
                TextButton(onClick = { onStartCall(peer, "video") }) { Text("Video", color = Amber, fontSize = 12.sp) }
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
                val bg = if (m.fromMe) Color(0xFF203D60) else Paper
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
                        if (m.kind == "poll" && groupId != null) {
                            val opts = optionsOf(m.pollOptionsJson)
                            val counts = Polls.counts(votesOf(m.pollVotesJson), opts.size)
                            Text("Counts are what this phone has opened.", color = Mute, fontSize = 12.sp)
                            opts.forEachIndexed { i, opt ->
                                TextButton(onClick = {
                                    if (m.pollClosed) return@TextButton
                                    scope.launch {
                                        withContext(Dispatchers.IO) { session.voteGroupPoll(groupId, m.id, i) }
                                        bump()
                                    }
                                }) { Text("$opt · ${counts.getOrElse(i) { 0 }}", color = Amber, fontSize = 12.sp) }
                            }
                            if (m.fromMe && !m.pollClosed) {
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { session.closeGroupPoll(groupId, m.id) }
                                        bump()
                                    }
                                }) { Text("Close poll", color = Mute, fontSize = 12.sp) }
                            }
                        }
                        if (m.viewOnce && m.viewed && !m.fromMe) Text("Capture is still possible.", color = Mute, fontSize = 12.sp)
                    }
                    Row {
                        TextButton(onClick = { session.toggleStar(threadKey, m.id); bump() }) { Text(if (m.starred) "★" else "☆", color = Amber, fontSize = 12.sp) }
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
            if (chamberId == null) {
                TextButton(onClick = { showLater = !showLater }) { Text("Send later", color = Mute, fontSize = 12.sp) }
            }
            if (groupId != null) {
                TextButton(onClick = { showPoll = !showPoll }) { Text("Poll", color = Mute, fontSize = 12.sp) }
            }
        }
        if (showLater && chamberId == null) {
            Text("Sends while Bleep is open, or while this phone is listening. Otherwise it sends the next time you open Bleep. The relay does not see it early.", color = Mute, fontSize = 12.sp)
            Field("Minutes, or a clock like 18:30", laterWhen) { laterWhen = it }
            TextButton(onClick = {
                val whenAt = LaterQueue.parseSendAt(laterWhen, System.currentTimeMillis())
                if (text.isBlank() || whenAt < 0) {
                    note = "Choose a future time."
                    return@TextButton
                }
                note = session.scheduleText(threadKey, text, whenAt)
                text = ""
                showLater = false
                bump()
            }) { Text("Schedule", color = Amber) }
        }
        if (showPoll && groupId != null) {
            Field("Question", pollQ) { pollQ = it }
            Field("Choice 1", pollA) { pollA = it }
            Field("Choice 2", pollB) { pollB = it }
            TextButton(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { session.sendGroupPoll(groupId, pollQ, listOf(pollA, pollB)) }
                        pollQ = ""; pollA = ""; pollB = ""; showPoll = false
                    } catch (e: Exception) {
                        note = e.message ?: "A poll needs a question and at least two choices."
                    }
                    bump()
                }
            }) { Text("Send poll", color = Amber) }
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
                Text("To see your recovery code, open Account recovery in You.", color = Mute, fontSize = 12.sp)
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

@Composable
fun LiveLocationButton(session: Session, peer: Peer, bump: () -> Unit) {
    var sharing by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sharing = true
    }
    LaunchedEffect(sharing) {
        if (!sharing) return@LaunchedEffect
        val mgr = ctx.getSystemService(android.location.LocationManager::class.java) ?: run {
            sharing = false
            return@LaunchedEffect
        }
        val until = System.currentTimeMillis() + 15 * 60 * 1000
        var lastSent = 0L
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                val now = System.currentTimeMillis()
                if (now > until) {
                    sharing = false
                    return
                }
                if (now - lastSent < 15_000L) return
                lastSent = now
                scope.launch {
                    withContext(Dispatchers.IO) { session.sendLocation(peer, loc.latitude, loc.longitude, until) }
                    bump()
                }
            }
        }
        val provider = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).firstOrNull { mgr.allProviders.contains(it) }
        if (provider == null) {
            sharing = false
            return@LaunchedEffect
        }
        try {
            mgr.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
            mgr.requestLocationUpdates(provider, 20_000L, 0f, listener, android.os.Looper.getMainLooper())
            delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
        } catch (_: SecurityException) {
        } finally {
            runCatching { mgr.removeUpdates(listener) }
            sharing = false
        }
    }
    TextButton(onClick = {
        if (sharing) {
            sharing = false
            return@TextButton
        }
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (ctx.checkSelfPermission(fine) == android.content.pm.PackageManager.PERMISSION_GRANTED) sharing = true
        else ask.launch(fine)
    }) { Text(if (sharing) "Stop live location" else "Live location", color = Amber, fontSize = 12.sp) }
    if (sharing) Text("Sharing this chat for 15 minutes. It stops when you leave or tap Stop. Location is not sent in the background.", color = Mute, fontSize = 12.sp)
}

@Composable
fun ChannelsBlock(session: Session, bump: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var post by remember { mutableStateOf("") }
    var rooms by remember { mutableStateOf(org.json.JSONArray()) }
    var open by remember { mutableStateOf<String?>(null) }
    var posts by remember { mutableStateOf(org.json.JSONArray()) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { rooms = withContext(Dispatchers.IO) { session.channelNames() } } catch (e: Exception) { err = e.message ?: "channels failed" }
    }
    Text("A public channel is readable by any Bleep account. The relay stores the text in the clear.", color = Mute, fontSize = 12.sp)
    Field("Channel name", name) { name = it }
    TextButton(onClick = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { session.createChannel(name.trim()) }
                name = ""
                rooms = withContext(Dispatchers.IO) { session.channelNames() }
                bump()
            } catch (e: Exception) { err = e.message ?: "channel failed" }
        }
    }) { Text("Create channel", color = Amber) }
    for (i in 0 until rooms.length()) {
        val row = rooms.getJSONObject(i)
        Text(row.optString("name"), color = TextC, modifier = Modifier.clickable {
            open = row.optString("id")
            scope.launch {
                try { posts = withContext(Dispatchers.IO) { session.channelPosts(open!!) } } catch (e: Exception) { err = e.message ?: "posts failed" }
            }
        }.padding(vertical = 8.dp))
    }
    if (open != null) {
        for (i in 0 until posts.length()) {
            val p = posts.getJSONObject(i)
            Text(p.optString("name") + " " + p.optString("text"), color = TextC, fontSize = 13.sp)
        }
        Field("Post", post) { post = it }
        TextButton(onClick = {
            val id = open ?: return@TextButton
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { session.postChannel(id, post.trim()) }
                    post = ""
                    posts = withContext(Dispatchers.IO) { session.channelPosts(id) }
                } catch (e: Exception) { err = e.message ?: "post failed" }
            }
        }) { Text("Post", color = Amber) }
    }
    if (err.isNotEmpty()) Text(err, color = Bad)
}

fun preciseSeen(iso: String): String = try {
    val odt = java.time.OffsetDateTime.parse(iso)
    odt.atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
} catch (_: Exception) {
    iso
}

fun chatName(p: Peer) = p.nickname?.takeIf { it.isNotBlank() } ?: p.displayName

@Composable
private fun CallSheet(call: PhoneCall, onAnswer: (Peer) -> Unit, onHangup: () -> Unit) {
    val who = call.peer
    val title = when (call.phase) {
        "active" -> "In call"
        "ringing-in" -> "Incoming call"
        else -> "Calling…"
    }
    Column(Modifier.fillMaxWidth().padding(12.dp).background(Paper, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(title, color = TextC, fontSize = 18.sp)
        Text(chatName(who ?: return), color = Mute, fontSize = 13.sp)
        Text("Signalling is sealed. TURN never sees a name. The call stays while Bleep is open.", color = Mute, fontSize = 12.sp)
        if (call.media == "video") {
            val egl = call.egl()
            val remote = call.remoteTrack
            val local = call.localTrack
            if (egl != null && remote != null) CallVideo(remote, egl, mirror = false, Modifier.fillMaxWidth().height(220.dp))
            if (egl != null && local != null) CallVideo(local, egl, mirror = true, Modifier.fillMaxWidth().height(120.dp))
        }
        if (call.error.isNotBlank()) Text(call.error, color = Bad, fontSize = 13.sp)
        Row {
            if (call.phase == "ringing-in" && who != null) {
                TextButton(onClick = { onAnswer(who) }) { Text("Answer", color = Amber) }
            }
            TextButton(onClick = onHangup) {
                Text(if (call.phase == "ringing-in") "Decline" else "Hang up", color = Bad)
            }
        }
    }
}

@Composable
private fun CallVideo(track: VideoTrack, egl: EglBase, mirror: Boolean, modifier: Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, null)
                setMirror(mirror)
                setEnableHardwareScaler(true)
                view = this
            }
        },
        modifier = modifier,
        onRelease = { renderer ->
            view = null
            renderer.release()
        },
    )
    DisposableEffect(track, view) {
        val renderer = view
        if (renderer != null) track.addSink(renderer)
        onDispose {
            if (renderer != null) track.removeSink(renderer)
        }
    }
}

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
private fun RecoveryCodePane(session: Session) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as BleepApp
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    DisposableEffect(life) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                attempt++
                pin = ""
                code = ""
            }
        }
        life.lifecycle.addObserver(observer)
        onDispose { life.lifecycle.removeObserver(observer) }
    }
    Text("Account recovery", color = TextC, fontSize = 18.sp)
    if (session.recoverySeed.isNullOrBlank()) {
        Text("Use an enrolled device that holds your recovery code.", color = Mute)
        return
    }
    Field("Vault PIN", pin, password = true) { pin = it }
    TextButton(enabled = pin.isNotEmpty(), onClick = {
        val enteredPin = pin
        val request = ++attempt
        pin = ""
        code = ""
        error = ""
        scope.launch {
            val verified = withContext(Dispatchers.IO) {
                runCatching { app.vault.load(enteredPin) }.isSuccess
            }
            if (request != attempt) return@launch
            if (!verified) error = "Could not verify your vault PIN."
            else session.recoverySeed?.let { code = Crypto.formatRecovery(Bytes.b64d(it)) }
        }
    }) { Text("Show recovery code", color = Amber) }
    if (error.isNotBlank()) Text(error, color = Bad)
    if (code.isNotBlank()) {
        Text(code, color = TextC, fontFamily = FontFamily.Monospace)
        TextButton(onClick = { code = "" }) { Text("Hide recovery code", color = Amber) }
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
