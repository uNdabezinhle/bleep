import { useEffect, useMemo, useRef, useState } from "react";
import QRCode from "qrcode";
import jsQR from "jsqr";
import { api } from "../net/api";
import { rt } from "./instance";
import { disappearAction, disappearPhrase, muteAction, replySnippet } from "../chat/prefs";
import { bleepCode, formatRecovery, formatShortId, freshRecoverySeed } from "../protocol/keys";
import { pairSafety } from "../session/session";
import { navigate } from "../site/router";
import { profileName } from "../store/vault";
import { GuardianReport } from "./Guardian";
import { SlideTabs } from "./SlideTabs";
import type { Hit } from "../guardian/engine";
import type { Message, Peer, Thread } from "../types";

type Tab = "chats" | "requests" | "status" | "guardian" | "you";
const YOU_SLIDES = ["Profile", "Account", "Lock", "Export", "About", "Danger"] as const;
type Gate = "boot" | "setup" | "lock" | "app";

function Icon({ d }: { d: string | readonly string[] }) {
  const paths = Array.isArray(d) ? d : [d];
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      {paths.map((p, i) => (
        <path key={i} d={p} strokeLinecap="round" strokeLinejoin="round" />
      ))}
    </svg>
  );
}

const ICON = {
  back: "M15 6l-6 6 6 6",
  clip: "M21.4 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48",
  mic: "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M19 11a7 7 0 0 1-14 0 M12 18v3",
  stop: "M7 7h10v10H7z",
  eye: "M2 12s4-6 10-6 10 6 10 6-4 6-10 6S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
  send: "M22 2L11 13 M22 2l-7 20-4-9-9-4 20-7",
  check: "M5 12l5 5L20 7",
  checks: ["M2 12l5 5L17 7", "M7 12l5 5L22 7"],
  clock: "M12 8v5l3 2 M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z",
  alert: "M12 8v5 M12 16h.01 M12 3l9 16H3z",
  trash: "M4 7h16 M9 7V5h6v2 M7 7l1 13h8l1-13",
  phone: "M6 4h4l2 4-3 2a12 12 0 0 0 5 5l2-3 4 2v4a2 2 0 0 1-2 2A16 16 0 0 1 4 6a2 2 0 0 1 2-2z",
  video: "M4 7h10v10H4z M14 10l6-3v10l-6-3z",
} as const;

function inList(t: Thread, mode: "chats" | "chambers" | "groups" | "channels"): boolean {
  if (mode === "chambers") return t.kind === "chamber";
  if (mode === "groups") return t.kind === "group" && !t.archived;
  return t.kind !== "chamber" && t.kind !== "group" && !t.archived;
}

function Face({ id, letter, ember }: { id?: string; letter: string; ember?: boolean }) {
  const [url, setUrl] = useState("");
  useEffect(() => {
    if (!id) return;
    let dead = false;
    let revoke = "";
    void rt.vault.getAttachment(id).then((att) => {
      if (!att || dead) return;
      revoke = URL.createObjectURL(new Blob([att.bytes.slice().buffer], { type: att.mime || "image/jpeg" }));
      setUrl(revoke);
    });
    return () => {
      dead = true;
      if (revoke) URL.revokeObjectURL(revoke);
    };
  }, [id]);
  return (
    <div className={"avatar" + (ember ? " ember" : "")}>
      {url ? <img src={url} alt="" /> : (letter[0] || "?").toUpperCase()}
    </div>
  );
}

