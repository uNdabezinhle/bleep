package za.bleep.personal.ui

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import za.bleep.personal.protocol.CallIce
import za.bleep.personal.protocol.Peer
import za.bleep.personal.protocol.Session
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A 1:1 call that uses the same sealed offer, answer, ICE, hangup, and missed
 * payloads as the website. The microphone opens only after the activity has
 * permission. The call stays while this process is open.
 */
class PhoneCall(private val app: Context, private val session: Session) {
    var phase by mutableStateOf("idle")
        private set
    var media by mutableStateOf("audio")
        private set
    var peer by mutableStateOf<Peer?>(null)
        private set
    var error by mutableStateOf("")
    var remoteTrack by mutableStateOf<VideoTrack?>(null)
        private set
    var localTrack by mutableStateOf<VideoTrack?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var pendingOffer: String? = null
    private val earlyIce = mutableListOf<IceCandidate>()
    private var closed = true
    private var missed: Runnable? = null
    private var turnUser = CallIce.USER
    private var turnPass = CallIce.PASS
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var routed = false

    fun onSignal(who: Peer, payload: JSONObject) {
        val copy = JSONObject(payload.toString())
        if (copy.optString("phase") == "offer" && phase == "idle") session.ringOffer = true
        if (copy.optString("phase") == "hangup" || copy.optString("phase") == "missed") session.ringOffer = false
        main.post { handle(who, copy) }
    }

    fun start(who: Peer, kind: String) {
        main.post {
            if (refused(who)) return@post
            teardown(sendHangup = phase != "idle")
            error = ""
            peer = who
            media = if (kind == "video") "video" else "audio"
            phase = "ringing-out"
            withTurn { beginOffer(who) }
        }
    }

