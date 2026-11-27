/* The home page is an interactive TV demo and made for large screens.
   On a phone a friendly note covers it. Other pages, such as the app download, stay as they are. */
(() => {
  "use strict";
  const phone = matchMedia(
    "(pointer: coarse) and (max-width: 767px), (pointer: coarse) and (max-height: 500px)",
  );
  const key = "openNavaPhoneHintClosed";
  try {
    if (sessionStorage.getItem(key)) return;
  } catch {}
  if (!phone.matches) return;

  const asked = new URL(location.href).searchParams.get("lang");
  const german = /^de\b/i.test(asked || navigator.language || "");
  const t = german
    ? {
        title: "Am Computer wird’s richtig schön.",
        text: "Unsere Demo zeigt Fernseher und App nebeneinander. Dafür braucht sie Platz: Öffne die Seite am Desktop.",
        send: "Link an meinen Computer senden",
        copied: "Link kopiert ✓",
        failed: "Tippe am Computer open-nava.com ein",
        app: "Android-App laden",
        stay: "Trotzdem ansehen",
      }
    : {
        title: "Made for the big screen.",
        text: "Our demo shows the TV and the app side by side. It needs some room: open this site on a desktop computer.",
        send: "Send the link to my computer",
        copied: "Link copied ✓",
        failed: "Type open-nava.com on your computer",
        app: "Get the Android app",
        stay: "View it anyway",
      };

  const host = document.createElement("div");
  host.id = "phone-hint";
  const view = host.attachShadow({ mode: "open" });
  view.innerHTML = `
    <style>
      .hint { position: fixed; inset: 0; z-index: 2147483000; overflow-y: auto; overscroll-behavior: contain;
        color: #f6f4fb; font-family: Manrope, system-ui, sans-serif; text-align: center;
        background: radial-gradient(120% 60% at 50% -8%, #4d3a8c 0%, transparent 70%),
          radial-gradient(90% 55% at 100% 100%, #153f4a 0%, transparent 70%), #12101c; }
      .inner { box-sizing: border-box; min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
        gap: 18px; padding: calc(28px + env(safe-area-inset-top)) 24px calc(28px + env(safe-area-inset-bottom)); }
      .name { font: 54px/1 Script, cursive; }
      .screen { width: min(74vw, 280px); margin: 6px 0 2px; }
      .glass { position: relative; aspect-ratio: 16 / 10; border: 7px solid #292a34; border-radius: 16px; overflow: hidden;
        background: url(./assets/alpine-lake.webp) center / cover;
        box-shadow: 0 0 0 1px #ffffff1f, 0 20px 50px #0008, 0 0 60px #a58cff59; }
      .cards { position: absolute; left: 8%; right: 8%; bottom: 14%; display: flex; gap: 6%; }
      .cards i { flex: 1; aspect-ratio: 5 / 4; border-radius: 8px; background: #ffffff2e; border: 1px solid #ffffff59; backdrop-filter: blur(3px); }
      .cards i:first-child { border: 2px solid #e4dcff; box-shadow: 0 0 16px #bfaaff; }
      .stand { width: 34%; height: 9px; margin: 0 auto; border-radius: 0 0 12px 12px; background: #24252e; }
      h2 { margin: 6px 0 0; max-width: 11em; font: 400 42px/1.08 Instrument, Georgia, serif; text-wrap: balance; }
      p { margin: 0; max-width: 21em; font-size: 16px; line-height: 1.55; color: #c3c0d6; text-wrap: balance; }
      .address { font-weight: 700; font-size: 18px; letter-spacing: .02em; color: #d9ccff;
        padding: 10px 20px; border: 1px dashed #a794e8; border-radius: 14px; user-select: all; -webkit-user-select: all; }
      button, a { font: inherit; cursor: pointer; -webkit-tap-highlight-color: transparent; }
      .send { width: min(100%, 320px); min-height: 56px; padding: 12px 20px; border: 0; border-radius: 28px;
        background: #cabaff; color: #1b1530; font-weight: 800; font-size: 16px; box-shadow: 0 0 34px #bfaaff66; }
      .more { display: flex; flex-wrap: wrap; justify-content: center; gap: 4px 26px; }
      .more > * { min-height: 44px; display: inline-flex; align-items: center; padding: 0; border: 0; background: none;
        color: #c3c0d6; font-size: 14px; font-weight: 650; text-decoration: underline; text-underline-offset: 4px; }
      :focus-visible { outline: 3px solid #fff; outline-offset: 3px; }
      @media (max-height: 500px) { .screen, .address { display: none; } .inner { gap: 8px; padding-top: 12px; padding-bottom: 12px; } .name { font-size: 36px; } h2 { margin: 0; font-size: 28px; max-width: none; } p { font-size: 14px; line-height: 1.4; max-width: 34em; } .send { min-height: 46px; } }
    </style>
    <div class="hint" role="dialog" aria-modal="true" aria-labelledby="title" aria-describedby="text">
      <div class="inner">
        <div class="name" translate="no">Opennava</div>
        <div class="screen" aria-hidden="true"><div class="glass"><div class="cards"><i></i><i></i><i></i></div></div><div class="stand"></div></div>
        <h2 id="title">${t.title}</h2>
        <p id="text">${t.text}</p>
        <div class="address" translate="no">open-nava.com</div>
        <button type="button" class="send">${t.send}</button>
        <div class="more">
          <a href="${new URL("./handy/", location.href).pathname}${asked ? `?lang=${encodeURIComponent(asked)}` : ""}">${t.app}</a>
          <button type="button" class="stay">${t.stay}</button>
        </div>
      </div>
    </div>`;

  const behind = [...document.body.children];
  const scroll = document.documentElement.style.overflow;
  document.body.append(host);
  behind.forEach((element) => (element.inert = true));
  document.documentElement.style.overflow = "hidden";

  const send = view.querySelector(".send");
  send.addEventListener("click", async () => {
    const url = "https://open-nava.com/";
    if (navigator.share) {
      try {
        await navigator.share({ title: "OpenNava", url });
        return;
      } catch (error) {
        if (error.name === "AbortError") return;
      }
    }
    try {
      await navigator.clipboard.writeText(url);
      send.textContent = t.copied;
    } catch {
      send.textContent = t.failed;
    }
  });

  const close = () => {
    host.remove();
    behind.forEach((element) => (element.inert = false));
    document.documentElement.style.overflow = scroll;
    phone.removeEventListener("change", onChange);
  };
  view.querySelector(".stay").addEventListener("click", () => {
    try {
      sessionStorage.setItem(key, "1");
    } catch {}
    close();
  });
  const onChange = () => {
    if (!phone.matches) close();
  };
  phone.addEventListener("change", onChange);
})();
