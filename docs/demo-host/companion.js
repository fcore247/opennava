// Browser counterpart to the Android companion. The TV owns playback, search and recordings.
const tvFrame = document.querySelector("#demo-tv");
const viewport = document.querySelector("#tv-viewport");
const phone = document.querySelector("#virtual-phone");
const content = document.querySelector("#companion-content");
const menuButton = phone.querySelector('[data-phone="menu"]');
const connectionButton = phone.querySelector('[data-phone="connection"]');
const providers = ["ard", "zdf", "arte"];
const nativeScreens = [
  "home",
  "live",
  "guide",
  "catalog",
  "recordings",
  "player",
];
let state = { screen: "home", channels: [], items: [] };
let page = "home",
  menuReturn = "home",
  activeProfile,
  connected = true,
  linking = true;
let rendered = "",
  picker,
  pendingDelete,
  searchTimer,
  pendingSearch,
  pendingView;

function send(action, value = "") {
  if (!connected) {
    show("connection");
    return;
  }
  const destinations = {
    tune: "live",
    guide: "guide",
    recordings: "recordings",
    play: "player",
    home: "home",
  };
  if (action === "open") pendingView = value === "live" ? "live" : "catalog";
  else if (destinations[action]) pendingView = destinations[action];
  tvFrame.contentWindow?.postMessage(
    { source: "navatv-site", type: "action", action, value },
    location.origin,
  );
}
function el(tag, text, cls) {
  const item = document.createElement(tag);
  if (text != null) item.textContent = text;
  if (cls) item.className = cls;
  return item;
}
function icon(name) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg"),
    use = document.createElementNS(ns, "use");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
}
function button(label, action, key, cls = "phone-action") {
  const b = el("button", label, cls);
  b.type = "button";
  if (key) b.dataset.choice = key;
  b.addEventListener("click", action);
  return b;
}
function row(label, action, key, detail, target = content) {
  const b = button(null, action, key, "phone-row"),
    words = el("span");
  words.append(el("b", label));
  if (detail) words.append(el("small", detail));
  b.append(words, el("span", "›", "row-arrow"));
  target.append(b);
}
function heading(title) {
  content.append(el("h3", title));
}
function show(next) {
  clearTimeout(searchTimer);
  pendingSearch = null;
  rendered = "";
  content.scrollTop = 0;
  page = next;
  render();
}
function tvPage() {
  return nativeScreens.includes(state.screen) ? state.screen : "home";
}
function openTV(action, value = "") {
  send(action, value);
}
function home() {
  if (connected) send("home");
  show(connected ? "home" : "connection");
}
function switchLibrary(id) {
  clearTimeout(searchTimer);
  pendingSearch = null;
  openTV("open", id);
}
function openMenu() {
  if (page === "menu") {
    show(menuReturn);
    return;
  }
  menuReturn = page;
  show("menu");
}
function pageHome() {
  heading("What would you like to do?");
  const tiles = el("div", null, "phone-choices");
  for (const [id, label, sub, glyph, action] of [
    [
      "live",
      "Live TV",
      "Choose channel",
      "monitor",
      () => openTV("open", "live"),
    ],
    [
      "libraries",
      "Libraries",
      "ARD · ZDF · ARTE",
      "book",
      () => show("libraries"),
    ],
    [
      "guide",
      "TV guide",
      "What’s on and when",
      "calendar",
      () => openTV("guide"),
    ],
    [
      "recordings",
      "Recordings",
      "Recordings and reminders",
      "save",
      () => openTV("recordings"),
    ],
  ]) {
    const card = button(null, action, id, "phone-tile"),
      top = el("span", null, "tile-top"),
      body = el("span", null, "tile-words");
    top.append(icon(glyph), icon("out"));
    body.append(el("b", label), el("small", sub));
    card.append(top, body);
    tiles.append(card);
  }
  content.append(tiles);
}
function pageMenu() {
  heading("Menu");
  row(
    "Connection",
    () => show("connection"),
    "connection",
    connected ? "Demo TV" : "Choose a TV",
  );
  row("Manual remote", () => show("remote"), "remote");
  row("Software updates", () => show("updates"), "updates");
  row("Home", home, "start");
  row("Live TV", () => openTV("open", "live"), "live");
  row("TV guide", () => openTV("guide"), "guide");
  row("Recordings", () => openTV("recordings"), "recordings");
  content.append(el("h4", "Libraries"));
  for (const id of providers)
    row(id.toUpperCase(), () => switchLibrary(id), id);
}
function pageConnection() {
  heading("Connection");
  content.append(el("h4", "Available TVs"));
  row(
    "Demo TV",
    () => {
      if (connected) {
        show(tvPage());
        return;
      }
      connected = true;
      linking = true;
      show("connection");
      send("refresh");
    },
    "connect",
    connected
      ? linking
        ? "Connecting…"
        : "Connected"
      : "Connect to the virtual TV",
  );
  if (connected)
    content.append(
      button(
        "Disconnect",
        () => {
          connected = false;
          linking = false;
          show("connection");
        },
        "disconnect",
        "phone-action secondary",
      ),
    );
}
function pageRemote() {
  heading("Manual remote");
  const pad = el("div", null, "dpad");
  for (const [key, caption, name] of [
    ["ArrowUp", "↑", "Up"],
    ["ArrowLeft", "←", "Left"],
    ["Enter", "OK", "Select"],
    ["ArrowRight", "→", "Right"],
    ["ArrowDown", "↓", "Down"],
  ]) {
    const b = button(caption, () => send("key", key), key, "pad-key");
    b.dataset.key = key;
    b.setAttribute("aria-label", name + " on the TV");
    pad.append(b);
  }
  content.append(pad);
  if (state.screen === "live" || state.screen === "player")
    content.append(transport());
}
function pageLibraries() {
  heading("Libraries");
  for (const id of providers)
    row(
      id.toUpperCase(),
      () => switchLibrary(id),
      id,
      "Search and watch on TV",
    );
}
function sendQuery(input, provider, immediate = false) {
  clearTimeout(searchTimer);
  pendingSearch = { provider, query: input.value };
  const doSearch = () =>
    send("search", { provider, query: input.value.trim() });
  if (immediate) doSearch();
  else searchTimer = setTimeout(doSearch, 200);
}
function pageCatalog() {
  const provider = state.provider || "all",
    scoped = provider.toUpperCase();
  heading(provider === "all" ? "Media libraries" : scoped);
  const form = el("form", null, "phone-search");
  const input = el("input");
  input.type = "search";
  input.placeholder = "Search " + (provider === "all" ? "libraries" : scoped);
  input.setAttribute("aria-label", input.placeholder);
  input.value = state.query || "";
  input.autocomplete = "off";
  input.maxLength = 120;
  const submit = el("button", "Search", "phone-action");
  submit.type = "submit";
  form.append(input, submit);
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    sendQuery(input, provider, true);
  });
  input.addEventListener("input", () => sendQuery(input, provider));
  const keyboard = el("div", null, "phone-keyboard");
  keyboard.hidden = true;
  const keyToggle = button(
    "Keypad",
    () => {
      keyboard.hidden = !keyboard.hidden;
      keyToggle.setAttribute("aria-expanded", String(!keyboard.hidden));
    },
    "keypad",
    "phone-pill",
  );
  keyToggle.setAttribute("aria-expanded", "false");
  const bar = el("div", null, "phone-bar");
  bar.append(
    keyToggle,
    button("Libraries", () => show("libraries"), "libraries", "phone-pill"),
  );
  for (const letters of ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"]) {
    const line = el("div", null, "phone-key-row");
    for (const char of letters)
      line.append(
        button(char, () => insert(char.toLowerCase()), "key-" + char, "letter"),
      );
    keyboard.append(line);
  }
  const editing = el("div", null, "phone-key-row");
  editing.append(
    button("Space", () => insert(" "), "key-space", "letter wide"),
    button("⌫", () => insert(""), "key-backspace", "letter wide"),
  );
  keyboard.append(editing);
  function insert(chars) {
    let begin = input.selectionStart ?? input.value.length,
      end = input.selectionEnd ?? begin;
    if (!chars && begin === end) begin = Math.max(0, begin - 1);
    input.setRangeText(chars, begin, end, "end");
    sendQuery(input, provider);
  }
  content.append(form, bar, keyboard, el("div", null, "phone-results"));
  updateCatalog();
}
function updateCatalog() {
  const results = content.querySelector(".phone-results"),
    input = content.querySelector('input[type="search"]');
  if (!results || !input) return;
  if (
    !pendingSearch ||
    (pendingSearch.provider === state.provider &&
      pendingSearch.query.trim() === (state.query || ""))
  ) {
    if (state.query !== undefined && input.value.trim() !== state.query)
      input.value = state.query;
    pendingSearch = null;
  }
  results.replaceChildren(
    el("h4", input.value.trim() ? "Search results" : "Recommendations"),
  );
  if (!state.items?.length)
    results.append(el("p", "No matching programmes.", "phone-info"));
  for (const item of state.items || [])
    row(
      item.title,
      () => openTV("play", item.id),
      String(item.id),
      "Watch on TV",
      results,
    );
}
function transport() {
  const controls = el("div", null, "phone-bar");
  controls.append(
    button(state.paused ? "Resume" : "Pause", () => send("playPause"), "pause"),
  );
  if (state.seekable) {
    for (const [delta, label] of [
      [-10, "−10 s"],
      [10, "+10 s"],
    ]) {
      const seek = button(
        label,
        () => send("seek", delta),
        "seek-" + delta,
        "phone-action secondary",
      );
      seek.setAttribute(
        "aria-label",
        delta < 0 ? "Back ten seconds" : "Forward ten seconds",
      );
      controls.append(seek);
    }
  }
  if (state.programmeId) {
    const saved = (state.savedIds || []).includes(state.programmeId);
    controls.append(
      button(
        saved ? "Remove saved" : "Save programme",
        () => send(saved ? "delete" : "save", state.programmeId),
        saved ? "unsave" : "save",
        "phone-action secondary",
      ),
    );
  }
  return controls;
}
function pagePlayer() {
  heading("Now playing");
  const card = el("div", null, "phone-onair");
  card.append(
    el("small", (state.provider || "Media library").toUpperCase()),
    el("b", state.title),
  );
  content.append(card, transport());
  row("Back to TV", () => send("key", "Escape"), "back");
  row("Recordings", () => openTV("recordings"), "recordings");
}
function pageLive() {
  const live = page === "live",
    station = state.channels.find((c) => c.id === state.channel);
  heading(live ? "Live TV" : "TV guide");
  content.append(
    button(
      "Choose channel",
      () => openPicker(!live),
      "wheel",
      "phone-action secondary choose-channel",
    ),
  );
  const current = (state.schedule || []).find((it) => it.slot === "now");
  const card = el("div", null, "phone-onair");
  card.append(
    el(
      "small",
      station
        ? String(station.number).padStart(2, "0") + " · " + station.name
        : "Live TV",
    ),
  );
  if (current?.title || state.title)
    card.append(el("b", current?.title || state.title));
  content.append(card);
  if (live) content.append(transport());
  else if (station)
    content.append(
      button("Watch channel", () => send("tune", station.id), "tune"),
    );
  const timeline = el("div", null, "phone-timeline");
  timeline.append(el("h4", "Programme guide"));
  for (const entry of state.schedule || []) {
    const slot =
      { previous: "Previously", now: "On now", next: "Up next" }[entry.slot] ||
      entry.slot;
    row(
      entry.title,
      () =>
        entry.slot === "now" && station
          ? send("tune", station.id)
          : send("play", entry.id),
      "slot-" + entry.slot,
      [slot, entry.time].filter(Boolean).join(" · "),
      timeline,
    );
  }
  content.append(timeline);
  if (live) row("TV guide", () => openTV("guide", state.channel), "guide");
}
function groupOf(c) {
  return (
    c.regional ?? ["ndr", "wdr", "br", "swr", "mdr", "rbb", "hr"].includes(c.id)
  );
}
function openPicker(guide) {
  const c =
    state.channels.find((c) => c.id === state.channel) || state.channels[0];
  if (!c) return;
  picker = { guide, regional: groupOf(c), id: c.id, tabs: [null, null] };
  show("picker");
}
function pagePicker() {
  heading("Choose channel");
  const tabs = el("div", null, "channel-tabs");
  tabs.setAttribute("aria-label", "Channel groups");
  for (const [group, label] of [
    [false, "Main channels"],
    [true, "Regional"],
  ]) {
    if (!state.channels.some((c) => groupOf(c) === group)) continue;
    const tab = button(
      label,
      () => {
        picker.tabs[Number(picker.regional)] = picker.id;
        picker.regional = group;
        picker.id = picker.tabs[Number(group)];
        render();
      },
      "group-" + group,
      "phone-pill",
    );
    tab.setAttribute("aria-pressed", String(picker.regional === group));
    tabs.append(tab);
  }
  const list = state.channels.filter((c) => groupOf(c) === picker.regional);
  if (!list.some((c) => c.id === picker.id)) picker.id = list[0]?.id;
  const wrap = el("div", null, "wheel-wrap"),
    wheel = el("div", null, "channel-wheel");
  wheel.setAttribute("role", "listbox");
  wheel.setAttribute("aria-label", "Channel wheel");
  wheel.tabIndex = 0;
  const opts = list.map((c) => {
    const opt = el("div", null, "wheel-option");
    opt.id = "wheel-ch-" + c.id;
    opt.dataset.channel = c.id;
    opt.setAttribute("role", "option");
    opt.append(el("small", String(c.number).padStart(2, "0")), el("b", c.name));
    wheel.append(opt);
    return opt;
  });
  const confirm = button(
    picker.guide ? "View schedule" : "Watch channel",
    () => send(picker.guide ? "guide" : "tune", picker.id),
    "confirm-channel",
  );
  const close = button(
    "Cancel",
    () => show(tvPage()),
    "cancel-wheel",
    "phone-action secondary",
  );
  function select(index, scroll) {
    const c = list[index];
    if (!c) return;
    picker.id = c.id;
    for (let i = 0; i < opts.length; i++)
      opts[i].setAttribute("aria-selected", String(i === index));
    wheel.setAttribute("aria-activedescendant", opts[index].id);
    confirm.setAttribute(
      "aria-label",
      (picker.guide ? "View schedule for " : "Watch ") + c.name,
    );
    if (scroll) wheel.scrollTop = index * opts[index].offsetHeight;
  }
  opts.forEach((option, i) =>
    option.addEventListener("click", () => select(i, true)),
  );
  wheel.addEventListener(
    "scroll",
    () => {
      if (wheel.isConnected)
        select(
          Math.max(
            0,
            Math.min(
              list.length - 1,
              Math.round(wheel.scrollTop / (opts[0]?.offsetHeight || 1)),
            ),
          ),
          false,
        );
    },
    { passive: true },
  );
  wheel.addEventListener("keydown", (e) => {
    let i = list.findIndex((c) => c.id === picker.id);
    if (e.key === "ArrowDown") i++;
    else if (e.key === "ArrowUp") i--;
    else if (e.key === "Home") i = 0;
    else if (e.key === "End") i = list.length - 1;
    else if (e.key === "Enter") {
      e.preventDefault();
      confirm.click();
      return;
    } else return;
    e.preventDefault();
    select(Math.max(0, Math.min(list.length - 1, i)), true);
  });
  wrap.append(wheel);
  const actions = el("div", null, "phone-bar");
  actions.append(confirm, close);
  content.append(tabs, wrap, actions);
  select(
    list.findIndex((c) => c.id === picker.id),
    true,
  );
}
function pageRecordings() {
  heading("Recordings");
  if (!state.items?.length) {
    content.append(el("p", "No saved programmes yet.", "phone-info"));
    row("Open TV guide", () => openTV("guide"), "guide");
    return;
  }
  content.append(el("h4", "Saved programmes"));
  for (const item of state.items) {
    const slot = el("div", null, "saved-row");
    row(item.title, () => send("play", item.id), item.id, "Watch on TV", slot);
    const del = button(
      "Delete",
      () => {
        pendingDelete = item;
        show("delete");
      },
      "delete-" + item.id,
      "phone-pill",
    );
    del.setAttribute("aria-label", "Delete " + item.title);
    slot.append(del);
    content.append(slot);
  }
}
function pageDelete() {
  heading("Delete recording?");
  content.append(
    el("p", "Remove “" + pendingDelete.title + "” from the TV?", "phone-info"),
  );
  content.append(
    button(
      "Delete recording",
      () => {
        const id = pendingDelete.id;
        pendingDelete = null;
        show("recordings");
        send("delete", id);
      },
      "confirm-delete",
      "phone-action danger",
    ),
    button(
      "Keep recording",
      () => {
        pendingDelete = null;
        show("recordings");
      },
      "cancel-delete",
      "phone-action secondary",
    ),
  );
}
function render() {
  const key = page + ":" + (page === "catalog" ? state.provider : "");
  if (page === "catalog" && key === rendered) {
    updateCatalog();
    return;
  }
  const focusKey = content.contains(document.activeElement)
    ? document.activeElement.dataset.choice
    : "";
  const scroll = key === rendered ? content.scrollTop : 0;
  content.replaceChildren();
  rendered = key;
  phone.dataset.page = page;
  menuButton.textContent = page === "menu" ? "×" : "☰";
  menuButton.setAttribute("aria-expanded", String(page === "menu"));
  connectionButton.querySelector("span").textContent = connected
    ? linking
      ? "Linking"
      : "Demo TV"
    : "Connect";
  phone.dataset.connected = String(connected);
  phone.dataset.ready = String(!linking);
  switch (page) {
    case "home":
      pageHome();
      break;
    case "menu":
      pageMenu();
      break;
    case "connection":
      pageConnection();
      break;
    case "remote":
      pageRemote();
      break;
    case "libraries":
      pageLibraries();
      break;
    case "catalog":
      pageCatalog();
      break;
    case "live":
    case "guide":
      pageLive();
      break;
    case "picker":
      pagePicker();
      break;
    case "player":
      pagePlayer();
      break;
    case "recordings":
      pageRecordings();
      break;
    case "delete":
      pageDelete();
      break;
    case "updates":
      heading("Software updates");
      row(
        "OpenNava releases",
        () =>
          window.open(
            "https://github.com/fcore247/opennava/releases",
            "_blank",
            "noopener",
          ),
        "releases",
        "TV and Android companion",
      );
      break;
  }
  content.scrollTop = scroll;
  if (focusKey)
    Array.from(content.querySelectorAll("[data-choice]"))
      .find((b) => b.dataset.choice === focusKey)
      ?.focus({ preventScroll: true });
}
function selectProfile(profile) {
  if (activeProfile === profile) return;
  activeProfile = profile;
  linking = connected;
  state = { screen: "home", channels: [], items: [] };
  show(connected ? "home" : "connection");
  const href = "./demo/" + profile + "/index.html";
  if (tvFrame.getAttribute("src") !== href) tvFrame.src = href;
}
menuButton.addEventListener("click", openMenu);
connectionButton.addEventListener("click", () => show("connection"));
phone
  .querySelector('[data-phone="remote"]')
  .addEventListener("click", () => show(connected ? "remote" : "connection"));
