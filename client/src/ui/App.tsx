import { useEffect, useMemo, useRef, useState } from "react";
import QRCode from "qrcode";
import jsQR from "jsqr";
import { Runtime } from "../runtime";
import { bleepCode } from "../protocol/keys";
import { pairSafety } from "../session/session";
import { profileName } from "../store/vault";
import type { Hit } from "../guardian/engine";
import type { Message, Peer, Thread } from "../types";

const rt = new Runtime();

type Tab = "chats" | "requests" | "status" | "chambers" | "settings";
type Gate = "boot" | "setup" | "lock" | "app";

export function App() {
  const [, setTick] = useState(0);
  const bump = () => setTick((x) => x + 1);
  const [gate, setGate] = useState<Gate>("boot");
  const [tab, setTab] = useState<Tab>("chats");
  const [threadId, setThreadId] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [groupOpen, setGroupOpen] = useState(false);
  const [hits, setHits] = useState<{ hits: Hit[]; text: string; files: FileBits[]; thread: Thread; viewOnce: boolean } | null>(null);
  const [err, setErr] = useState("");
  const [narrow, setNarrow] = useState(() => window.innerWidth < 780);

  useEffect(() => {
    rt.onChange = bump;
    void rt.boot().then(setGate);
    const on = () => setNarrow(window.innerWidth < 780);
    window.addEventListener("resize", on);
    const act = () => rt.touch();
    window.addEventListener("pointerdown", act);
    window.addEventListener("keydown", act);
    return () => {
      window.removeEventListener("resize", on);
      window.removeEventListener("pointerdown", act);
      window.removeEventListener("keydown", act);
    };
  }, []);

  if (gate === "boot") return <div className="screen"><div className="card">Opening vault…</div></div>;
  if (gate === "setup") return <Setup onDone={() => setGate("app")} setErr={setErr} err={err} />;
  if (gate === "lock" || rt.locked) return <Lock onDone={() => setGate("app")} setErr={setErr} err={err} />;

  const snap = rt.snap!;
  const threads = Object.values(snap.threads)
    .filter((t) => (tab === "chambers" ? t.kind === "chamber" : t.kind !== "chamber" && !t.archived))
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.lastAt - a.lastAt);
  const thread = threadId ? snap.threads[threadId] : undefined;
  const showRail = !(narrow && thread);
  const showThread = !(narrow && !thread);

  return (
    <div className="app" onPointerDown={() => rt.touch()}>
      <header className="topbar">
        <div className="wordmark">Bleep <span>· personal</span></div>
        <div className={"chip" + (rt.regionOk ? "" : " warn")}>{snap.settings.region.replace("-", " · ")}</div>
        <div className="grow" />
        <span className="note">profile {profileName()}</span>
        <button className="ghost" onClick={() => rt.lockNow()}>Lock</button>
      </header>
      {rt.notice?.kind === "region" && (
        <div className="empty warn">{rt.notice.text} Nothing is being sent.</div>
      )}
      <div className="shell">
        <aside className={"rail" + (showRail ? "" : " hide-m")}>
          <nav className="tabs">
            {(["chats", "requests", "status", "chambers", "settings"] as Tab[]).map((t) => (
              <button key={t} className={tab === t ? "on" : ""} onClick={() => { setTab(t); setThreadId(null); }}>
                {t}{t === "requests" && snap.requests.length ? ` (${snap.requests.length})` : ""}
              </button>
            ))}
          </nav>
          {tab === "chats" || tab === "chambers" ? (
            <>
              <div style={{ padding: 10, display: "flex", gap: 8 }}>
                <button className="ghost" style={{ flex: 1 }} onClick={() => setAddOpen(true)}>Add by QR / code</button>
                {tab === "chats" && (
                  <button className="ghost" onClick={() => setGroupOpen(true)}>Group</button>
                )}
              </div>
              <div className="list">
                {threads.length === 0 && (
                  <div className="empty">
                    {tab === "chambers"
                      ? "No Chambers. A Chamber is a fresh ID, its own lock, and a burn date — not a label."
                      : "No threads yet. Add someone by Bleep code. First contact is a request, not a hot inbox."}
                  </div>
                )}
                {threads.map((t) => (
                  <div key={t.id} className={"row" + (threadId === t.id ? " on" : "")} onClick={() => setThreadId(t.id)}>
                    <div className={"avatar" + (t.kind === "chamber" ? " ember" : "")}>
                      {(t.title[0] || "?").toUpperCase()}
                    </div>
                    <div className="meta">
                      <div className="name">{t.title}</div>
                      <div className="preview">{t.lockedStub ? "Chamber active · locked" : t.lastPreview || " "}</div>
                    </div>
                    <div>
                      <div className="when">{fmtTime(t.lastAt)}</div>
                      {t.unread > 0 && <div className="badge">{t.unread}</div>}
                    </div>
                  </div>
                ))}
              </div>
            </>
          ) : tab === "requests" ? (
            <Requests />
          ) : tab === "status" ? (
            <StatusPane />
          ) : (
            <SettingsPane />
          )}
        </aside>
        {showThread && (
          <main className={"thread" + (showThread ? "" : " hide-m")}>
            {thread ? (
              <ThreadView
                thread={thread}
                onBack={() => setThreadId(null)}
                onGuardian={(h, text, files, viewOnce) => setHits({ hits: h, text, files, thread, viewOnce })}
              />
            ) : (
              <MeCard />
            )}
          </main>
        )}
      </div>
      {addOpen && <AddPerson onClose={() => setAddOpen(false)} />}
      {groupOpen && <NewGroup onClose={() => setGroupOpen(false)} />}
      {rt.call.phase !== "idle" && <CallOverlay />}
      {hits && (
        <GuardianSheet
          hits={hits.hits}
          peerName={hits.thread.title}
          verified={Boolean(hits.thread.peerMailboxId && snap.peers[hits.thread.peerMailboxId]?.verified)}
          onCancel={() => setHits(null)}
          onRemove={() => setHits(null)}
          onSendAnyway={async (strip) => {
            await rt.commitSend(hits.thread, hits.text, hits.files, strip, hits.viewOnce);
            setHits(null);
          }}
        />
      )}
    </div>
  );
}

