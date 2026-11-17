// The browser phone sends the same high-level choices as the Android companion.
const tvFrame = document.querySelector("#demo-tv");
const viewport = document.querySelector("#tv-viewport");
const phone = document.querySelector("#virtual-phone");
const phoneContent = document.querySelector("#companion-content");
const defaultChannels = [
  { id: "daserste", number: 1, name: "Das Erste" },
  { id: "zdf", number: 2, name: "ZDF" },
  { id: "arte", number: 3, name: "ARTE" },
];
let snapshot = { screen: "home", channels: defaultChannels };
let phonePage = "home";
let activeProfile;
let previousContext = "";

function send(action, value = "") {
  tvFrame.contentWindow?.postMessage(
    { source: "navatv-site", type: "action", action, value },
    location.origin,
  );
}
function node(tag, text, cls) {
  const el = document.createElement(tag);
  if (text !== null) el.textContent = text;
  if (cls) el.className = cls;
  return el;
}
function icon(id) {
  const el = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  el.setAttribute("class", "icon");
  el.setAttribute("aria-hidden", "true");
  const path = document.createElementNS(el.namespaceURI, "use");
  path.setAttribute("href", "#i-" + id);
  el.append(path);
  return el;
}
function control(label, callback, cls, key) {
  const el = node("button", label, cls);
  el.type = "button";
  if (key) el.dataset.choice = key;
  el.addEventListener("click", callback);
  return el;
}
function show(page) {
  phonePage = page;
  render();
}
function title(text) {
  phoneContent.append(node("h3", text));
}
function row(label, callback, key, detail) {
  const el = control("", callback, "phone-row", key);
  const group = node("span", null);
  group.append(node("b", label));
  if (detail) group.append(node("small", detail));
  el.append(group, node("span", "›", "row-arrow"));
  phoneContent.append(el);
}
function goTV(action, value, localPage) {
  if (localPage) show(localPage);
  send(action, value);
}
function render() {
  const focusKey = phoneContent.contains(document.activeElement)
    ? document.activeElement.dataset.choice
    : "";
  phoneContent.replaceChildren();
  const menuButton = phone.querySelector('[data-phone="menu"]');
  menuButton.textContent = phonePage === "menu" ? "×" : "☰";
  menuButton.setAttribute("aria-expanded", String(phonePage === "menu"));
  if (phonePage === "home") {
    title("What would you like to do?");
    const tiles = node("div", null, "phone-choices");
    for (const [view, label, glyph] of [
      ["live", "Live TV", "monitor"],
      ["libraries", "Media libraries", "book"],
      ["guide", "TV guide", "calendar"],
      ["recordings", "Recordings", "save"],
    ]) {
      const b = control(
        "",
        () =>
          view === "libraries"
            ? show("libraries")
            : goTV(
                view === "live" ? "open" : view,
                view === "live" ? "live" : "",
                view,
              ),
        "phone-tile",
        view,
      );
      b.append(icon(glyph), node("span", label));
      tiles.append(b);
    }
    phoneContent.append(tiles);
  } else if (phonePage === "menu") {
    title("Menu");
    row("Companion home", () => show("home"), "start", "Choose an activity");
    row(
      "Remote control",
      () => show("remote"),
      "remote",
      "Directional pad and OK",
    );
    row(
      "Media libraries",
      () => show("libraries"),
      "libraries",
      "ARD, ZDF and ARTE",
    );
  } else if (phonePage === "remote") {
    title("Remote control");
    const pad = node("div", null, "dpad");
    for (const [key, caption, name] of [
      ["ArrowUp", "↑", "Up"],
      ["ArrowLeft", "←", "Left"],
      ["Enter", "OK", "Select"],
      ["ArrowRight", "→", "Right"],
      ["ArrowDown", "↓", "Down"],
    ]) {
      const b = control(caption, () => send("key", key), "pad-key", key);
      b.dataset.key = key;
      b.setAttribute("aria-label", name + " on the TV");
      pad.append(b);
    }
    phoneContent.append(pad);
  } else if (phonePage === "libraries") {
    title("Media libraries");
    for (const code of ["ard", "zdf", "arte"])
      row(code.toUpperCase(), () => goTV("open", code, "catalog"), code);
  } else if (phonePage === "catalog" || phonePage === "player") {
    title(
      phonePage === "player"
        ? "Now playing"
        : (snapshot.provider || "Media library").toUpperCase(),
    );
    if (snapshot.title)
      phoneContent.append(node("p", snapshot.title, "phone-info"));
    for (const item of (snapshot.items || []).slice(0, 5)) {
      if (item?.id && item?.title)
        row(item.title, () => send("play", item.id), String(item.id));
    }
    if (phonePage === "player")
      row(
        snapshot.paused ? "Resume" : "Pause",
        () => send("playPause"),
        "pause",
      );
    row("Remote control", () => show("remote"), "remote");
    row("Choose a library", () => show("libraries"), "libraries");
  } else if (phonePage === "live" || phonePage === "guide") {
    title(phonePage === "live" ? "Live TV" : "TV guide");
    const channels = snapshot.channels?.length
      ? snapshot.channels
      : defaultChannels;
    if (phonePage === "live") {
      const station = channels.find((item) => item.id === snapshot.channel);
      if (station)
        phoneContent.append(
          node(
            "p",
            station.name + (snapshot.title ? " · " + snapshot.title : ""),
            "phone-info",
          ),
        );
      row(
        snapshot.paused ? "Resume" : "Pause",
        () => send("playPause"),
        "pause",
      );
    }
    if (phonePage === "guide")
      for (const item of (snapshot.items || []).slice(0, 3)) {
        if (item?.id && item?.title)
          row(item.title, () => send("play", item.id), String(item.id));
      }
    for (const item of channels)
      row(
        String(item.number || "").padStart(2, "0") + "  " + item.name,
        () => goTV("tune", item.id, "live"),
        "ch-" + item.id,
      );
  } else {
    title("Recordings");
    if (snapshot.items?.length) {
      for (const item of snapshot.items)
        if (item?.id && item?.title)
          row(item.title, () => send("play", item.id), String(item.id));
    } else
      phoneContent.append(
        node("p", "No recordings saved in this demo.", "phone-info"),
      );
    row("TV guide", () => goTV("guide", "", "guide"), "guide");
  }
  if (focusKey) {
    const prev = Array.from(
      phoneContent.querySelectorAll("[data-choice]"),
    ).find((el) => el.dataset.choice === focusKey);
    prev?.focus({ preventScroll: true });
  }
}
function selectProfile(profile) {
  if (activeProfile === profile) return;
  activeProfile = profile;
  previousContext = "";
  snapshot = { screen: "home", channels: defaultChannels };
  show("home");
  const href = "./demo/" + profile + "/index.html";
  if (tvFrame.getAttribute("src") !== href) tvFrame.src = href;
}
phone
  .querySelector('[data-phone="menu"]')
  .addEventListener("click", () =>
    show(phonePage === "menu" ? "home" : "menu"),
  );