async function readMedia(file: File, maxEdge: number): Promise<FileBits> {
  if (file.type.startsWith("image/")) {
    const bmp = await createImageBitmap(file);
    const scale = Math.min(1, maxEdge / Math.max(bmp.width, bmp.height));
    const w = Math.max(1, Math.round(bmp.width * scale));
    const h = Math.max(1, Math.round(bmp.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("Could not read that photo.");
    ctx.drawImage(bmp, 0, 0, w, h);
    bmp.close();
    const blob = await new Promise<Blob | null>((res) => canvas.toBlob(res, "image/jpeg", 0.82));
    if (!blob) throw new Error("Could not read that photo.");
    return { name: "photo.jpg", mime: "image/jpeg", bytes: new Uint8Array(await blob.arrayBuffer()) };
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (bytes.byteLength > 3_000_000) throw new Error("Keep files under 3 MB.");
  return { name: file.name || "file", mime: file.type || "application/octet-stream", bytes };
}

function Receipt({ status }: { status?: Message["status"] }) {
  if (status === "read") return <span className="tick read" role="img" aria-label="Read"><Icon d={ICON.checks} /></span>;
  if (status === "delivered") return <span className="tick" role="img" aria-label="Delivered"><Icon d={ICON.checks} /></span>;
  if (status === "sent") return <span className="tick" role="img" aria-label="Sent"><Icon d={ICON.check} /></span>;
  if (status === "queued") return <span className="tick" role="img" aria-label="Queued"><Icon d={ICON.clock} /></span>;
  if (status === "failed") return <span className="tick bad" role="img" aria-label="Not sent"><Icon d={ICON.alert} /></span>;
  return null;
}

function presenceLine(row: { online: boolean; lastSeen: string | null } | undefined): string {
  if (!row) return "";
  if (row.online) return "Online · ";
  if (!row.lastSeen) return "";
  return `Last seen ${new Date(row.lastSeen).toLocaleString()} · `;
}

export function App({ path = "/app" }: { path?: string }) {
  const [, setTick] = useState(0);
  const bump = () => setTick((x) => x + 1);
  const [gate, setGate] = useState<Gate>("boot");
  const [tab, setTab] = useState<Tab>("chats");
  const [listMode, setListMode] = useState<"chats" | "chambers" | "groups" | "channels">("chats");
  const [leaveId, setLeaveId] = useState<string | null>(null);
  const [threadId, setThreadId] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [find, setFind] = useState("");
  const [groupOpen, setGroupOpen] = useState(false);
  const [hits, setHits] = useState<{ hits: Hit[]; text: string; files: FileBits[]; thread: Thread; viewOnce: boolean } | null>(null);
  const [err, setErr] = useState("");
  const [youSlide, setYouSlide] = useState(0);
  const [narrow, setNarrow] = useState(() => window.innerWidth < 840);

  useEffect(() => {
    rt.onChange = bump;
    let dead = false;
    void rt.boot().then((next) => {
      if (dead) return;
      setGate((cur) => (cur === "app" ? cur : next));
    });
    const on = () => setNarrow(window.innerWidth < 840);
    window.addEventListener("resize", on);
    const act = () => rt.touch();
    window.addEventListener("pointerdown", act);
    window.addEventListener("keydown", act);
    return () => {
      dead = true;
      window.removeEventListener("resize", on);
      window.removeEventListener("pointerdown", act);
      window.removeEventListener("keydown", act);
    };
  }, []);

  const resetToken = new URLSearchParams(location.search).get("reset");
  const confirmToken = new URLSearchParams(location.search).get("confirm");
  if (path === "/register" || path === "/signin" || resetToken || confirmToken) {
    if (gate === "boot") return <div className="screen"><div className="card">Opening vault…</div></div>;
    return (
      <RegisterFlow
        hasVault={gate !== "setup"}
        startMode={resetToken ? "reset" : path === "/signin" ? "signin" : "create"}
        resetToken={resetToken}
        confirmToken={confirmToken}
        onDone={() => {
          const params = new URLSearchParams(location.search);
          params.delete("reset");
          params.delete("confirm");
          const q = params.toString();
          navigate(q ? `/app?${q}` : "/app");
          setGate("app");
        }}
      />
    );
  }
  if (gate === "boot") return <div className="screen"><div className="card">Opening vault…</div></div>;
  if (gate === "setup") return <Setup err={err} />;
  if (gate === "lock" || rt.locked) return <Lock onDone={() => setGate("app")} onErased={() => setGate("setup")} setErr={setErr} err={err} />;

  const snap = rt.snap!;
  const q = find.trim().toLowerCase();
  const threads = Object.values(snap.threads)
    .filter((t) => inList(t, listMode))
    .filter((t) => {
      if (!q || listMode === "chambers") return true;
      if (t.title.toLowerCase().includes(q)) return true;
      return (snap.messages[t.id] ?? []).some((m) => !m.deleted && m.text.toLowerCase().includes(q));
    })
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.lastAt - a.lastAt);
  const thread = threadId ? snap.threads[threadId] : undefined;
  const showList = tab === "chats" && !(narrow && thread);
  const showMain = !(narrow && tab === "chats" && !thread);
  const frame =
    "workspace" +
    (tab === "chats" ? "" : " no-list") +
    (narrow && tab === "chats" && thread ? " show-thread" : "") +
    (narrow && tab === "chats" && !thread ? " show-list" : "");

  return (
    <div className="app" onPointerDown={() => rt.touch()}>
      {rt.notice?.kind === "region" && (
        <div className="banner">{rt.notice.text} Nothing is being sent.</div>
      )}
      <div className={frame}>
        <nav className="dock">
          {([
            ["chats", "Chats", "M5 6h14v10H8l-3 3z"],
            ["requests", "Requests", "M4 6h16v12H4z M4 7l8 6 8-6"],
            ["status", "Status", "M12 6a6 6 0 1 0 0 12 6 6 0 0 0 0-12z"],
            ["guardian", "Guardian", ["M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", "M12 12l5-3"]],
            ["you", "You", "M12 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M6 19c1.5-3 10.5-3 12 0"],
          ] as const).map(([id, label, d]) => (
            <button key={id} className={tab === id ? "on" : ""} onClick={() => { setTab(id); if (id !== "chats") setThreadId(null); void rt.poll(); }}>
              <Icon d={d} />
              {label}
              {id === "requests" && snap.requests.length > 0 && <span className="count">{snap.requests.length}</span>}
            </button>
          ))}
        </nav>
        {showList && (
          <aside className="col-list">
            <header className="list-head">
              <div className="grow">
                <a className="wordmark" href="/" onClick={(e) => { e.preventDefault(); navigate("/"); }}>Bleep</a>
                <div className={rt.regionOk ? "region" : "region bad"}>{rt.regionOk ? "ZA-JHB" : snap.settings.region}</div>
              </div>
              <button className="iconbtn" aria-label="Lock" onClick={() => rt.lockNow()}>
                <Icon d="M8 11V8a4 4 0 0 1 8 0v3 M7 11h10v8H7z" />
              </button>
              <button className="iconbtn primary" aria-label="Add" onClick={() => setAddOpen(true)}>+</button>
            </header>
            <div className="seg" role="tablist" aria-label="Chats">
              <button className={listMode === "chats" ? "on" : ""} onClick={() => { setListMode("chats"); setThreadId(null); }}>Chats</button>
              <button className={listMode === "chambers" ? "on" : ""} onClick={() => { setListMode("chambers"); setThreadId(null); }}>Chambers</button>
              <button className={listMode === "groups" ? "on" : ""} onClick={() => { setListMode("groups"); setThreadId(null); }}>Groups</button>
              <button className={listMode === "channels" ? "on" : ""} onClick={() => { setListMode("channels"); setThreadId(null); }}>Channels</button>
            </div>
            {listMode !== "chambers" && listMode !== "channels" && (
              <input
                className="find"
                value={find}
                placeholder="Search this device"
                aria-label="Search this device"
                onChange={(e) => setFind(e.target.value)}
              />
            )}
            {listMode === "channels" ? <ChannelsPane /> : <div className="list">
              {threads.length === 0 && (
                <div className="empty">
                  {q
                    ? "No messages on this device match."
                    : listMode === "chambers"
                    ? "No Chambers. A Chamber is a fresh ID, its own lock, and a burn date."
                    : listMode === "groups"
                    ? "No groups. A group stays small, and each person joins on purpose."
                    : "No threads yet. Add someone by Bleep code. First contact is a request."}
                  {listMode === "groups" && !q && (
                    <div><button type="button" className="ghost" onClick={() => setGroupOpen(true)}>New group</button></div>
                  )}
                </div>
              )}
              {threads.map((t) => (
                <div key={t.id}>
                  <button className={"row" + (threadId === t.id ? " on" : "")} onClick={() => setThreadId(t.id)}>
                    <Face id={t.kind === "dm" ? snap.peers[t.peerMailboxId || ""]?.avatarId : undefined} letter={t.title} ember={t.kind === "chamber"} />
                    <div className="meta">
                      <div className="name">{t.title}</div>
                      <div className="preview">{t.lockedStub ? "Chamber active · locked" : t.lastPreview || " "}</div>
                    </div>
                    <div>
                      <div className="when">{fmtTime(t.lastAt)}</div>
                      {t.unread > 0 && <div className="badge">{t.unread}</div>}
                    </div>
                  </button>
                  {listMode === "groups" && t.groupId && (
                    <button type="button" className="ghost" onClick={() => setLeaveId(t.groupId || null)}>Leave group</button>
                  )}
                </div>
              ))}
            </div>}
          </aside>
        )}
        {showMain && (
          <main className="col-main">
            {tab === "chats" && (thread ? (
              <ThreadView
                thread={thread}
                onBack={() => setThreadId(null)}
                onGuardian={(h, text, files, viewOnce) => setHits({ hits: h, text, files, thread, viewOnce })}
              />
            ) : (
              <MeCard />
            ))}
            {tab === "requests" && (
              <div className="pane">
                <h1 className="pane-title">Requests</h1>
                <Requests onJoined={(id) => { setTab("chats"); setListMode("groups"); setThreadId(id); }} />
              </div>
            )}
            {tab === "status" && <div className="pane"><h1 className="pane-title">Status</h1><StatusPane /></div>}
            {tab === "guardian" && (
              <div className="pane you">
                <h1 className="pane-title">Guardian</h1>
                <GuardianReport
                  onChats={() => setTab("chats")}
                  onJump={(id) => {
                    const slide = id === "lock-timer" ? 2 : id === "erase-device" ? 5 : 1;
                    setYouSlide(slide);
                    setTab("you");
                    window.setTimeout(() => {
                      document.getElementById(id)?.scrollIntoView({ block: "start" });
                      if (id === "lock-timer") document.querySelector<HTMLSelectElement>("#lock-timer select")?.focus();
                    }, 320);
                  }}
                />
              </div>
            )}
            {tab === "you" && (
              <div className="pane you">
                <h1 className="pane-title">You</h1>
                <SettingsPane slide={youSlide} onSlide={setYouSlide} onErased={() => setGate("setup")} />
              </div>
            )}
          </main>
        )}
      </div>
      {addOpen && (
        <AddPerson onClose={() => setAddOpen(false)} onGroup={() => { setAddOpen(false); setGroupOpen(true); }} />
      )}
      {groupOpen && (
        <NewGroup
          onClose={() => setGroupOpen(false)}
          onCreated={(id) => { setTab("chats"); setListMode("groups"); setThreadId(id); }}
        />
      )}
      {leaveId && (
        <div className="sheet" onClick={() => setLeaveId(null)}>
          <div className="panel" onClick={(e) => e.stopPropagation()}>
            <h3>Leave group</h3>
            <p>Leave this group? People already in it keep their copy.</p>
            <div className="actions">
              <button
                className="danger"
                onClick={() => {
                  const id = leaveId;
                  void rt.leaveGroup(id).then(() => {
                    setLeaveId(null);
                    if (threadId === "g:" + id) setThreadId(null);
                  }).catch((e: Error) => alert(e.message));
                }}
              >
                Leave group
              </button>
              <button onClick={() => setLeaveId(null)}>Stay</button>
            </div>
          </div>
        </div>
      )}
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

const FEATURES: [string, string][] = [
  ["A key on this device", "The key is made on this device. Email is the login. The recovery code never goes to the relay."],
  ["Sealed envelopes", "Transport mail expires. A sealed copy of history stays for this account until unlink. The relay has no transcript and no sender column."],
  ["Requests, not an open inbox", "A new Bleep code arrives as a request. You accept before a thread opens."],
  ["Safety numbers", "Compare them in person. A key change blocks sending until you mark it verified."],
  ["Guardian on the device", "Warn or strict, before a message is sealed. Hits are not stored in the cloud."],
  ["Chambers", "A separate lock and a burn date. Unlocking Bleep does not open a Chamber."],
  ["Your region check", "The client only talks to a box that answers ZA-JHB. Anything else fails closed."],
  ["Android build", "The hash page lists the debug APK. It is not the Play Store app."],
];

function Features() {
  return (
    <section className="features" id="features" aria-labelledby="features-heading">
      <h2 id="features-heading">Features</h2>
      <ul>
        {FEATURES.map(([title, body]) => (
          <li key={title}>
            <strong>{title}</strong>
            <p>{body}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

function accountPath(next: "signin" | "create") {
  const params = new URLSearchParams(location.search);
  params.delete("reset");
  params.delete("confirm");
  const q = params.toString();
  navigate((next === "signin" ? "/signin" : "/register") + (q ? `?${q}` : ""));
}

function RegisterFlow({
  hasVault,
  startMode,
  resetToken,
  confirmToken,
  onDone,
}: {
  hasVault: boolean;
  startMode: "create" | "signin" | "reset";
  resetToken: string | null;
  confirmToken: string | null;
  onDone: () => void;
}) {
  const [mode, setMode] = useState<"create" | "signin" | "reset">(startMode);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [again, setAgain] = useState("");
  const [name, setName] = useState("");
  const [pin, setPin] = useState("");
  const [recovery, setRecovery] = useState("");
  const [seed, setSeed] = useState<Uint8Array | null>(null);
  const [kept, setKept] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (!confirmToken) return;
    void api.confirmAccount(confirmToken).then(() => {
      setNotice("Address confirmed. Sign in with the recovery code. The link did not contain it.");
      setMode("signin");
    }).catch((ex) => setErr((ex as Error).message));
  }, [confirmToken]);

  const startSeed = () => {
    setErr("");
    if (password.length < 8) return setErr("Password should be 8 characters or more.");
    if (password !== again) return setErr("Passwords do not match.");
    if (pin.length < 4) return setErr("PIN should be 4 characters or more.");
    if (!name.trim()) return setErr("Add a display name.");
    setSeed(freshRecoverySeed());
  };

  return (
    <div className="screen invite-screen">
      <div className="invite">
        <Features />
        <form
          className="card"
          onSubmit={async (e) => {
            e.preventDefault();
            setErr("");
            setNotice("");
            setBusy(true);
            try {
              if (mode === "reset") {
                if (!resetToken) throw new Error("Missing reset link.");
                await api.recoverComplete(resetToken, password);
                setNotice("Password updated. Sign in with the recovery code to open history.");
                setMode("signin");
                return;
              }
              if (mode === "signin") {
                if (hasVault) throw new Error("This browser profile already has a vault. Open Bleep, or use another ?p= profile.");
                await rt.signIn(email, password, recovery, pin);
                onDone();
                return;
              }
              if (!seed) {
                startSeed();
                return;
              }
              if (!kept) throw new Error("Confirm that you have kept the recovery code.");
              const detail = await rt.signUp(email, password, name, pin, seed);
              if (!rt.snap) {
                setNotice(detail);
                setMode("signin");
                return;
              }
              onDone();
            } catch (ex) {
              setErr((ex as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <div className="chip">ZA-JHB</div>
          <h1>{mode === "signin" ? "Sign in" : mode === "reset" ? "New password" : "Create an account"}</h1>
          {mode !== "reset" && (
            <>
              <label>Email</label>
              <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" required />
            </>
          )}
          <label>{mode === "reset" ? "New password" : "Password"}</label>
          <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" minLength={8} required />
          {mode === "create" && !seed && (
            <>
              <label>Password again</label>
              <input value={again} onChange={(e) => setAgain(e.target.value)} type="password" required />
              <label>Display name</label>
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Lebo" />
            </>
          )}
          {mode !== "reset" && (
            <>
              <label>Bleep lock PIN</label>
              <input value={pin} onChange={(e) => setPin(e.target.value)} type="password" minLength={4} required={mode === "signin" || !seed} />
            </>
          )}
          {mode === "signin" && (
            <>
              <label>Recovery code</label>
              <textarea value={recovery} onChange={(e) => setRecovery(e.target.value)} rows={2} required />
              <p className="note">The code opens the key and the sealed history. The password alone does not.</p>
            </>
          )}
          {seed && mode === "create" && (
            <>
              <p className="note">Write this down. It is shown once. A second device needs it. The relay never receives it.</p>
              <div className="code">{formatRecovery(seed)}</div>
              <label className="toggle">
                <input type="checkbox" checked={kept} onChange={(e) => setKept(e.target.checked)} />
                <span>I have kept this recovery code</span>
              </label>
            </>
          )}
          {err && <p className="warn">{err}</p>}
          {notice && <p className="note">{notice}</p>}
          <button className="primary" disabled={busy}>{busy ? "Working…" : mode === "signin" ? "Open vault" : mode === "reset" ? "Update password" : seed ? "Create account" : "Show recovery code"}</button>
          {mode === "create" && <button type="button" className="ghost" onClick={() => { setMode("signin"); accountPath("signin"); }}>Sign in</button>}
          {mode === "signin" && <button type="button" className="ghost" onClick={() => { setMode("create"); setSeed(null); accountPath("create"); }}>Create an account</button>}
          {mode !== "reset" && (
            <button
              type="button"
              className="ghost"
              onClick={async () => {
                setErr("");
                setNotice("");
                if (!email.trim()) {
                  setErr("Enter the email on the account.");
                  return;
                }
                try {
                  const res = (await api.recover(email.trim())) as { detail: string };
                  setNotice(res.detail);
                } catch (ex) {
                  setErr((ex as Error).message);
                }
              }}
            >
              Forgot password
            </button>
          )}
          {hasVault && mode !== "reset" && <p className="note">This browser profile already has a vault. Sign-in here needs an empty profile, such as /register?p=other.</p>}
        </form>
      </div>
    </div>
  );
}

function Setup({ err }: { err: string }) {
  return (
    <div className="screen invite-screen">
      <div className="invite">
        <Features />
        <div className="card">
          <div className="chip">JHB · ZA</div>
          <h1>Open Bleep</h1>
          <p className="lead">
            Email is the login. A new device also needs the recovery code. The password alone does not open the key.{" "}
            <a href="/hashes.html">Download Android APK</a>
          </p>
          {err && <p className="warn">{err}</p>}
          <a className="primary" href="/signin" onClick={(e) => { e.preventDefault(); navigate(`/signin${location.search}`); }}>Sign in</a>
          <p className="note"><a href="/register" onClick={(e) => { e.preventDefault(); navigate(`/register${location.search}`); }}>Create an account</a></p>
        </div>
      </div>
    </div>
  );
}

function Lock({
  onDone,
  onErased,
  err,
  setErr,
}: {
  onDone: () => void;
  onErased: () => void;
  err: string;
  setErr: (s: string) => void;
}) {
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
            const msg = (ex as Error).message || "";
            if (msg.includes("erased")) {
              setErr("This device was erased. Local files on this browser are gone.");
              onErased();
              return;
            }
            setErr("Wrong PIN or damaged vault.");
          }
        }}
      >
        <div className="chip">ZA-JHB</div>
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

function MeCard({ compact = false }: { compact?: boolean }) {
  const id = rt.identity()!;
  const code = bleepCode(id);
  const short = rt.snap?.settings.shortId ? formatShortId(rt.snap.settings.shortId) : "";
  const avatarId = rt.snap?.settings.avatarId;
  const [qr, setQr] = useState("");
  const [full, setFull] = useState(false);
  const [photoErr, setPhotoErr] = useState("");
  useEffect(() => {
    void QRCode.toDataURL(short || code, { margin: 1, width: 220 }).then(setQr);
  }, [short, code]);
  return (
    <div className={compact ? "mine" : "screen"}>
      <div className="card">
        <Face id={avatarId} letter={id.displayName} />
        <h1>{id.displayName}</h1>
        <label className="ghost" style={{ display: "inline-block" }}>
          Profile photo
          <input
            type="file"
            accept="image/*"
            className="hidden"
            onChange={async (e) => {
              const f = e.target.files?.[0];
              e.target.value = "";
              if (!f) return;
              setPhotoErr("");
              try {
                const photo = await readMedia(f, 512);
                await rt.setProfilePhoto(photo.bytes, photo.mime);
              } catch (err) {
                setPhotoErr((err as Error).message);
              }
            }}
          />
        </label>
        {avatarId && (
          <button type="button" className="ghost" onClick={() => void rt.clearProfilePhoto()}>Remove photo</button>
        )}
        {photoErr && <p className="warn">{photoErr}</p>}
        <p className="note">Accepted people receive this photo in a sealed envelope. There is no public photo.</p>
        <p className="lead">This Bleep ID is eight characters. A hyphen is optional. Someone can paste it, scan the QR, or add a published handle.</p>
        {qr && <div className="qrbox"><img src={qr} width={220} height={220} alt="Bleep QR" /></div>}
        <div className="code" style={{ fontSize: 28, letterSpacing: "0.08em" }}>{short || "…"}</div>
        <button type="button" className="ghost" onClick={() => setFull((v) => !v)}>{full ? "Hide long code" : "Long code"}</button>
        {full && <div className="code">{code}</div>}
        <p className="note" style={{ marginTop: 12 }}>
          Two people on one machine: open <code>/app?p=alice</code> and <code>/app?p=bob</code>.
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
  const [recOn, setRecOn] = useState(false);
  const [menu, setMenu] = useState(false);
  const [leaveAsk, setLeaveAsk] = useState(false);
  const [mediaOpen, setMediaOpen] = useState(false);
  const [nick, setNick] = useState(peer?.nickname ?? "");
  const recRef = useRef<MediaRecorder | null>(null);
  const holdRef = useRef(false);
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
      else {
        setText("");
        setViewOnce(false);
      }
    } catch (e) {
      alert((e as Error).message);
    }
  };

  return (
    <>
      <div className="thead">
        <button className="iconbtn" aria-label="Back" onClick={onBack}><Icon d={ICON.back} /></button>
        <Face id={thread.kind === "dm" ? peer?.avatarId : undefined} letter={thread.title} ember={thread.kind === "chamber"} />
        <div className="grow">
          <h2>{thread.title}</h2>
          <div className="sub">
            {peer ? (peer.verified ? "verified · " : "unverified · ") : ""}
            {peer && thread.kind === "dm" ? presenceLine(rt.presence[peer.mailboxId]) : ""}
            {thread.peerTypingUntil && thread.peerTypingUntil > Date.now() ? "typing · " : ""}
            {disappearPhrase(thread.disappearingSec)}
            {peer?.nickname ? ` · ${peer.displayName}` : ""}
            {" · The Android app blocks the system screenshot. This browser cannot. A photograph of the screen still works."}
            <span className={"tick" + (thread.receipts ? " read" : "")} role="img" aria-label={thread.receipts ? "Read receipts on" : "Read receipts off"}>
              <Icon d={thread.receipts ? ICON.checks : ICON.check} />
            </span>
          </div>
        </div>
        {peer && thread.kind === "dm" && (
          <>
            <button className="iconbtn" aria-label="Call" onClick={() => void rt.startCall(peer, "audio")}><Icon d={ICON.phone} /></button>
            <button className="iconbtn" aria-label="Video" onClick={() => void rt.startCall(peer, "video")}><Icon d={ICON.video} /></button>
          </>
        )}
        <button className="iconbtn" aria-label="Thread menu" onClick={() => setMenu(true)}>···</button>
      </div>
      {menu && (
        <div className="sheet" onClick={() => setMenu(false)}>
          <div className="panel" onClick={(e) => e.stopPropagation()}>
            <h3>{thread.title}</h3>
            <div className="actions">
              <button onClick={() => { setMenu(false); setMediaOpen(true); }}>Photos and files</button>
            </div>
            {peer && thread.kind !== "chamber" && (
              <SafetyMenu
                thread={thread}
                peer={peer}
                nick={nick}
                onNick={setNick}
                onDone={() => setMenu(false)}
              />
            )}
            {thread.kind === "group" && thread.groupId && (
              <div className="actions">
                {leaveAsk ? (
                  <>
                    <p className="hint">Leave this group? People already in it keep their copy.</p>
                    <button
                      className="danger"
                      onClick={() => {
                        void rt.leaveGroup(thread.groupId || "").then(() => {
                          setMenu(false);
                          onBack();
                        }).catch((e: Error) => alert(e.message));
                      }}
                    >
                      Leave group
                    </button>
                    <button onClick={() => setLeaveAsk(false)}>Stay</button>
                  </>
                ) : (
                  <button onClick={() => setLeaveAsk(true)}>Leave group</button>
                )}
              </div>
            )}
            {thread.kind === "chamber" && thread.chamberId && (
              <div className="actions">
                <button onClick={() => { rt.lockChamber(thread.chamberId!); setMenu(false); }}>Lock</button>
                <button className="danger" onClick={() => { rt.burnChamber(thread.chamberId!); setMenu(false); }}>Burn</button>
              </div>
            )}
          </div>
        </div>
      )}
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
            onReply={() => {
              if (m.deleted || m.kind === "system") return;
              thread.replyTo = m.id;
              thread.replyText = replySnippet(m.text, m.kind);
              rt.bump();
            }}
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
      {mediaOpen && <MediaGrid msgs={msgs} onClose={() => setMediaOpen(false)} />}
      {thread.lockedStub ? null : <div className="composer">
        {thread.replyText && (
          <div className="replybar">
            <span>{thread.replyText}</span>
            <button type="button" aria-label="Cancel reply" onClick={() => { thread.replyTo = undefined; thread.replyText = undefined; rt.bump(); }}>×</button>
          </div>
        )}
        <label className="iconbtn" title="Photo or file" aria-label="Photo or file">
          <Icon d={ICON.clip} />
          <input
            type="file"
            className="hidden"
            accept="image/*,video/*,audio/*,.pdf,.apk,application/*"
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
          aria-label={recOn ? "Stop recording" : "Hold to record a voice note"}
          title="Voice note. Hold to record."
          onPointerDown={async (e) => {
            if (recRef.current) return;
            holdRef.current = true;
            e.currentTarget.setPointerCapture(e.pointerId);
            let stream: MediaStream;
            try {
              stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            } catch {
              holdRef.current = false;
              return;
            }
            if (!holdRef.current) {
              stream.getTracks().forEach((t) => t.stop());
              return;
            }
            const mr = new MediaRecorder(stream);
            const chunks: Blob[] = [];
            mr.ondataavailable = (ev) => chunks.push(ev.data);
            mr.onstop = async () => {
              stream.getTracks().forEach((t) => t.stop());
              recRef.current = null;
              setRecOn(false);
              const blob = new Blob(chunks, { type: mr.mimeType || "audio/webm" });
              const bytes = new Uint8Array(await blob.arrayBuffer());
              await send([{ name: "voice.webm", mime: blob.type, bytes }]);
            };
            recRef.current = mr;
            setRecOn(true);
            mr.start();
          }}
          onPointerUp={() => {
            holdRef.current = false;
            recRef.current?.stop();
          }}
          onPointerCancel={() => {
            holdRef.current = false;
            recRef.current?.stop();
          }}
        >
          {recOn ? <Icon d={ICON.stop} /> : <Icon d={ICON.mic} />}
        </button>
        <button
          type="button"
          className={"iconbtn once" + (viewOnce ? " on" : "")}
          title="Open once, then drop. Not screenshot-proof."
          aria-label="Open once"
          aria-pressed={viewOnce}
          onClick={() => setViewOnce((v) => !v)}
        >
          <Icon d={ICON.eye} />
        </button>
        {thread.kind === "dm" && peer && <LiveLocation thread={thread} />}
        <textarea
          rows={1}
          placeholder="Message"
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
        <button className="iconbtn primary" aria-label="Send" onClick={() => void send()}><Icon d={ICON.send} /></button>
      </div>}
    </>
  );
}

function LiveLocation({ thread }: { thread: Thread }) {
  const [on, setOn] = useState(false);
  const [err, setErr] = useState("");
  useEffect(() => {
    if (!on || !navigator.geolocation) return;
    const until = Date.now() + 15 * 60 * 1000;
    let last = 0;
    const watch = navigator.geolocation.watchPosition(
      (pos) => {
        const now = Date.now();
        if (now > until) {
          setOn(false);
          return;
        }
        if (now - last < 20000) return;
        last = now;
        void rt.sendLocation(thread, pos.coords.latitude, pos.coords.longitude, until);
      },
      () => setErr("Location permission was not granted."),
      { enableHighAccuracy: true, maximumAge: 10000 },
    );
    return () => navigator.geolocation.clearWatch(watch);
  }, [on, thread]);
  if (!navigator.geolocation) return null;
  return (
    <button type="button" className="ghost" onClick={() => { setErr(""); setOn((v) => !v); }}>
      {on ? "Stop live location" : "Live location"}
      {err ? ` ${err}` : ""}
    </button>
  );
}

function ChannelsPane() {
  const [items, setItems] = useState<{ id: string; name: string }[]>([]);
  const [name, setName] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [posts, setPosts] = useState<{ id: string; name: string; text: string }[]>([]);
  const [text, setText] = useState("");
  const [err, setErr] = useState("");
  const load = () => {
    if (!rt.token) return;
    void api.channels(rt.token).then((body) => {
      setItems((body as { items: { id: string; name: string }[] }).items ?? []);
    }).catch((e) => setErr((e as Error).message));
  };
  useEffect(load, []);
  useEffect(() => {
    if (!open || !rt.token) return;
    void api.channelPosts(rt.token, open).then((body) => {
      setPosts((body as { posts: { id: string; name: string; text: string }[] }).posts ?? []);
    }).catch((e) => setErr((e as Error).message));
  }, [open]);
  return (
    <div className="list" style={{ padding: 12 }}>
      <p className="note">A public channel is readable by any Bleep account. The relay stores the text in the clear.</p>
      {err && <p className="warn">{err}</p>}
      <form onSubmit={(e) => {
        e.preventDefault();
        if (!rt.token || !name.trim()) return;
        void api.createChannel(rt.token, name.trim()).then(() => { setName(""); load(); }).catch((ex) => setErr((ex as Error).message));
      }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Channel name" aria-label="Channel name" />
        <button className="ghost" type="submit">Create channel</button>
      </form>
      {items.map((ch) => (
        <button key={ch.id} type="button" className="row" onClick={() => setOpen(ch.id)}>{ch.name}</button>
      ))}
      {open && (
        <div>
          {posts.map((p) => <p key={p.id}><b>{p.name}</b> {p.text}</p>)}
          <form onSubmit={(e) => {
            e.preventDefault();
            if (!rt.token || !text.trim()) return;
            const who = rt.snap?.settings.handle || rt.snap?.identity.displayName || "Someone";
            void api.postChannel(rt.token, open, text.trim(), who).then(() => {
              setText("");
              return api.channelPosts(rt.token!, open);
            }).then((body) => setPosts((body as { posts: { id: string; name: string; text: string }[] }).posts ?? [])).catch((ex) => setErr((ex as Error).message));
          }}>
            <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Post" aria-label="Post" />
            <button className="ghost" type="submit">Post</button>
          </form>
        </div>
      )}
    </div>
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
        <button className="primary" onClick={() => void rt.unlockChamber(id, pin).then((ok) => { if (!ok) setPin(""); })}>Unlock Chamber</button>
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
  const name = c.peer?.nickname || c.peer?.displayName || "peer";
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
  msg, mine, onReply, onReact, onDel, onDelAll, onOpenHeld, onEdit,
}: {
  msg: Message;
  mine: boolean;
  onReply: () => void;
  onReact: (e: string) => void;
  onDel: () => void;
  onDelAll: () => void;
  onOpenHeld: () => void;
  onEdit: () => void;
}) {
  const onceHidden = Boolean(msg.viewOnce && !msg.viewed && !mine && !msg.deleted);
  const [url, setUrl] = useState<string | null>(null);
  const hold = useRef(0);
  const start = useRef({ x: 0, y: 0 });
  useEffect(() => {
    if (onceHidden || !msg.attachmentId || msg.deleted || msg.needsDownload) return;
    let revoke = "";
    let dead = false;
    void rt.vault.getAttachment(msg.attachmentId).then((att) => {
      if (!att || dead) return;
      revoke = URL.createObjectURL(new Blob([att.bytes.slice().buffer], { type: att.mime }));
      setUrl(revoke);
    });
    return () => {
      dead = true;
      if (revoke) URL.revokeObjectURL(revoke);
    };
  }, [msg.attachmentId, msg.deleted, msg.needsDownload, onceHidden]);
  if (msg.kind === "system") return <div className="bubble sys">{msg.text}</div>;
  const onBody = (target: EventTarget | null) => {
    const el = target as HTMLElement | null;
    return !el?.closest("button, a, audio, video, input");
  };
  return (
    <div
      className={"bubble" + (mine ? " me" : "")}
      title="Press and hold to reply"
      onContextMenu={(e) => {
        e.preventDefault();
        if (!onBody(e.target)) return;
        onReply();
      }}
      onPointerDown={(e) => {
        if (e.pointerType === "mouse" || e.button !== 0 || !onBody(e.target)) return;
        start.current = { x: e.clientX, y: e.clientY };
        window.clearTimeout(hold.current);
        hold.current = window.setTimeout(() => onReply(), 480);
      }}
      onPointerMove={(e) => {
        if (Math.hypot(e.clientX - start.current.x, e.clientY - start.current.y) > 16) window.clearTimeout(hold.current);
      }}
      onPointerUp={() => window.clearTimeout(hold.current)}
      onPointerCancel={() => window.clearTimeout(hold.current)}
    >
      {msg.deleted ? <div className="t" style={{ color: "var(--faint)" }}>Deleted on this device</div> : (
        <>
          {msg.replyText && <div className="quote">{msg.replyText}</div>}
          {onceHidden ? (
            <button type="button" className="ghost" onClick={onOpenHeld}>View once — tap to open. Capture is still possible.</button>
          ) : (
            <>
              {msg.needsDownload && (
                <button className="ghost" onClick={onOpenHeld}>Tap to open {msg.name || "file"}. Low Data waits until you ask. Public Bleep did not scan this in the cloud.</button>
              )}
              {!msg.needsDownload && msg.kind === "photo" && url && <img className="media" src={url} alt="" />}
              {!msg.needsDownload && msg.kind === "voice" && url && <audio controls src={url} style={{ width: "100%" }} />}
              {!msg.needsDownload && msg.kind === "file" && <div className="t">File: {msg.name}</div>}
              {msg.kind === "call" && <div className="t">☎ {msg.text}</div>}
              {msg.text && msg.kind !== "call" && (
                <div className="t" onDoubleClick={mine ? onEdit : undefined}>
                  {msg.text}
                  {msg.edited ? " (edited)" : ""}
                  {msg.viewOnce ? " · Open once" : ""}
                </div>
              )}
              {msg.viewOnce && !msg.text && msg.kind !== "call" && <div className="t">Open once</div>}
            </>
          )}
        </>
      )}
      <div className="at">
        {fmtTime(msg.at)} {msg.reacted ?? ""} <Receipt status={msg.status} />
        <button className="iconbtn tiny" aria-label="Reply" onClick={onReply}>↩</button>
        <button className="iconbtn tiny" aria-label="React" onClick={() => onReact("👍")}>👍</button>
        <button className="iconbtn tiny" aria-label="Delete for me" title="Delete for me" onClick={onDel}>×</button>
        {mine && (
          <button className="iconbtn tiny" aria-label="Delete for everyone" title="Delete for everyone — they may honour it. Not screenshot-proof." onClick={onDelAll}>
            <Icon d={ICON.trash} />
          </button>
        )}
      </div>
    </div>
  );
}

function MediaGrid({ msgs, onClose }: { msgs: Message[]; onClose: () => void }) {
  const items = msgs.filter((m) => !m.deleted && (m.kind === "photo" || m.kind === "file" || m.kind === "voice"));
  return (
    <div className="sheet" onClick={onClose}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h3>Photos and files</h3>
        <p className="note">From this chat, on this device.</p>
        {items.length === 0 && <p className="note">None yet.</p>}
        <div className="media-grid">
          {items.map((m) => <MediaTile key={m.id} msg={m} />)}
        </div>
        <div className="actions"><button onClick={onClose}>Close</button></div>
      </div>
    </div>
  );
}

function MediaTile({ msg }: { msg: Message }) {
  const hidden = Boolean(msg.viewOnce && !msg.viewed && !msg.fromMe);
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (hidden || !msg.attachmentId || msg.needsDownload) return;
    let revoke = "";
    let dead = false;
    void rt.vault.getAttachment(msg.attachmentId).then((att) => {
      if (!att || dead) return;
      revoke = URL.createObjectURL(new Blob([att.bytes.slice().buffer], { type: att.mime }));
      setUrl(revoke);
    });
    return () => {
      dead = true;
      if (revoke) URL.revokeObjectURL(revoke);
    };
  }, [msg.attachmentId, msg.needsDownload, hidden]);
  if (hidden) return <button type="button" className="filetile" onClick={() => void rt.consumeViewOnce(msg)}>Open once</button>;
  if (msg.kind === "photo" && url) return <img src={url} alt="" />;
  return <button type="button" className="filetile" onClick={() => void rt.openHeld(msg)}>{msg.kind === "voice" ? "Voice note" : msg.name || "File"}</button>;
}

function SafetyMenu({ thread, peer, nick, onNick, onDone }: { thread: Thread; peer: Peer; nick: string; onNick: (v: string) => void; onDone: () => void }) {
  const snap = rt.snap!;
  const num = useMemo(() => pairSafety(snap.identity, peer), [snap.identity, peer]);
  return (
    <div>
          <h3>Safety number</h3>
          <p className="safety">{num}</p>
          <p className="note">Compare this in person or by a second channel. A change is a new key.</p>
          <div className="actions">
            <button onClick={() => { rt.verifyPeer(peer); onDone(); }}>Mark verified</button>
            <button onClick={() => rt.cycleDisappear(thread)}>{disappearAction(thread.disappearingSec)}</button>
            <button
              className={thread.receipts ? "on" : ""}
              aria-pressed={thread.receipts}
              aria-label={thread.receipts ? "Read receipts on" : "Read receipts off"}
              title={thread.receipts ? "Read receipts on" : "Read receipts off"}
              onClick={() => { thread.receipts = !thread.receipts; rt.bump(); }}
            >
              <Icon d={thread.receipts ? ICON.checks : ICON.check} />
            </button>
            <button onClick={() => { thread.typing = !thread.typing; rt.bump(); }}>
              {thread.typing ? "Typing off" : "Typing on"}
            </button>
          </div>
          <div className="actions">
            <button onClick={() => rt.pinThread(thread)}>{thread.pinned ? "Unpin" : "Pin"}</button>
            <button onClick={() => rt.muteThread(thread)}>{muteAction(thread)}</button>
            <button onClick={() => rt.archiveThread(thread)}>{thread.archived ? "Unarchive" : "Archive"}</button>
          </div>
          <div className="actions">
            <button onClick={() => void rt.startChamber(peer)}>Start a Chamber…</button>
            <button className="danger" onClick={() => { rt.block(peer); onDone(); }}>Block</button>
          </div>
          <label>Private name</label>
          <input value={nick} onChange={(e) => onNick(e.target.value)} placeholder="Only on this device" />
          <div className="actions">
            <button onClick={() => { rt.setNickname(peer, nick); onDone(); }}>Save private name</button>
          </div>
    </div>
  );
}

function Requests({ onJoined }: { onJoined: (threadId: string) => void }) {
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
            <Face id={peer?.avatarId} letter={peer?.displayName || "?"} />
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
                      onJoined("g:" + g.groupId);
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
  const [file, setFile] = useState<FileBits | null>(null);
  const [hits, setHits] = useState<Hit[] | null>(null);
  const [err, setErr] = useState("");
  const snap = rt.snap!;
  const live = snap.statuses.filter((s) => s.until > Date.now());
  const share = (anyway = false) => {
    const body = text.trim();
    if (!body && !file) return;
    const peers = Object.values(snap.peers).filter((p) => p.accepted && !p.blocked);
    if (!anyway) {
      const found = rt.previewHits(body, file ? [file] : [], peers);
      if (found.length) {
        setHits(found);
        return;
      }
    }
    void rt.publishStatus(body, file ?? undefined).then(() => {
      setText("");
      setFile(null);
      setHits(null);
      setErr("");
    }).catch((e) => setErr((e as Error).message));
  };
  return (
    <div className="settings">
      <section>
        <h3>Your status</h3>
        <p className="note">Text, a photo, a voice note, or a file. Sealed to accepted peers for 24 hours. No viewer list. Chambers do not publish Status.</p>
        <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} placeholder="Status" style={{ width: "100%", background: "var(--ink)", border: "1px solid var(--rule)", borderRadius: 8, padding: 8 }} />
        <label className="ghost" style={{ display: "inline-block", marginTop: 8 }}>
          Add photo, voice, or file
          <input
            type="file"
            className="hidden"
            accept="image/*,video/*,audio/*,.pdf,application/*"
            onChange={async (e) => {
              const f = e.target.files?.[0];
              e.target.value = "";
              if (!f) return;
              setErr("");
              try {
                setFile(await readMedia(f, 1280));
              } catch (ex) {
                setErr((ex as Error).message);
              }
            }}
          />
        </label>
        {file && (
          <p className="note">
            {file.name}
            <button type="button" className="ghost" onClick={() => setFile(null)}>Remove</button>
          </p>
        )}
        {err && <p className="warn">{err}</p>}
        <button className="primary" onClick={() => share(false)}>Share for 24h</button>
      </section>
      {hits && (
        <div className="sheet">
          <div className="panel">
            <h3>Guardian</h3>
            {hits.map((h, i) => (
              <p className="reason" key={i}>{h.reason}</p>
            ))}
            <p className="note">Hits are not stored. This status has not been shared.</p>
            <div className="actions">
              <button onClick={() => setHits(null)}>Cancel</button>
              <button className="go" onClick={() => share(true)}>Share anyway</button>
            </div>
          </div>
        </div>
      )}
      {live.map((s) => (
        <section key={s.id}>
          <div className="name">{s.fromName}</div>
          {s.text && <div>{s.text}</div>}
          {s.attachmentId && <HeldMedia id={s.attachmentId} mime={s.mime} name={s.name} />}
          <div className="note">until {new Date(s.until).toLocaleString()}</div>
        </section>
      ))}
    </div>
  );
}

function HeldMedia({ id, mime, name }: { id: string; mime?: string; name?: string }) {
  const [url, setUrl] = useState("");
  useEffect(() => {
    let dead = false;
    let revoke = "";
    void rt.vault.getAttachment(id).then((att) => {
      if (!att || dead) return;
      revoke = URL.createObjectURL(new Blob([att.bytes.slice().buffer], { type: att.mime || mime || "" }));
      setUrl(revoke);
    });
    return () => {
      dead = true;
      if (revoke) URL.revokeObjectURL(revoke);
    };
  }, [id, mime]);
  if (!url) return <p className="note">{name || "Media"}</p>;
  if ((mime || "").startsWith("video/")) return <video className="status-media" controls src={url} />;
  if ((mime || "").startsWith("audio/")) return <audio controls src={url} />;
  if ((mime || "").startsWith("image/") || !mime) return <img className="status-media" src={url} alt="" />;
  return <p className="note">{name || "File"}</p>;
}

function lockChoices(current: number): [number, string][] {
  const choices: [number, string][] = [
    [0, "Never"],
    [60, "60 seconds"],
    [300, "5 minutes"],
    [900, "15 minutes"],
    [3600, "1 hour"],
  ];
  if (!choices.some(([sec]) => sec === current)) choices.push([current, `${current} seconds`]);
  return choices;
}

function SettingsPane({ slide, onSlide, onErased }: { slide: number; onSlide: (i: number) => void; onErased: () => void }) {
  const snap = rt.snap!;
  const s = snap.settings;
  const [pass, setPass] = useState("");
  const [handle, setHandle] = useState(s.handle ?? "");
  const [impPass, setImpPass] = useState("");
  const [impPin, setImpPin] = useState("");
  const [impErr, setImpErr] = useState("");
  return (
    <div className="settings">
      <SlideTabs label="You" labels={YOU_SLIDES} index={slide} onIndex={onSlide}>
        <section>
          <p>{snap.identity.displayName}</p>
          <p className="note">Profile {profileName()}</p>
          <MeCard compact />
          <p className="note">Mailbox {snap.identity.mailboxId}</p>
          <p className="note">
            This page is the invite web vault. The charter vault is the APK:{" "}
            <a href="/hashes.html">download + hashes (T6)</a>
          </p>
          <p className="note">
            <button type="button" className="ghost" onClick={() => onSlide(4)}>Features</button>
          </p>
        </section>
        <section>
          <div id="handle-control">
            <h3>Optional handle</h3>
            <p className="note">Opt-in publish. Other accounts can search a handle prefix of two or more characters. Email and display name are not searchable.</p>
            <input value={handle} onChange={(e) => setHandle(e.target.value)} placeholder="lebo" />
            <button className="primary" onClick={() => void rt.publishHandle(handle)}>Publish handle</button>
            {s.handle && <button className="ghost" onClick={() => void rt.unpublishHandle().then(() => setHandle(""))}>Remove handle</button>}
          </div>
          {s.confirmNote && <p id="confirm-note" className="note">{s.confirmNote}</p>}
          {s.accountEmail && (
            <>
              <AccountDevices />
              <AccountPassword />
              <CloseMailbox onErased={onErased} />
            </>
          )}
          <AccountPin />
        </section>
        <section>
          <div>
            <h3>On-device checks</h3>
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
          </div>
          <div>
            <h3>Background notice</h3>
            <p className="note">The notice says Bleep. It does not include the message or the sender. The browser push service can see that a notice was sent. This box sends notices only after a push key is set.</p>
            <button type="button" className="ghost" onClick={() => void rt.enablePush().then((note) => alert(note)).catch((e) => alert((e as Error).message))}>
              Notify this browser
            </button>
          </div>
          <div id="lock-timer">
            <h3>Lock & data</h3>
            <div className="toggle">
              <span>Auto-lock</span>
              <select
                aria-label="Auto-lock"
                value={s.autoLockSec}
                onChange={(e) => { s.autoLockSec = Number(e.target.value); rt.bump(); rt.touch(); }}
              >
                {lockChoices(s.autoLockSec).map(([sec, label]) => (
                  <option key={sec} value={sec}>{label}</option>
                ))}
              </select>
            </div>
            <div className="toggle">
              <span>Low Data</span>
              <input type="checkbox" checked={s.lowData} onChange={(e) => { s.lowData = e.target.checked; rt.bump(); }} />
            </div>
            <p className="note">Low Data keeps auto-download off on mobile-shaped viewports. Proud, not hidden.</p>
          </div>
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
            A compelled operator of this relay yields sealed blobs, the account email, published handles, and the sealed history ciphertext. Reading history still takes the recovery code or a device that already has the key.
          </p>
          <Features />
        </section>
        <section id="erase-device">
          <h3>Danger</h3>
          <p className="note">Erase this device wipes this browser. The mailbox stays for your other devices. A device that never comes back keeps its local files.</p>
          <button className="ghost" onClick={() => void rt.eraseDevice().then(onErased)}>Erase this device</button>
        </section>
      </SlideTabs>
    </div>
  );
}

function AccountDevices() {
  const [rows, setRows] = useState<{ device_id: string; label: string; created_band: string; revoked: boolean; erase_requested: boolean; this_device: boolean }[]>([]);
  const [note, setNote] = useState("");
  const [err, setErr] = useState("");
  const load = () => {
    void rt.listDevices().then(setRows).catch((e) => setErr((e as Error).message));
  };
  useEffect(load, []);
  return (
    <section id="device-list">
      <h3>Devices</h3>
      <p className="note">Signed in as {rt.snap?.settings.accountEmail}. Erase marks that device. The wipe runs the next time it contacts this box. A device that never comes back keeps its local files.</p>
      <p className="note">Thread text is one sealed blob. File bytes are separate sealed rows. A Chamber uses its own PIN and is not inside that blob.</p>
      {rows.map((d) => (
        <div key={d.device_id} className="toggle">
          <span>
            {d.label} · {d.created_band}
            {d.this_device ? " · this browser" : ""}
            {d.revoked ? " · revoked" : ""}
            {d.erase_requested ? " · erase waiting" : ""}
          </span>
          {!d.this_device && !d.revoked && (
            <span>
              <button className="ghost" onClick={async () => { await rt.revokeRemote(d.device_id); load(); }}>Revoke</button>
              <button className="ghost" onClick={async () => { await rt.eraseRemote(d.device_id); setNote("Erase is waiting for that device's next contact."); load(); }}>Erase</button>
            </span>
          )}
        </div>
      ))}
      {note && <p className="note">{note}</p>}
      {err && <p className="warn">{err}</p>}
    </section>
  );
}

function CloseMailbox({ onErased }: { onErased: () => void }) {
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  return (
    <section id="close-mailbox">
      <h3>Close this mailbox</h3>
      <p className="note">Every device loses this mailbox. The email can be used for a new account. This does not only wipe this browser.</p>
      <input type="password" placeholder="Current password" value={password} onChange={(e) => setPassword(e.target.value)} />
      <button
        className="ghost"
        onClick={async () => {
          setErr("");
          try {
            await rt.closeAccount(password);
            onErased();
          } catch (ex) {
            const msg = (ex as Error).message || "";
            setErr(msg.includes("Email or password") ? "Email or password is wrong." : msg);
          }
        }}
      >
        Close this mailbox
      </button>
      {err && <p className="warn">{err}</p>}
    </section>
  );
}

function AccountPassword() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [note, setNote] = useState("");
  const [err, setErr] = useState("");
  return (
    <section>
      <h3>Password</h3>
      <p className="note">This changes the login. It does not change the recovery code, and it does not open messages.</p>
      <input type="password" placeholder="Current password" value={current} onChange={(e) => setCurrent(e.target.value)} />
      <input type="password" placeholder="New password" value={next} onChange={(e) => setNext(e.target.value)} />
      <button
        className="primary"
        onClick={async () => {
          setErr("");
          setNote("");
          try {
            if (next.length < 8) throw new Error("Password should be 8 characters or more.");
            await rt.changeAccountPassword(current, next);
            setCurrent("");
            setNext("");
            setNote("Password updated.");
          } catch (e) {
            setErr((e as Error).message);
          }
        }}
      >
        Change password
      </button>
      {note && <p className="note">{note}</p>}
      {err && <p className="warn">{err}</p>}
    </section>
  );
}

function AccountPin() {
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [note, setNote] = useState("");
  const [err, setErr] = useState("");
  return (
    <section>
      <h3>Bleep lock PIN</h3>
      <p className="note">The PIN wraps this browser vault. It is not the account password.</p>
      <input type="password" placeholder="New PIN" value={next} onChange={(e) => setNext(e.target.value)} />
      <input type="password" placeholder="New PIN again" value={again} onChange={(e) => setAgain(e.target.value)} />
      <button
        className="primary"
        onClick={async () => {
          setErr("");
          setNote("");
          try {
            if (next.length < 4) throw new Error("PIN should be 4 characters or more.");
            if (next !== again) throw new Error("PINs do not match.");
            await rt.vault.rewrap(next, rt.snap!);
            setNext("");
            setAgain("");
            setNote("PIN updated on this browser.");
          } catch (e) {
            setErr((e as Error).message);
          }
        }}
      >
        Change PIN
      </button>
      {note && <p className="note">{note}</p>}
      {err && <p className="warn">{err}</p>}
    </section>
  );
}

function NewGroup({ onClose, onCreated }: { onClose: () => void; onCreated: (threadId: string) => void }) {
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
                const threadId = await rt.createGroup(name.trim(), ids);
                onCreated(threadId);
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

function AddPerson({ onClose, onGroup }: { onClose: () => void; onGroup: () => void }) {
  const [code, setCode] = useState("");
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<{ handle: string }[]>([]);
  const [hello, setHello] = useState("Sawubona — request to message.");
  const [err, setErr] = useState("");
  const video = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    let stream: MediaStream | undefined;
    let timer = 0;
    let dead = false;
    void (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
        if (dead) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        if (video.current) {
          video.current.srcObject = stream;
          await video.current.play();
        }
        const scan = () => {
          const v = video.current;
          if (!v || v.readyState < 2 || !v.videoWidth || !v.videoHeight) return;
          const scale = Math.min(1, 320 / v.videoWidth);
          const c = document.createElement("canvas");
          c.width = Math.max(1, Math.round(v.videoWidth * scale));
          c.height = Math.max(1, Math.round(v.videoHeight * scale));
          const ctx = c.getContext("2d");
          if (!ctx) return;
          ctx.drawImage(v, 0, 0, c.width, c.height);
          const img = ctx.getImageData(0, 0, c.width, c.height);
          const qr = jsQR(img.data, img.width, img.height);
          if (qr) setCode(qr.data);
        };
        const loop = () => {
          if (dead) return;
          scan();
          timer = window.setTimeout(loop, 700);
        };
        if (!dead) timer = window.setTimeout(loop, 700);
      } catch {
        /* camera optional */
      }
    })();
    return () => {
      dead = true;
      stream?.getTracks().forEach((t) => t.stop());
      if (timer) window.clearTimeout(timer);
    };
  }, []);
  return (
    <div className="sheet" onClick={onClose}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h3>Add by Bleep ID or handle</h3>
        <p className="reason">No address-book upload. Paste an eight-character Bleep ID, scan a QR, or type a published handle. Email is not searchable.</p>
        <video ref={video} style={{ width: "100%", borderRadius: 8, background: "#000" }} muted playsInline />
        <label>Published handle</label>
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="two or more letters" />
        <button
          type="button"
          onClick={async () => {
            setErr("");
            try {
              const rows = await rt.searchDirectory(query);
              setHits(rows);
              if (query.trim().length >= 2 && rows.length === 0) setErr("No published handle with that prefix.");
            } catch (e) {
              setErr((e as Error).message);
            }
          }}
        >
          Search handles
        </button>
        {hits.map((h) => (
          <button key={h.handle} type="button" onClick={() => setCode("@" + h.handle)}>@{h.handle}</button>
        ))}
        <p className="note">A hit is a published handle. Email is not searchable.</p>
        <label>Bleep ID or handle</label>
        <textarea value={code} onChange={(e) => setCode(e.target.value)} rows={2} placeholder="k7m2-pq9x or @lebo" style={{ width: "100%", background: "var(--ink)", border: "1px solid var(--rule)", borderRadius: 8, padding: 8 }} />
        <label>First message (they will see a request)</label>
        <input value={hello} onChange={(e) => setHello(e.target.value)} />
        {err && <p className="warn">{err}</p>}
        <div className="actions">
          <button onClick={onClose}>Cancel</button>
          <button onClick={onGroup}>New group</button>
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
          {canStrip && <button onClick={() => onSendAnyway(false)}>Send with location</button>}
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
            {!armed ? "Tap again to send anyway" : canStrip ? "Send without location" : "Send anyway"}
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