function Setup({ onDone, err, setErr }: { onDone: () => void; err: string; setErr: (s: string) => void }) {
  const [name, setName] = useState("");
  const [pin, setPin] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <div className="screen">
      <form
        className="card"
        onSubmit={async (e) => {
          e.preventDefault();
          setErr("");
          setBusy(true);
          try {
            await rt.createAccount(name, pin);
            onDone();
          } catch (ex) {
            setErr((ex as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="chip">JHB · ZA</div>
        <h1>Create a Bleep ID</h1>
        <p className="lead">
          A device-generated keypair. No phone number, no email, no OTP. The vault is this browser —
          an invite client, not the Android APK, not an audit.
        </p>
        <label>Display name (stays on this device until you send it)</label>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Lebo" required />
        <label>Bleep lock PIN</label>
        <input type="password" value={pin} onChange={(e) => setPin(e.target.value)} minLength={4} required />
        {err && <p className="warn">{err}</p>}
        <button className="primary" disabled={busy}>{busy ? "Sealing…" : "Create ID"}</button>
      </form>
    </div>
  );
}

function Lock({ onDone, err, setErr }: { onDone: () => void; err: string; setErr: (s: string) => void }) {
  const [pin, setPin] = useState("");
  return (
    <div className="screen">
      <form
        className="card"
        onSubmit={async (e) => {
          e.preventDefault();
          setErr("");
          try {
            await rt.unlock(pin);
            onDone();
          } catch (ex) {
            setErr("Wrong PIN or damaged vault.");
          }
        }}
      >
        <div className="chip">JHB · ZA</div>
        <h1>Bleep is locked</h1>
        <p className="lead">Short auto-lock. A stolen phone with the screen off should hit this, not your threads.</p>
        <label>PIN</label>
        <input type="password" value={pin} onChange={(e) => setPin(e.target.value)} autoFocus />
        {err && <p className="warn">{err}</p>}
        <button className="primary">Unlock</button>
      </form>
    </div>
  );
}

function MeCard() {
  const id = rt.identity()!;
  const code = bleepCode(id);
  const [qr, setQr] = useState("");
  useEffect(() => {
    void QRCode.toDataURL(code, { margin: 1, width: 220 }).then(setQr);
  }, [code]);
  return (
    <div className="screen">
      <div className="card">
        <h1>{id.displayName}</h1>
        <p className="lead">Add a person by showing this QR or pasting the code. It opens a request, not a hot inbox.</p>
        {qr && <div className="qrbox"><img src={qr} width={220} height={220} alt="Bleep QR" /></div>}
        <div className="code">{code}</div>
        <p className="note" style={{ marginTop: 12 }}>
          Two people on one machine: open <code>?p=alice</code> and <code>?p=bob</code> in two windows.
        </p>
      </div>
    </div>
  );
}

type FileBits = { name: string; mime: string; bytes: Uint8Array };

function ThreadView({
  thread,
  onBack,
  onGuardian,
}: {
  thread: Thread;
  onBack: () => void;
  onGuardian: (h: Hit[], text: string, files: FileBits[], viewOnce: boolean) => void;
}) {
  const snap = rt.snap!;
  const peer = thread.peerMailboxId ? snap.peers[thread.peerMailboxId] : undefined;
  const msgs = snap.messages[thread.id] ?? [];
  const [text, setText] = useState(thread.draft);
  const [viewOnce, setViewOnce] = useState(false);
  const [rec, setRec] = useState<MediaRecorder | null>(null);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    end.current?.scrollIntoView();
    thread.unread = 0;
    rt.bump();
  }, [msgs.length, thread]);

  const send = async (files: FileBits[] = []) => {
    try {
      const hits = await rt.sendText(thread, text, files, viewOnce);
      if (hits && hits.length) onGuardian(hits, text, files, viewOnce);
      else setText("");
    } catch (e) {
      alert((e as Error).message);
    }
  };

  return (
    <>
      <div className="thead">
        <button className="ghost" onClick={onBack}>Back</button>
        <div className={"avatar" + (thread.kind === "chamber" ? " ember" : "")}>{(thread.title[0] || "?").toUpperCase()}</div>
        <div className="grow">
          <h2>{thread.title}</h2>
          <div className="sub">
            {peer ? (peer.verified ? "verified · " : "unverified · ") : ""}
            {thread.peerTypingUntil && thread.peerTypingUntil > Date.now() ? "typing · " : ""}
            {thread.disappearingSec ? `disappear ${thread.disappearingSec / 3600}h` : "keep on device"}
            {thread.receipts ? " · receipts on" : " · receipts off"}
          </div>
        </div>
        {peer && thread.kind !== "chamber" && (
          <>
            <button className="ghost" onClick={() => void rt.startCall(peer, "audio")}>Call</button>
            <button className="ghost" onClick={() => void rt.startCall(peer, "video")}>Video</button>
            <SafetyMenu thread={thread} peer={peer} />
          </>
        )}
        {thread.kind === "chamber" && thread.chamberId && (
          <>
            <button className="ghost" onClick={() => rt.lockChamber(thread.chamberId!)}>Lock</button>
            <button className="ghost" onClick={() => rt.burnChamber(thread.chamberId!)}>Burn</button>
          </>
        )}
      </div>
      {peer?.safetyChanged && (
        <div className="empty warn" style={{ padding: 10 }}>
          Safety number changed. This is a new key. Check with them before you send — the composer is blocked until you mark verified.
        </div>
      )}
      {peer && !peer.verified && !peer.safetyChanged && (
        <div className="empty" style={{ padding: 10 }}>
          Safety number not verified. A key-change will block, not toast.
        </div>
      )}
      {thread.lockedStub ? (
        <ChamberUnlock id={thread.chamberId || ""} />
      ) : (
      <div className="msgs">
        {msgs.map((m) => (
          <Bubble
            key={m.id}
            msg={m}
            mine={m.fromMe}
            onReact={(e) => rt.react(thread, m, e)}
            onDel={() => rt.deleteForMe(m)}
            onDelAll={() => void rt.deleteForEveryone(thread, m)}
            onOpenHeld={() => {
              if (m.viewOnce) void rt.consumeViewOnce(m);
              else void rt.openHeld(m);
            }}
            onEdit={() => {
              const next = window.prompt("Edit (signed follow-up, not a relay history)", m.text);
              if (next != null) void rt.editMessage(thread, m, next);
            }}
          />
        ))}
        <div ref={end} />
      </div>
      )}
      {thread.lockedStub ? null : <div className="composer">
        <label className="iconbtn" title="Photo or file">
          +
          <input
            type="file"
            className="hidden"
            accept="image/*,audio/*,.pdf,.apk,application/*"
            onChange={async (e) => {
              const f = e.target.files?.[0];
              if (!f) return;
              const bytes = new Uint8Array(await f.arrayBuffer());
              await send([{ name: f.name, mime: f.type || "application/octet-stream", bytes }]);
              e.target.value = "";
            }}
          />
        </label>
        <button
          className="iconbtn"
          title="Voice note"
          onMouseDown={async () => {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            const mr = new MediaRecorder(stream);
            const chunks: Blob[] = [];
            mr.ondataavailable = (ev) => chunks.push(ev.data);
            mr.onstop = async () => {
              stream.getTracks().forEach((t) => t.stop());
              const blob = new Blob(chunks, { type: mr.mimeType || "audio/webm" });
              const bytes = new Uint8Array(await blob.arrayBuffer());
              await send([{ name: "voice.webm", mime: blob.type, bytes }]);
              setRec(null);
            };
            mr.start();
            setRec(mr);
          }}
          onMouseUp={() => rec?.stop()}
        >
          {rec ? "■" : "●"}
        </button>
        <label className="note" title="Open once, then drop. Not screenshot-proof.">
          <input type="checkbox" checked={viewOnce} onChange={(e) => setViewOnce(e.target.checked)} /> once
        </label>
        <textarea
          rows={1}
          placeholder="Message — Guardian runs before seal"
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            rt.sendTyping(thread);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void send();
            }
          }}
        />
        <button className="iconbtn primary" onClick={() => void send()}>Send</button>
      </div>}
    </>
  );
}

