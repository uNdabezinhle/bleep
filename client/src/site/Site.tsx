import { useState } from "react";
import { CONTACT_EMAIL } from "./contact";
import { onSiteClick } from "./router";

const LINKS = [
  ["/", "Home"],
  ["/about", "About"],
  ["/faq", "FAQ"],
  ["/contact", "Contact"],
  ["/register", "Register"],
] as const;

export function Site({ path }: { path: string }) {
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  return (
    <div className="site">
      <header className="site-bar">
        <a className="wordmark" href="/" onClick={onSiteClick}>Bleep</a>
        <button className="iconbtn site-menu" aria-label="Menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          {open ? "×" : "☰"}
        </button>
        <nav className={"site-nav" + (open ? " open" : "")}>
          {LINKS.map(([href, label]) => (
            <a key={href} href={href} className={path === href ? "on" : ""} onClick={(e) => { onSiteClick(e); close(); }}>
              {label}
            </a>
          ))}
          <a className="accent-link" href="/app" onClick={(e) => { onSiteClick(e); close(); }}>Open Bleep</a>
        </nav>
      </header>
      <main className="site-main">
        {path === "/" && <Home />}
        {path === "/about" && <About />}
        {path === "/faq" && <Faq />}
        {path === "/contact" && <Contact />}
        {path !== "/" && path !== "/about" && path !== "/faq" && path !== "/contact" && <Missing />}
      </main>
      <footer className="site-foot">
        {LINKS.map(([href, label]) => (
          <a key={href} href={href} onClick={onSiteClick}>{label}</a>
        ))}
        <a href="/hashes.html">APK and hashes</a>
      </footer>
    </div>
  );
}

function Home() {
  return (
    <>
      <section className="hero">
        <p className="eyebrow">Personal messenger</p>
        <h1>The conversation stays with the people in it.</h1>
        <p className="lede">
          A Bleep ID is made on the device. Email is the login. The relay holds sealed envelopes for a short time, and a sealed copy of history until unlink. It does not keep a plaintext transcript.
        </p>
        <div className="hero-actions">
          <a className="accent-link" href="/register" onClick={onSiteClick}>Create an account</a>
          <a className="quiet-link" href="/hashes.html">Download the Android APK</a>
        </div>
      </section>
      <section className="points">
        <article>
          <h2>A key on the device</h2>
          <p>The key is made on this device. Email is the account login. The recovery code never goes to the relay. The browser build is an invite client, not the Play Store app.</p>
        </article>
        <article>
          <h2>A request, then a thread</h2>
          <p>Someone with your Bleep code can ask to message you. A thread opens when you accept. Unknown codes cannot drop mail into an inbox.</p>
        </article>
        <article>
          <h2>Sealed on the way through</h2>
          <p>Public Bleep stores envelopes and wakes the device. Names do not go to the call relay. This build is not an audit.</p>
        </article>
      </section>
    </>
  );
}

function About() {
  return (
    <article className="prose">
      <p className="eyebrow">About</p>
      <h1>What this box is</h1>
      <p>
        The web app at Open Bleep is the invite client: a protocol-compatible vault in the browser so two people can try the loop. The charter vault is the Android app. Install that from the hash page, then paste this box’s URL as Public Bleep.
      </p>
      <p>
        A mailbox holds sealed envelopes with a short time to live. There is no sender column. A published handle can be found by prefix. Email is not searchable. When the region pin is not ZA-JHB, the client stops sending.
      </p>
      <p>
        A Chamber is a fresh ID with its own lock and a burn date. It is not a label on an ordinary chat. Screenshots are still possible. The app does not pretend otherwise.
      </p>
      <p className="note">Parents: BLEEP-TM-001 v0.1 and BLEEP-FC-001 v0.3. Those names are the parent documents, not a claim that this build has been audited.</p>
    </article>
  );
}

function Faq() {
  const items = [
    ["Do I need a phone number?", "No. Signup uses an email and a password. The key is still made on the device. The recovery code is not sent to the relay."],
    ["Can a stranger message me?", "They can send a request if they have your code. A thread opens after you accept."],
    ["Is the download the Play Store app?", "No. The file on the hash page is a debug-signed APK for this box, with its SHA-256 beside it. It is not a Play release and it is not an audit."],
    ["Can people screenshot a chat?", "Yes. View-once and Chambers tell you that capture is still possible. The app does not claim it can stop a photograph of the screen."],
    ["Where does a message live?", "On the devices in the conversation, and in a sealed history blob for the account until unlink. The relay cannot read that blob. Transport envelopes expire."],
    ["What is a Chamber?", "A separate ID, its own PIN, and a burn date. Unlocking Bleep does not unlock a Chamber."],
    ["Is there an iPhone app?", "No. There is no iOS build. Apple signing would need a Mac, and this tree does not have one."],
  ];
  return (
    <article className="prose">
      <p className="eyebrow">FAQ</p>
      <h1>Short answers</h1>
      {items.map(([q, a]) => (
        <section key={q}>
          <h2>{q}</h2>
          <p>{a}</p>
        </section>
      ))}
    </article>
  );
}

function Contact() {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState("");
  return (
    <article className="prose">
      <p className="eyebrow">Contact</p>
      <h1>Write to us</h1>
      <p>
        This form does not send anything to the relay. Submit opens a mail draft to {CONTACT_EMAIL}. The hash page is the place for the APK.
      </p>
      <form
        className="contact"
        onSubmit={(e) => {
          e.preventDefault();
          const body = `Name: ${name}\nEmail: ${email}\n\n${message}`;
          window.location.href = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent("Bleep")}&body=${encodeURIComponent(body)}`;
        }}
      >
        <label>Name<input value={name} onChange={(e) => setName(e.target.value)} required /></label>
        <label>Email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></label>
        <label>Message<textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={5} required /></label>
        <button className="primary" type="submit">Open mail draft</button>
      </form>
      <p><a href="/hashes.html">APK and hashes</a></p>
    </article>
  );
}

function Missing() {
  return (
    <article className="prose">
      <h1>That page is not here</h1>
      <p><a href="/" onClick={onSiteClick}>Back home</a></p>
    </article>
  );
}