    private fun beginOffer(who: Peer) {
        if (phase != "ringing-out") return
        if (!openPeer()) {
            teardown(sendHangup = false)
            return
        }
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (media == "video") "true" else "false"))
        val conn = pc ?: return
        conn.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null || closed) {
                    fail("This phone could not start the call.")
                    return
                }
                setLocal(desc) {
                    seal(who, "offer", sdp = desc.description, media = media, failCall = true)
                    armMissed()
                }
            }
            override fun onCreateFailure(message: String?) = fail("This phone could not start the call.")
            override fun onSetSuccess() {}
            override fun onSetFailure(message: String?) {}
        }, constraints)
    }

    fun accept() {
        main.post {
            val who = peer ?: return@post
            val sdp = pendingOffer ?: return@post
            if (refused(who)) return@post
            withTurn { beginAnswer(who, sdp) }
        }
    }

    private fun beginAnswer(who: Peer, sdp: String) {
        if (phase != "ringing-in") return
        if (!openPeer()) {
            teardown(sendHangup = false)
            return
        }
        val conn = pc ?: return
        conn.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    flushIce()
                    conn.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            if (desc == null || closed) {
                                fail("This phone could not answer.")
                                return
                            }
                            setLocal(desc) {
                                seal(who, "answer", sdp = desc.description, media = media, failCall = true)
                                phase = "active"
                            }
                        }
                        override fun onCreateFailure(message: String?) = fail("This phone could not answer.")
                        override fun onSetSuccess() {}
                        override fun onSetFailure(message: String?) {}
                    }, MediaConstraints())
                }
                override fun onSetFailure(message: String?) = fail("This phone could not answer.")
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onCreateFailure(message: String?) {}
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun hangup() {
        main.post { teardown(sendHangup = phase != "idle") }
    }

    fun egl(): EglBase? = sharedEgl

    private fun handle(who: Peer, pt: JSONObject) {
        when (pt.optString("phase")) {
            "offer" -> {
                if (phase != "idle") return
                val sdp = pt.optString("sdp")
                if (sdp.isBlank()) return
                pendingOffer = sdp
                peer = who
                media = if (pt.optString("media") == "video") "video" else "audio"
                error = ""
                phase = "ringing-in"
            }
            "answer" -> {
                val conn = pc
                val sdp = pt.optString("sdp")
                if (phase != "ringing-out" || conn == null || sdp.isBlank()) return
                cancelMissed()
                conn.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        flushIce()
                        phase = "active"
                    }
                    override fun onSetFailure(message: String?) = fail("The other side did not connect.")
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(message: String?) {}
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice" -> {
                val parsed = CallIce.parseCandidate(pt.optString("candidate")) ?: return
                val cand = IceCandidate(parsed.first, parsed.second, parsed.third)
                val conn = pc
                if (conn?.remoteDescription != null) conn.addIceCandidate(cand) else earlyIce.add(cand)
            }
            "hangup" -> {
                val wasRinging = phase == "ringing-in"
                val whoNow = peer
                teardown(sendHangup = false)
                if (wasRinging && whoNow != null) session.noteMissedCall(whoNow, false)
            }
            "missed" -> {
                val whoNow = peer ?: who
                teardown(sendHangup = false)
                session.noteMissedCall(whoNow, false)
            }
        }
    }

    private fun refused(who: Peer): Boolean {
        if (who.safetyChanged) {
            error = "Safety number changed. This is a new key. Verify it before you send."
            return true
        }
        if (who.xPk.size != 32 || who.xPk.all { it == 0.toByte() }) {
            error = "This chat is on this phone, but the key has not arrived yet."
            return true
        }
        return false
    }

    private fun openPeer(): Boolean {
        val factory = factory() ?: run {
            error = "This phone could not open the call."
            return false
        }
        closed = false
        routeAudio(true)
        val servers = CallIce.urls(session.boxUrl()).map { url ->
            if (url.startsWith("turn:")) {
                PeerConnection.IceServer.builder(url).setUsername(turnUser).setPassword(turnPass).createIceServer()
            } else {
                PeerConnection.IceServer.builder(url).createIceServer()
            }
        }
        val config = PeerConnection.RTCConfiguration(servers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val conn = factory.createPeerConnection(config, observer)
        if (conn == null) {
            error = "This phone could not open the call."
            return false
        }
        pc = conn
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = factory.createAudioTrack("bleep-audio", source)
        track.setEnabled(true)
        conn.addTrack(track, listOf("bleep"))
        if (media == "video") {
            val camera = openCamera()
            if (camera == null) {
                error = "This phone has no camera for a video call."
                return false
            }
            capturer = camera
            val egl = sharedEgl ?: return false
            val texture = SurfaceTextureHelper.create("bleep-cam", egl.eglBaseContext)
            helper = texture
            val video = factory.createVideoSource(camera.isScreencast)
            videoSource = video
            camera.initialize(texture, app, video.capturerObserver)
            camera.startCapture(640, 480, 24)
            val local = factory.createVideoTrack("bleep-video", video)
            local.setEnabled(true)
            localTrack = local
            conn.addTrack(local, listOf("bleep"))
        }
        return true
    }

    private fun openCamera(): VideoCapturer? {
        val enumerator = Camera2Enumerator(app)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        return enumerator.createCapturer(name, null)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {
            if (closed) return
            val who = peer ?: return
            seal(who, "ice", candidate = CallIce.candidateJson(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
            showRemote(receiver.track())
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            showRemote(transceiver.receiver.track())
        }
    }

    private fun showRemote(track: org.webrtc.MediaStreamTrack?) {
        if (track is AudioTrack) track.setEnabled(true)
        if (track is VideoTrack) main.post { remoteTrack = track }
    }

    private fun setLocal(desc: SessionDescription, after: () -> Unit) {
        val conn = pc ?: return
        conn.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                if (!closed) main.post(after)
            }
            override fun onSetFailure(message: String?) = fail("This phone could not start the call.")
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(message: String?) {}
        }, desc)
    }

    private fun flushIce() {
        val conn = pc ?: return
        val queued = earlyIce.toList()
        earlyIce.clear()
        queued.forEach { conn.addIceCandidate(it) }
    }

    private fun withTurn(then: () -> Unit) {
        Thread {
            val (user, pass) = session.turnLogin()
            main.post {
                turnUser = user
                turnPass = pass
                then()
            }
        }.start()
    }

    private fun seal(
        who: Peer,
        phaseName: String,
        sdp: String? = null,
        candidate: String? = null,
        media: String? = null,
        failCall: Boolean = false,
    ) {
        Thread {
            try {
                session.sendCall(who, phaseName, sdp, candidate, media)
            } catch (e: Exception) {
                main.post {
                    error = e.message ?: "The call did not send."
                    if (failCall) teardown(sendHangup = false)
                }
            }
        }.start()
    }

    private fun armMissed() {
        cancelMissed()
        val job = Runnable {
            if (phase != "ringing-out") return@Runnable
            val who = peer ?: return@Runnable
            seal(who, "missed")
            session.noteMissedCall(who, true)
            teardown(sendHangup = false)
        }
        missed = job
        main.postDelayed(job, 30_000)
    }

    private fun cancelMissed() {
        missed?.let { main.removeCallbacks(it) }
        missed = null
    }

    private fun fail(text: String) {
        main.post {
            error = text
            teardown(sendHangup = false)
        }
    }

    private fun teardown(sendHangup: Boolean) {
        val who = peer
        val shouldSend = sendHangup && who != null && phase != "idle"
        session.ringOffer = false
        cancelMissed()
        closed = true
        pendingOffer = null
        earlyIce.clear()
        remoteTrack = null
        localTrack = null
        try { pc?.close() } catch (_: Exception) {}
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { helper?.dispose() } catch (_: Exception) {}
        helper = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        routeAudio(false)
        phase = "idle"
        peer = null
        media = "audio"
        if (shouldSend && who != null) seal(who, "hangup")
    }

    private fun routeAudio(on: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            if (routed) return
            prevMode = am.mode
            prevSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            routed = true
        } else if (routed) {
            am.isSpeakerphoneOn = prevSpeaker
            am.mode = prevMode
            routed = false
        }
    }

    private fun factory(): PeerConnectionFactory? {
        if (sharedFactory != null) return sharedFactory
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
            )
            val egl = EglBase.create()
            sharedEgl = egl
            val audio = org.webrtc.audio.JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
            sharedFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audio)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            sharedFactory
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private var sharedFactory: PeerConnectionFactory? = null
        private var sharedEgl: EglBase? = null
    }
}