function ChamberUnlock({ id }: { id: string }) {
  const [pin, setPin] = useState("");
  return (
    <div className="screen">
      <div className="card">
        <h1>Chamber locked</h1>
        <p className="lead">Main Bleep being unlocked does not open this vault. Enter the Chamber PIN.</p>
        <input type="password" value={pin} onChange={(e) => setPin(e.target.value)} placeholder="Chamber PIN" />
        <button className="primary" onClick={() => rt.unlockChamber(id, pin.length >= 4)}>Unlock Chamber</button>
      </div>
    </div>
  );
}

function CallOverlay() {
  const c = rt.call;
  const audio = useRef<HTMLAudioElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    const el = c.media === "video" ? video.current : audio.current;
    if (el && c.remoteStream) el.srcObject = c.remoteStream;
  }, [c.remoteStream, c.media]);
  const name = c.peer?.displayName || "peer";
  return (
    <div className="sheet">
      <div className="panel call-panel">
        <h3>{c.phase === "active" ? "In call" : c.phase === "ringing-in" ? "Incoming call" : "Calling…"}</h3>
        <p className="reason">{name} · {c.media} · signalling is sealed. TURN never sees a name.</p>
        {c.media === "audio" && <audio ref={audio} autoPlay />}
        {c.media === "video" && <video ref={video} autoPlay playsInline style={{ width: "100%", borderRadius: 8, background: "#000" }} />}
        <div className="actions">
          {c.phase === "ringing-in" && <button className="go" onClick={() => void rt.acceptCall()}>Answer</button>}
          <button className="danger" onClick={() => void rt.hangup()}>
            {c.phase === "ringing-in" ? "Decline" : "Hang up"}
          </button>
        </div>
      </div>
    </div>
  );
}