phone.querySelector('[data-phone="tv-home"]').addEventListener("click", home);
phone
  .querySelector("[data-device-key]")
  .addEventListener("click", () => send("key", "Escape"));
document.querySelector("#try-companion").addEventListener("click", () => {
  show(connected ? tvPage() : "connection");
  phone.focus({ preventScroll: true });
});
document.addEventListener("navatv:profile", (e) => selectProfile(e.detail));
selectProfile(document.body.dataset.profile);
window.addEventListener("message", (e) => {
  const x = e.data;
  if (
    e.origin !== location.origin ||
    e.source !== tvFrame.contentWindow ||
    x?.source !== "navatv-demo"
  )
    return;
  if (x.type === "profile" && ["full", "senior"].includes(x.profile)) {
    showProfile(x.profile);
    return;
  }
  if (
    !connected ||
    x.type !== "state" ||
    typeof x.screen !== "string" ||
    x.profile !== activeProfile
  )
    return;
  const changed =
      x.screen !== state.screen ||
      x.provider !== state.provider ||
      x.channel !== state.channel,
    first = linking;
  state = { ...x, items: x.items || [], channels: x.channels || [] };
  linking = false;
  if (pendingView === x.screen) {
    pendingView = null;
    show(tvPage());
    return;
  }
  // Local full-screen tools stay usable while their own TV commands change focus.
  if (first || (changed && !["menu", "remote", "connection"].includes(page))) {
    show(tvPage());
    return;
  }
  if (page === "picker" || page === "delete" || page === "updates") return;
  render();
});
tvFrame.addEventListener("load", () => {
  if (connected) send("refresh");
});
new ResizeObserver(() => {
  tvFrame.style.transform = "scale(" + viewport.clientWidth / 1280 + ")";
}).observe(viewport);
document.querySelector("#expand-tv").onclick = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await viewport.requestFullscreen();
  } catch {
    window.open(tvFrame.src, "_blank", "noopener");
  }
};