phone
  .querySelector('[data-phone="remote"]')
  .addEventListener("click", () => show("remote"));
phone
  .querySelector('[data-phone="tv-home"]')
  .addEventListener("click", () => goTV("home", "", "home"));
phone
  .querySelector("[data-device-key]")
  .addEventListener("click", () => send("key", "Escape"));
document.addEventListener("navatv:profile", (event) =>
  selectProfile(event.detail),
);
selectProfile(document.body.dataset.profile);
window.addEventListener("message", (event) => {
  const data = event.data;
  if (
    event.origin !== location.origin ||
    event.source !== tvFrame.contentWindow ||
    !data ||
    data.source !== "navatv-demo"
  )
    return;
  if (data.type === "profile" && ["full", "senior"].includes(data.profile)) {
    showProfile(data.profile);
    return;
  }
  if (data.type !== "state" || !(typeof data.screen === "string")) return;
  snapshot = {
    ...data,
    channels: data.channels?.length ? data.channels : defaultChannels,
  };
  const context = [data.screen, data.provider, data.channel].join("/");
  let target = ["catalog", "live", "guide", "recordings", "player"].includes(
    data.screen,
  )
    ? data.screen
    : "home";
  if (
    context !== previousContext &&
    phonePage !== "remote" &&
    phonePage !== "menu"
  )
    phonePage = target;
  previousContext = context;
  render();
});
tvFrame.addEventListener("load", () => send("refresh"));
new ResizeObserver(() => {
  const scale = viewport.clientWidth / 1280;
  tvFrame.style.transform = "scale(" + scale + ")";
}).observe(viewport);
const expand = document.querySelector("#expand-tv");
expand.onclick = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await viewport.requestFullscreen();
  } catch {
    window.open(tvFrame.src, "_blank", "noopener");
  }
};