function Bubble({
  msg, mine, onReact, onDel, onDelAll, onOpenHeld, onEdit,
}: {
  msg: Message;
  mine: boolean;
  onReact: (e: string) => void;
  onDel: () => void;
  onDelAll: () => void;
  onOpenHeld: () => void;
  onEdit: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!msg.attachmentId || msg.deleted || msg.needsDownload) return;
    void rt.vault.getAttachment(msg.attachmentId).then((att) => {
      if (!att) return;
      setUrl(URL.createObjectURL(new Blob([att.bytes.slice().buffer], { type: att.mime })));
    });
  }, [msg.attachmentId, msg.deleted]);
  if (msg.kind === "system") return <div className="bubble sys">{msg.text}</div>;
  return (
    <div className={"bubble" + (mine ? " me" : "")} onContextMenu={(e) => { e.preventDefault(); onDel(); }}>
      {msg.deleted ? <div className="t" style={{ color: "var(--faint)" }}>Deleted on this device</div> : (
        <>
          {msg.needsDownload && (
            <button className="ghost" onClick={onOpenHeld}>Tap to open — unknown sender or Low Data. Public Bleep did not scan this in the cloud.</button>
          )}
          {!msg.needsDownload && msg.kind === "photo" && url && <img className="media" src={url} alt="" />}
          {!msg.needsDownload && msg.kind === "voice" && url && <audio controls src={url} style={{ width: "100%" }} />}
          {!msg.needsDownload && msg.kind === "file" && <div className="t">File: {msg.name}</div>}
          {msg.kind === "call" && <div className="t">☎ {msg.text}</div>}
          {msg.text && msg.kind !== "call" && (
            <div className="t" onDoubleClick={mine ? onEdit : undefined}>
              {msg.viewOnce && !msg.viewed && !mine ? "View once — tap to open. Capture is still possible." : msg.text}
              {msg.edited ? " (edited)" : ""}
            </div>
          )}
        </>
      )}
      <div className="at">
        {fmtTime(msg.at)} {msg.reacted ?? ""} {msg.status === "queued" ? "queued" : msg.status === "read" ? "read" : msg.status === "sent" ? "sent" : ""}
        <button className="ghost" style={{ marginLeft: 8, padding: "0 6px" }} onClick={() => onReact("👍")}>👍</button>
        {mine && (
          <button className="ghost" style={{ marginLeft: 4, padding: "0 6px" }} onClick={onDelAll} title="Delete for everyone — they may honour it. Not screenshot-proof.">
            unsay
          </button>
        )}
      </div>
    </div>
  );
}

