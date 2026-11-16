import { useEffect, useState } from "react";
import { rt } from "./instance";
import { SlideTabs } from "./SlideTabs";

type DeviceRow = {
  device_id: string;
  label: string;
  created_band: string;
  revoked: boolean;
  erase_requested: boolean;
  this_device: boolean;
};

const MAIL_OFF = "not configured";
const LABELS = ["Overview", "Device", "Account", "People", "Chambers", "Next"] as const;

function CountTiles({ rows }: { rows: [string, number][] }) {
  const max = Math.max(1, ...rows.map(([, n]) => n));
  return (
    <div className="tiles">
      {rows.map(([label, n]) => (
        <div key={label} className="tile">
          <div className="tile-n">{n}</div>
          <div className="tile-label">{label}</div>
          <div className="bar" aria-hidden="true"><span style={{ width: `${(n / max) * 100}%` }} /></div>
        </div>
      ))}
    </div>
  );
}

export function GuardianReport({
  onJump,
  onChats,
}: {
  onJump: (id: string) => void;
  onChats: () => void;
}) {
  const snap = rt.snap!;
  const s = snap.settings;
  const [panel, setPanel] = useState(0);
  const [rows, setRows] = useState<DeviceRow[]>([]);
  const [deviceErr, setDeviceErr] = useState(false);
  const [devicesLoaded, setDevicesLoaded] = useState(false);
  useEffect(() => {
    let dead = false;
    void rt.listDevices().then(
      (list) => { if (!dead) { setRows(list); setDevicesLoaded(true); } },
      () => { if (!dead) { setDeviceErr(true); setDevicesLoaded(true); } },
    );
    return () => { dead = true; };
  }, [snap.settings.deviceId]);

  const mine = rows.find((d) => d.this_device);
  const othersLive = rows.filter((d) => !d.this_device && !d.revoked);
  const mailOff = (s.confirmNote || "").toLowerCase().includes(MAIL_OFF);
  const unconfirmed = s.emailConfirmed === false && !mailOff;
  const threads = Object.values(snap.threads).filter((t) => t.kind !== "chamber" && !t.id.startsWith("ch:")).length;
  const blocked = snap.blocked.length;
  const verified = Object.values(snap.peers).filter((p) => p.verified).length;
  const counts: [string, number][] = [
    ["Threads", threads],
    ["Requests", snap.requests.length],
    ["Blocked", blocked],
    ["Verified", verified],
  ];
  const chambers = Object.values(snap.chambers);
  const openChambers = chambers.filter((c) => c.unlocked || rt.pinHeld(c.id));
  const lockedCount = chambers.length - openChambers.length;
  const pinInMemory = openChambers.some((c) => rt.pinHeld(c.id));
  const lockNever = (s.autoLockSec ?? 300) === 0;
  const lockLabel = lockNever ? "Never" : s.autoLockSec === 60 ? "60 seconds" : s.autoLockSec === 300 ? "5 minutes" : s.autoLockSec === 900 ? "15 minutes" : s.autoLockSec === 3600 ? "1 hour" : `${s.autoLockSec} seconds`;
  const pocketMissing = snap.androidPocketKnown !== true;
  const filesHere = rt.filesWaitingLocally();
  const unverifiedPeer = Object.values(snap.peers).some((p) => p.accepted && !p.verified && !p.blocked);

  const risks: string[] = [];
  if (!rt.regionOk) risks.push("Region pin does not match, so sending is stopped.");
  if (lockNever) risks.push("Lock is Never.");
  if (unconfirmed) risks.push("Address is unconfirmed while mail is configured, so another device cannot be added until the link is opened.");
  if (othersLive.length > 0) risks.push("A device you do not recognise is still live.");
  if (s.handle) risks.push("Handle is published.");
  if (openChambers.length > 0) risks.push("A chamber is unlocked on this screen.");
  if (pocketMissing) risks.push("Another device has not pulled shared history yet, so its pocket is not on this browser.");
  if (filesHere) risks.push("Files on this browser stay here until this browser next reaches the relay.");
  if (s.lowData) risks.push("Low Data waits to fetch bytes until you open the file. The name still shows.");

  const chamberLine = chambers.length === 0
    ? "None"
    : `${lockedCount} locked · ${openChambers.length} open`;

  return (
    <div className="guardian">
      <p className="note">This report uses this browser and this account. The relay still stores sealed ciphertext, the email, device rows, and published handles. It does not score chats, store directory searches, or recommend people.</p>
      <SlideTabs label="Guardian" labels={LABELS} index={panel} onIndex={setPanel}>
        <section>
          <CountTiles rows={counts} />
          <div className="marks">
            <div className={rt.regionOk ? "mark" : "mark bad"}>
              <strong>{rt.regionOk ? "ZA-JHB" : "Stopped"}</strong>
              <span>{rt.regionOk ? "Region pin" : "Fail closed"}</span>
            </div>
            <div className={lockNever ? "mark bad" : "mark"}>
              <strong>{lockLabel}</strong>
              <span>{lockNever ? "Open state" : "Lock timer"}</span>
            </div>
            <div className={openChambers.length > 0 ? "mark bad" : "mark"}>
              <strong>{chamberLine}</strong>
              <span>Chambers</span>
            </div>
          </div>
          <p className="note">{risks.length} risk {risks.length === 1 ? "sentence applies" : "sentences apply"}.</p>
        </section>
        <section>
          <p>{rt.regionOk ? "Region pin: ZA-JHB." : "Region pin does not match. This client is fail-closed and is not sending."}</p>
          <p>{lockNever ? "Lock timer: Never. Never is the open state." : `Lock timer: ${lockLabel}.`}</p>
          <p>{mine ? `This device’s label is ${mine.label}.` : "This device’s label loads with the device list."}</p>
          <p>{mine?.erase_requested ? "A remote erase is waiting for the next contact." : "A remote erase is not waiting on this device."}</p>
          <p className="note">A device that never talks to the relay keeps its local copy.</p>
        </section>
        <section>
          {mailOff && <p>{s.confirmNote}</p>}
          {!mailOff && !unconfirmed && <p>Address confirmed.</p>}
          {unconfirmed && <p>This address is not confirmed.</p>}
          {unconfirmed && s.confirmNote && <p className="note">{s.confirmNote}</p>}
          {s.handle ? (
            <p>Handle @{s.handle} is published. The prefix directory can return the handle and the identity public keys. It does not return the email.</p>
          ) : (
            <p>No handle is published.</p>
          )}
          <div>
            {rows.map((d) => (
              <p key={d.device_id}>
                {d.label} · hour band {d.created_band}
                {d.this_device ? " · this browser" : ""}
                {d.revoked ? " · revoked" : ""}
                {d.erase_requested ? " · erase requested" : ""}
              </p>
            ))}
            {!devicesLoaded && !deviceErr && <p className="note">Loading device rows.</p>}
            {devicesLoaded && rows.length === 0 && !deviceErr && <p className="note">No device rows loaded.</p>}
            {deviceErr && <p className="warn">Device list did not load.</p>}
          </div>
          <p className="note">The recovery code was shown once at signup. It is not on this page. It is not on the relay.</p>
        </section>
        <section>
          <CountTiles rows={counts} />
          <p className="note">Counts only. No message text, no attachment bytes, and no ranking of who you talk to.</p>
          <p className="note">Blocked entries stay in the local set that shared history round-trips. The directory skips them on this device.</p>
        </section>
        <section>
          <p>
            {chambers.length === 0
              ? "No chamber on this device."
              : `${lockedCount} locked. ${openChambers.length} unlocked.`}
          </p>
          <p>{pinInMemory ? "A chamber PIN is only in memory while it is open." : "No chamber PIN is in memory. A chamber PIN is only kept in memory while that chamber is open."}</p>
          <p>Opening Bleep does not open a chamber. The chamber blob uses the chamber PIN, separate from the account recovery key.</p>
          <p>Main history does not hold chamber text.</p>
        </section>
        <section>
          {risks.length > 0 && (
            <>
              <h3>Risks</h3>
              {risks.map((line) => <p key={line}>{line}</p>)}
            </>
          )}
          <h3>Recommendations</h3>
          <div className="actions">
            {lockNever && <button className="ghost" onClick={() => onJump("lock-timer")}>Set a lock timer</button>}
            {unconfirmed && <button className="ghost" onClick={() => onJump("confirm-note")}>Confirm the address</button>}
            {s.handle && <button className="ghost" onClick={() => onJump("handle-control")}>Remove the handle</button>}
            {othersLive.length > 0 && <button className="ghost" onClick={() => onJump("device-list")}>Revoke or erase a device</button>}
            {unverifiedPeer && <button className="ghost" onClick={onChats}>Verify a safety number</button>}
            {openChambers.length > 0 && (
              <button className="ghost" onClick={() => { for (const c of openChambers) rt.lockChamber(c.id); }}>Lock the chamber</button>
            )}
            {s.accountEmail && <button className="ghost" onClick={() => onJump("erase-device")}>Erase this device</button>}
            {s.accountEmail && <button className="ghost" onClick={() => onJump("close-mailbox")}>Close this mailbox</button>}
          </div>
          <p className="note">Erase this device wipes this vault and leaves the shared mailbox for the others. Close this mailbox is the separate action: every device loses it, and the email can be used again.</p>
          <p className="note">Verify a safety number before treating a peer as checked. Lock the chamber before you leave the screen.</p>
          <h3>What this page does not show</h3>
          <p>No behaviour score, no activity feed, and no risky contacts.</p>
          <p>No email search, no display-name search, and no recommendation list.</p>
          <p>No copy of the recovery seed, device credential, or chamber PIN.</p>
        </section>
      </SlideTabs>
    </div>
  );
}
