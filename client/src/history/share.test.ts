import { describe, expect, it } from "vitest";
import { dmThreadId } from "../session/session";
import { emptySnapshot } from "../store/vault";
import { dmPeer, foldSharedHistory } from "./share";
import type { Identity } from "../protocol/keys";

const self = "a".repeat(32);
const peer = "b".repeat(32);

function snap() {
  const id = { mailboxId: self, displayName: "me" } as Identity;
  return emptySnapshot(id);
}

describe("shared history threads", () => {
  it("reads the other mailbox from either order", () => {
    expect(dmPeer(`dm:${peer}:${self}`, self)).toBe(peer);
    expect(dmPeer(dmThreadId(self, peer), self)).toBe(peer);
    expect(dmPeer("ch:nope", self)).toBeNull();
  });

  it("shows a phone thread and a browser thread as one direct chat", () => {
    const s = snap();
    const canonical = dmThreadId(self, peer);
    s.threads[peer] = {
      id: peer,
      kind: "dm",
      peerMailboxId: peer,
      title: "Old key",
      lastPreview: "from the browser",
      lastAt: 10,
      unread: 0,
      pinned: false,
      muted: false,
      archived: false,
      disappearingSec: null,
      receipts: true,
      typing: true,
      draft: "",
    };
    s.messages[peer] = [
      { id: "web-1", threadId: peer, fromMe: true, kind: "text", text: "from the browser", at: 10 },
    ];
    foldSharedHistory(
      s,
      {
        [`dm:${peer}:${self}`]: {
          id: `dm:${peer}:${self}`,
          kind: "dm",
          peerMailboxId: peer,
          title: "Ada",
          lastPreview: "from the phone",
          lastAt: 20,
        },
      },
      {
        [`dm:${peer}:${self}`]: [
          { id: "phone-1", threadId: `dm:${peer}:${self}`, fromMe: false, peerId: peer, kind: "text", text: "from the phone", at: 20 },
          { id: "web-1", threadId: `dm:${peer}:${self}`, fromMe: true, kind: "text", text: "from the browser", at: 10, attachmentId: undefined },
        ],
      },
    );
    expect(s.threads[peer]).toBeUndefined();
    expect(s.messages[peer]).toBeUndefined();
    expect(s.threads[canonical]?.kind).toBe("dm");
    expect(s.threads[canonical]?.peerMailboxId).toBe(peer);
    expect(s.threads[canonical]?.title).toBe("Ada");
    expect(s.messages[canonical]?.map((m) => m.id)).toEqual(["web-1", "phone-1"]);
    expect(s.messages[canonical]?.[1]?.text).toBe("from the phone");
    expect(s.peers[peer]?.accepted).toBe(true);
    expect(s.threads[canonical]?.lastPreview).toBe("from the phone");
  });

  it("keeps a file id when the other device omits it, and skips a chamber", () => {
    const s = snap();
    const canonical = dmThreadId(self, peer);
    s.messages[canonical] = [
      { id: "pic", threadId: canonical, fromMe: true, kind: "photo", text: "Photo", at: 5, attachmentId: "file-aaaa" },
    ];
    s.threads["ch:secret"] = {
      id: "ch:secret",
      kind: "chamber",
      title: "Chamber",
      lastPreview: "hidden",
      lastAt: 1,
      unread: 0,
      pinned: false,
      muted: false,
      archived: false,
      disappearingSec: null,
      receipts: false,
      typing: false,
      draft: "",
    };
    s.messages["ch:secret"] = [
      { id: "secret-1", threadId: "ch:secret", fromMe: true, kind: "text", text: "hidden", at: 1 },
    ];
    foldSharedHistory(s, undefined, {
      [canonical]: [{ id: "pic", threadId: canonical, fromMe: true, kind: "photo", text: "", at: 5 }],
      "ch:secret": [{ id: "secret-2", threadId: "ch:secret", fromMe: false, kind: "text", text: "nope", at: 2 }],
    });
    expect(s.messages[canonical]?.[0]?.attachmentId).toBe("file-aaaa");
    expect(s.messages["ch:secret"]?.map((m) => m.id)).toEqual(["secret-1"]);
    expect(s.threads["ch:secret"]?.kind).toBe("chamber");
  });

  it("opens a group thread from the other device", () => {
    const s = snap();
    foldSharedHistory(
      s,
      { "g:group1": { id: "g:group1", kind: "group", groupId: "group1", title: "Family", lastPreview: "hi", lastAt: 3 } },
      { "g:group1": [{ id: "g1", threadId: "g:group1", fromMe: false, fromMailbox: peer, kind: "text", text: "hi", at: 3 }] },
    );
    expect(s.threads["g:group1"]?.kind).toBe("group");
    expect(s.groups.group1?.name).toBe("Family");
    expect(s.messages["g:group1"]?.[0]?.text).toBe("hi");
    expect(s.messages["g:group1"]?.[0]?.fromMailbox).toBe(peer);
  });

  it("keeps a star from either side and drops it when the message is deleted", () => {
    const s = snap();
    const canonical = dmThreadId(self, peer);
    s.messages[canonical] = [
      { id: "m1", threadId: canonical, fromMe: true, kind: "text", text: "hi", at: 1, starred: true },
      { id: "m2", threadId: canonical, fromMe: false, kind: "text", text: "yo", at: 2 },
    ];
    foldSharedHistory(s, undefined, {
      [canonical]: [
        { id: "m1", threadId: canonical, fromMe: true, kind: "text", text: "hi", at: 1 },
        { id: "m2", threadId: canonical, fromMe: false, kind: "text", text: "", at: 2, deleted: true, starred: true },
      ],
    });
    expect(s.messages[canonical]?.find((m) => m.id === "m1")?.starred).toBe(true);
    expect(s.messages[canonical]?.find((m) => m.id === "m2")?.starred).toBe(false);
  });
});