function SafetyMenu({ thread, peer }: { thread: Thread; peer: Peer }) {
  const [open, setOpen] = useState(false);
  const snap = rt.snap!;
  const num = useMemo(() => pairSafety(snap.identity, peer), [snap.identity, peer]);
  return (
    <div>
      <button className="ghost" onClick={() => setOpen((v) => !v)}>Safety</button>
      {open && (
        <div className="card" style={{ position: "absolute", right: 16, top: 96, zIndex: 5, width: 360 }}>
          <h3>Safety number</h3>
          <p className="safety">{num}</p>
          <p className="note">Compare this in person or by a second channel. A change is a new key.</p>
          <div className="actions">
            <button onClick={() => { rt.verifyPeer(peer); setOpen(false); }}>Mark verified</button>
            <button onClick={() => { thread.disappearingSec = thread.disappearingSec ? null : 86400; rt.bump(); }}>
              {thread.disappearingSec ? "Keep history" : "Disappear 24h"}
            </button>
            <button onClick={() => { thread.receipts = !thread.receipts; rt.bump(); }}>
              {thread.receipts ? "Receipts off" : "Receipts on"}
            </button>
            <button onClick={() => { thread.typing = !thread.typing; rt.bump(); }}>
              {thread.typing ? "Typing off" : "Typing on"}
            </button>
          </div>
          <div className="actions">
            <button onClick={() => rt.pinThread(thread)}>{thread.pinned ? "Unpin" : "Pin"}</button>
            <button onClick={() => rt.muteThread(thread)}>{thread.muted ? "Unmute" : "Mute"}</button>
            <button onClick={() => rt.archiveThread(thread)}>{thread.archived ? "Unarchive" : "Archive"}</button>
          </div>
          <div className="actions">
            <button onClick={() => void rt.startChamber(peer)}>Start a Chamber…</button>
            <button className="danger" onClick={() => rt.block(peer)}>Block</button>
          </div>
        </div>
      )}
    </div>
  );
}

function Requests() {
  const snap = rt.snap!;
  if (snap.requests.length === 0) return <div className="empty">No requests. Unknown Bleep codes cannot dump a thread into your inbox.</div>;
  const grouped = new Map<string, typeof snap.requests>();
  for (const r of snap.requests) {
    const k = r.fromMailbox || "?";
    grouped.set(k, [...(grouped.get(k) ?? []), r]);
  }
  return (
    <div className="list">
      {[...grouped.entries()].map(([mid, msgs]) => {
        const peer = snap.peers[mid];
        return (
          <div key={mid} className="row" style={{ gridTemplateColumns: "40px 1fr" }}>
            <div className="avatar">{(peer?.displayName[0] || "?").toUpperCase()}</div>
            <div>
              <div className="name">{peer?.displayName || mid.slice(0, 8)}</div>
              <div className="preview">
                {msgs.some((m) => m.chamberInvite)
                  ? "Chamber invite — both of you must accept. Screenshots are not ours to delete."
                  : "Request to message — accept before a thread opens."}
              </div>
              <div className="actions">
                <button onClick={() => rt.declineRequest(mid)}>Decline</button>
                {msgs.some((m) => m.chamberInvite) ? (
                  <button className="go" onClick={() => rt.acceptChamber(mid, msgs.find((m) => m.burnAt)?.burnAt)}>
                    Accept Chamber
                  </button>
                ) : msgs.some((m) => m.groupId) ? (
                  <button
                    className="go"
                    onClick={() => {
                      const g = msgs.find((m) => m.groupId)!;
                      rt.acceptGroup(mid, g.groupId!, g.groupName || "Group");
                    }}
                  >
                    Join group
                  </button>
                ) : (
                  <button className="go" onClick={() => rt.acceptRequest(mid)}>Accept</button>
                )}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function StatusPane() {
  const [text, setText] = useState("");
  const snap = rt.snap!;
  const live = snap.statuses.filter((s) => s.until > Date.now());
  return (
    <div className="settings">
      <section>
        <h3>Your status</h3>
        <p className="note">Sealed fan-out to accepted peers. 24h TTL. Chambers do not publish Status. No viewer list on the relay.</p>
        <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} style={{ width: "100%", background: "var(--ink)", border: "1px solid var(--rule)", borderRadius: 8, padding: 8 }} />
        <button className="primary" onClick={() => { void rt.publishStatus(text); setText(""); }}>Share for 24h</button>
      </section>
      {live.map((s) => (
        <section key={s.id}>
          <div className="name">{s.fromName}</div>
          <div>{s.text}</div>
          <div className="note">until {new Date(s.until).toLocaleString()}</div>
        </section>
      ))}
    </div>
  );
}

function SettingsPane() {
  const snap = rt.snap!;
  const s = snap.settings;
  const [pass, setPass] = useState("");
  const [handle, setHandle] = useState(s.handle ?? "");
  const [impPass, setImpPass] = useState("");
  const [impPin, setImpPin] = useState("");
  const [impErr, setImpErr] = useState("");
  return (
    <div className="settings">
      <section>
        <h3>This device</h3>
        <p>{snap.identity.displayName}</p>
        <p className="note">Mailbox {snap.identity.mailboxId}</p>
      </section>
      <section>
        <h3>Optional handle</h3>
        <p className="note">Opt-in publish. No global people search. Rate-limited resolve.</p>
        <input value={handle} onChange={(e) => setHandle(e.target.value)} placeholder="lebo" />
        <button className="primary" onClick={() => void rt.publishHandle(handle)}>Publish handle</button>
      </section>
      <section>
        <h3>Guardian</h3>
        <p className="note">On-device. Off / warn / strict. Default warn. Hits are not a dossier.</p>
        <div className="toggle">
          <span>Mode</span>
          <select
            value={s.guardianMode}
            onChange={(e) => { s.guardianMode = e.target.value as typeof s.guardianMode; rt.bump(); }}
            style={{ background: "var(--ink)", color: "var(--text)", border: "1px solid var(--rule)", padding: 6 }}
          >
            <option value="off">off</option>
            <option value="warn">warn</option>
            <option value="strict">strict</option>
          </select>
        </div>
      </section>
      <section>
        <h3>Lock & data</h3>
        <div className="toggle">
          <span>Auto-lock (seconds)</span>
          <input type="number" value={s.autoLockSec} onChange={(e) => { s.autoLockSec = Number(e.target.value); rt.bump(); }} style={{ width: 80 }} />
        </div>
        <div className="toggle">
          <span>Low Data</span>
          <input type="checkbox" checked={s.lowData} onChange={(e) => { s.lowData = e.target.checked; rt.bump(); }} />
        </div>
        <p className="note">Low Data keeps auto-download off on mobile-shaped viewports. Proud, not hidden.</p>
      </section>
      <section>
        <h3>Export</h3>
        <p className="note">Forced passphrase. Chambers omitted unless unlocked and selected. No auto cloud backup.</p>
        <input type="password" placeholder="passphrase 8+" value={pass} onChange={(e) => setPass(e.target.value)} />
        <button
          className="primary"
          onClick={async () => {
            const blob = await rt.vault.exportBlob(pass, snap);
            const a = document.createElement("a");
            a.href = URL.createObjectURL(blob);
            a.download = "bleep-export.json";
            a.click();
          }}
        >
          Download encrypted export
        </button>
        <p className="note" style={{ marginTop: 12 }}>Restore on this profile (Loop 3). Chambers ride along only if they were in the file.</p>
        <input type="password" placeholder="export passphrase" value={impPass} onChange={(e) => setImpPass(e.target.value)} />
        <input type="password" placeholder="new Bleep lock PIN" value={impPin} onChange={(e) => setImpPin(e.target.value)} />
        <label className="ghost" style={{ display: "inline-block", marginTop: 8 }}>
          Choose export file
          <input
            type="file"
            accept="application/json"
            className="hidden"
            onChange={async (e) => {
              const f = e.target.files?.[0];
              if (!f) return;
              setImpErr("");
              try {
                await rt.restoreFromExport(await f.text(), impPass, impPin);
              } catch (err) {
                setImpErr((err as Error).message);
              }
            }}
          />
        </label>
        {impErr && <p className="warn">{impErr}</p>}
      </section>
      <section>
        <h3>Honesty</h3>
        <p className="note">
          We do not claim screenshot-proof, military-grade, or above-the-law.
          A compelled operator of this relay yields sealed blobs and coarse connect bands — not a transcript.
        </p>
      </section>
      <section>
        <h3>Danger</h3>
        <button className="ghost" onClick={() => void rt.remoteUnlink()}>Remote unlink this mailbox</button>
        <button className="ghost" onClick={() => void rt.eraseDevice()}>Erase this device</button>
      </section>
    </div>
  );
}

function NewGroup({ onClose }: { onClose: () => void }) {
  const snap = rt.snap!;
  const accepted = Object.values(snap.peers).filter((p) => p.accepted && !p.blocked);
  const [name, setName] = useState("");
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [err, setErr] = useState("");
  return (
    <div className="sheet" onClick={onClose}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h3>Small group</h3>
        <p className="reason">v1 cap stays small. Join is explicit — nobody is silent-added. Relays still do not store a member list.</p>
        <label>Name</label>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Family" />
        {accepted.length === 0 && <p className="note">Accept someone first, then invite them.</p>}
        {accepted.map((p) => (
          <label key={p.mailboxId} className="toggle">
            <span>{p.displayName}</span>
            <input
              type="checkbox"
              checked={Boolean(picked[p.mailboxId])}
              onChange={(e) => setPicked({ ...picked, [p.mailboxId]: e.target.checked })}
            />
          </label>
        ))}
        {err && <p className="warn">{err}</p>}
        <div className="actions">
          <button onClick={onClose}>Cancel</button>
          <button
            className="go"
            onClick={async () => {
              try {
                const ids = Object.keys(picked).filter((k) => picked[k]);
                if (!name.trim() || !ids.length) throw new Error("Name and at least one member.");
                await rt.createGroup(name.trim(), ids);
                onClose();
              } catch (e) {
                setErr((e as Error).message);
              }
            }}
          >
            Send invites
          </button>
        </div>
      </div>
    </div>
  );
}

function AddPerson({ onClose }: { onClose: () => void }) {
  const [code, setCode] = useState("");
  const [hello, setHello] = useState("Sawubona — request to message.");
  const [err, setErr] = useState("");
  const video = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    let stream: MediaStream | undefined;
    let timer: number;
    void (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
        if (video.current) {
          video.current.srcObject = stream;
          await video.current.play();
        }
        const scan = () => {
          const v = video.current;
          if (!v || v.readyState < 2) return;
          const c = document.createElement("canvas");
          c.width = v.videoWidth;
          c.height = v.videoHeight;
          const ctx = c.getContext("2d");
          if (!ctx) return;
          ctx.drawImage(v, 0, 0);
          const img = ctx.getImageData(0, 0, c.width, c.height);
          const qr = jsQR(img.data, img.width, img.height);
          if (qr) setCode(qr.data);
        };
        timer = window.setInterval(scan, 600);
      } catch {
        /* camera optional */
      }
    })();
    return () => {
      stream?.getTracks().forEach((t) => t.stop());
      if (timer) clearInterval(timer);
    };
  }, []);
  return (
    <div className="sheet" onClick={onClose}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h3>Add by QR or Bleep code</h3>
        <p className="reason">No address-book upload. Paste a code, scan a QR, or resolve an optional handle as @name.</p>
        <video ref={video} style={{ width: "100%", borderRadius: 8, background: "#000" }} muted playsInline />
        <label>Code</label>
        <textarea value={code} onChange={(e) => setCode(e.target.value)} rows={3} style={{ width: "100%", background: "var(--ink)", border: "1px solid var(--rule)", borderRadius: 8, padding: 8 }} />
        <label>First message (they will see a request)</label>
        <input value={hello} onChange={(e) => setHello(e.target.value)} />
        {err && <p className="warn">{err}</p>}
        <div className="actions">
          <button onClick={onClose}>Cancel</button>
          <button
            className="go"
            onClick={async () => {
              try {
                const peer = await rt.addByCode(code.trim());
                await rt.sendRequest(peer, hello);
                onClose();
              } catch (e) {
                setErr((e as Error).message);
              }
            }}
          >
            Send request
          </button>
        </div>
      </div>
    </div>
  );
}

function GuardianSheet({
  hits,
  peerName,
  verified,
  onCancel,
  onRemove,
  onSendAnyway,
}: {
  hits: Hit[];
  peerName: string;
  verified: boolean;
  onCancel: () => void;
  onRemove: () => void;
  onSendAnyway: (strip: boolean) => void;
}) {
  const two = hits.some((h) => h.otpTwoTap);
  const [armed, setArmed] = useState(!two);
  const canStrip = hits.some((h) => h.strip === "exif");
  return (
    <div className="sheet">
      <div className="panel">
        <h3>Guardian</h3>
        <p className="note">{peerName} · safety number {verified ? "verified" : "unverified"}</p>
        {hits.map((h, i) => (
          <p className="reason" key={i}>{h.reason}</p>
        ))}
        <p className="note">Hits are not stored. Public Bleep never sees this sheet.</p>
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button onClick={onRemove}>{canStrip ? "Strip location / remove file" : "Remove file"}</button>
          <button
            className="go"
            onClick={() => {
              if (!armed) {
                setArmed(true);
                return;
              }
              onSendAnyway(canStrip);
            }}
          >
            {armed ? "Send anyway" : "Tap again to send anyway"}
          </button>
        </div>
      </div>
    </div>
  );
}

function fmtTime(t: number): string {
  const d = new Date(t);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}
