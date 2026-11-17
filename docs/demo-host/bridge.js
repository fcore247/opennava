// Browser implementation of the TV WebView's native device API. All media is local demo content.
(() => {
  "use strict";
  const profile = location.pathname.includes("/senior/") ? "senior" : "full";
  const channels = window.openNavaStations;
  const programmes = [
    [
      "alpine",
      "Above the clouds",
      "ard",
      "mountains",
      "ard-cover",
      "A journey through the Alps.",
    ],
    [
      "forest",
      "Inside the forest",
      "zdf",
      "forest",
      "zdf-cover",
      "Light and life beneath the canopy.",
    ],
    [
      "ocean",
      "The blue hour",
      "arte",
      "ocean",
      "arte-cover",
      "An evening on the Atlantic.",
    ],
    [
      "earth",
      "Earth from orbit",
      "zdf",
      "earth",
      "suche-heute",
      "A new perspective on our planet.",
    ],
    [
      "hills",
      "First light",
      "ard",
      "hills",
      "suche-tagesschau",
      "The hills before sunrise.",
    ],
    [
      "wild",
      "Wild horizons",
      "arte",
      "mountains",
      "suche-stadt-land-kunst",
      "Exploring the mountain wilderness.",
    ],
    [
      "tagesschau",
      "Tagesschau",
      "ard",
      "mountains",
      "suche-tagesschau",
      "Sample news listing from the ARD library.",
    ],
    [
      "heute",
      "heute journal",
      "zdf",
      "hills",
      "suche-heute",
      "Sample news listing from the ZDF library.",
    ],
    [
      "stadt",
      "Stadt Land Kunst",
      "arte",
      "hills",
      "suche-stadt-land-kunst",
      "Sample culture listing from the ARTE library.",
    ],
    [
      "OpenNava001",
      "The changing tide",
      "youtube",
      "ocean",
      "arte-cover",
      "A coastal short film.",
    ],
    [
      "OpenNava002",
      "A walk in the woods",
      "youtube",
      "forest",
      "ard-cover",
      "A short nature study.",
    ],
  ].map(([id, title, provider, art, seniorArt, subtitle]) => ({
    id,
    title,
    provider,
    art,
    subtitle,
    author: provider === "youtube" ? "OpenNava" : provider.toUpperCase(),
    channel: provider.toUpperCase(),
    duration: 12,
    thumbnail: "assets/" + (profile === "senior" ? seniorArt : art) + ".jpg",
  }));
  const records = new Set();
  try {
    for (const id of JSON.parse(
      sessionStorage.getItem("openNavaDemoSaved") || "[]",
    ))
      if (programmes.some((p) => p.id === id)) records.add(id);
  } catch {}
  let station = channels[0],
    active = null,
    layer = null,
    previousFocus = null,
    lastState = "",
    seniorProvider = "",
    fullFilter = "",
    openingLibrary = false;
  const q = (sel) => document.querySelector(sel);
  const find = (id) => programmes.find((p) => p.id === id);
  const media = () => layer?.querySelector("video");
  const channelItems = (provider) =>
    programmes.filter((p) => p.provider === provider);
  let backStack = [];
  const playingNow = () =>
    channelItems(station.provider)[
      (station.number - 1) % channelItems(station.provider).length
    ];
  const post = (type, data) => {
    if (parent !== window)
      parent.postMessage(
        { source: "navatv-demo", type, ...data },
        location.origin,
      );
  };
  function changeProfile(next) {
    if (parent === window) location.assign("../" + next + "/index.html");
    else post("profile", { profile: next });
    return true;
  }
  const btn = (title, action) => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = title;
    b.onclick = action;
    return b;
  };
  const scheduleTime = new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
  function schedule() {
    const pool = channelItems(station.provider);
    const now = new Date(),
      start = new Date(now);
    start.setMinutes(Math.floor(now.getMinutes() / 30) * 30, 0, 0);
    const index = pool.indexOf(playingNow());
    return ["previous", "now", "next"].map((slot, i) => {
      const p = pool[(index + pool.length + i - 1) % pool.length];
      return {
        id: p.id,
        title: p.title,
        slot,
        time: scheduleTime.format(new Date(+start + (i - 1) * 1800000)),
      };
    });
  }
  function snapshot() {
    let screen = "home",
      provider = "",
      title = "",
      items = [],
      query = "";
    if (active) {
      ({ screen, provider = "", title = "", items = [] } = active);
    } else if (profile === "senior" && q("#station") && !q("#station").hidden) {
      screen = "catalog";
      provider = seniorProvider;
      title = q("#station-title").textContent;
      const field = q("#station.searching #search-text:not(.placeholder)");
      query = field?.textContent || "";
      const controls = q("#station.typing")
        ? "#preview-list [data-preview-play]"
        : "#results [data-play]";
      items = [...document.querySelectorAll(controls)]
        .map((b) => find(b.dataset.previewPlay || b.dataset.play))
        .filter(Boolean);
    } else if (q("#nava-tube")) {
      const context = window.NavaTube?.getSearchContext() || {};
      screen = "catalog";
      provider =
        context.provider === "youtube"
          ? "youtube"
          : fullFilter || context.channel || "all";
      title = provider === "all" ? "Media library" : provider.toUpperCase();
      query = q("#tube-query:not(.tube-placeholder)")?.textContent || "";
      items = [...document.querySelectorAll("[data-yt-play]")]
        .map((b) => find(b.dataset.ytPlay))
        .filter(Boolean);
    }
    const showingChannel = screen === "live" || screen === "guide";
    return {
      screen,
      provider,
      title,
      profile,
      query,
      items: items.map(({ id, title }) => ({ id, title })),
      channels,
      channel: showingChannel ? station.id : "",
      schedule: showingChannel ? schedule() : [],
      programmeId: active?.programmeId || "",
      savedIds: [...records],
      paused: Boolean(media()?.paused),
      seekable: Boolean(media()?.seekable.length),
    };
  }
  function sendState(force = false) {
    const data = snapshot(),
      key = JSON.stringify(data);
    if (force || key !== lastState) {
      lastState = key;
      post("state", data);
    }
  }
  function catalogue(id, raw, youtube) {
    let query;
    try {
      query = JSON.parse(raw);
    } catch {
      return false;
    }
    let provider = youtube ? "youtube" : query.channel;
    if (
      !youtube &&
      fullFilter === "arte" &&
      provider === "all" &&
      q("#nava-tube")
    )
      provider = "arte";
    let items =
      provider && provider !== "all"
        ? channelItems(provider)
        : programmes.filter((p) => p.provider !== "youtube");
    if (query.method === "home" && provider === "all") {
      // The home hero uses one of the original sample films. News listings remain searchable.
      items = items.filter(
        (p) => !["tagesschau", "heute", "stadt"].includes(p.id),
      );
    }
    if (query.method === "live") {
      const live =
        provider && provider !== "all"
          ? channels.filter((c) => c.provider === provider).slice(0, 1)
          : channels;
      items = live.map((c) => ({
        id: "live:" + c.id,
        title: c.name,
        author: c.name,
        provider: c.provider,
        channel: c.provider.toUpperCase(),
        isLive: true,
      }));
    } else if (query.method === "search" && query.query) {
      const term = String(query.query).trim().toLocaleLowerCase();
      items = items.filter((p) =>
        (p.title + " " + p.subtitle + " " + p.author)
          .toLowerCase()
          .includes(term),
      );
    }
    setTimeout(() => {
      window[youtube ? "navaYouTubeResult" : "navaMediathekResult"]?.(
        id,
        JSON.stringify(
          provider === "arte" && profile === "full"
            ? { items, groups: [{ title: "ARTE", items }] }
            : { items },
        ),
      );
      sendState();
    }, 0);
    return true;
  }
  function closeLayer(resume = true) {
    if (!layer) return;
    media()?.pause();
    layer.remove();
    layer = null;
    active = null;
    if (resume) {
      window.navaNativeResume?.();
      if (previousFocus?.isConnected)
        previousFocus.focus({ preventScroll: true });
    }
    previousFocus = null;
  }
  function makeLayer(screen, title, provider, items = []) {
    if (
      active &&
      active.screen !== screen &&
      ["live", "guide", "recordings"].includes(active.screen)
    ) {
      if (backStack.at(-1)?.screen !== active.screen)
        backStack.push({ screen: active.screen, channel: station.id });
    }
    const focus = previousFocus || document.activeElement;
    closeLayer(false);
    previousFocus = focus;
    active = { screen, title, provider, items };
    layer = document.createElement("dialog");
    layer.className = "demo-native";
    layer.setAttribute("aria-label", title);
    layer.addEventListener("cancel", (e) => {
      e.preventDefault();
      back();
    });
    document.body.append(layer);
    layer.showModal();
    return layer;
  }
  function heading(el, eyebrow, title) {
    const section = document.createElement("header"),
      label = document.createElement("small"),
      h = document.createElement("h1");
    label.textContent = eyebrow;
    h.textContent = title;
    section.append(label, h);
    el.append(section);
  }
  function back() {
    const previous = backStack.pop();
    closeLayer(!previous);
    if (previous?.screen === "live") tune(previous.channel);
    else if (previous?.screen === "guide") guide(previous.channel);
    else if (previous?.screen === "recordings") recordings();
    sendState();
  }
  function home() {
    backStack = [];
    closeLayer(false);
    fullFilter = "";
    seniorProvider = "";
    window.dismissSeniorStartup?.();
    window.navaNativeHome?.();
    sendState(true);
  }
  function changeSaved(id, saved) {
    if (!find(id)) return;
    if (saved) records.add(id);
    else records.delete(id);
    try {
      sessionStorage.setItem("openNavaDemoSaved", JSON.stringify([...records]));
    } catch {}
    if (active?.screen === "recordings") recordings();
    const b = layer?.querySelector("[data-demo-save]");
    if (b && active.programmeId === id)
      b.textContent = saved ? "Remove from recordings" : "Save to recordings";
    sendState(true);
  }
  function saveButton(p) {
    const b = btn(
      records.has(p.id) ? "Remove from recordings" : "Save to recordings",
      () => changeSaved(p.id, !records.has(p.id)),
    );
    b.dataset.demoSave = p.id;
    return b;
  }
  function play(p, live = false) {
    if (!p) return false;
    const el = makeLayer(
      live ? "live" : "player",
      p.title,
      live ? station.provider : p.provider,
    );
    active.programmeId = p.id;
    const video = document.createElement("video"),
      bar = document.createElement("div");
    video.muted = true;
    video.playsInline = true;
    video.loop = true;
    video.autoplay = true;
    video.poster = "../full/assets/" + p.art + ".jpg";
    video.src = "../full/assets/" + p.art + ".mp4";
    bar.className = "demo-controls";
    el.append(video);
    heading(
      el,
      live
        ? "CHANNEL " +
            String(station.number).padStart(2, "0") +
            "  ·  " +
            station.name.toUpperCase()
        : "MEDIA LIBRARY  ·  " + p.author,
      p.title,
    );
    const badge = document.createElement("span");
    badge.className = "sample-footage";
    badge.textContent = "Sample footage";
    el.append(badge);
    const toggle = btn("Pause", () => togglePlay());
    const indicate = () => {
      toggle.textContent = video.paused ? "Play" : "Pause";
      sendState();
    };
    video.addEventListener("loadedmetadata", indicate);
    video.addEventListener("canplay", indicate);
    video.addEventListener("play", indicate);
    video.addEventListener("pause", indicate);
    video.addEventListener("error", () => {
      toggle.textContent = "Retry preview";
      toggle.onclick = () => {
        video.load();
        video.play().catch(indicate);
      };
    });
    bar.append(toggle, saveButton(p));
    if (live)
      bar.append(
        btn("TV guide", guide),
        btn("Media library", () => library(station.provider)),
      );
    bar.append(btn("Back", back));
    el.append(bar);
    toggle.focus();
    video.play().catch(indicate);
    sendState(true);
    return true;
  }
  function togglePlay() {
    const v = media();
    if (!v) return;
    if (v.paused) v.play().catch(() => sendState(true));
    else v.pause();
  }
  function seek(delta) {
    const v = media();
    if (![-10, 10].includes(delta) || !v?.seekable.length) return;
    // Seek only inside the range the browser can actually play.
    const ranges = v.seekable;
    const start = ranges.start(0),
      end = ranges.end(ranges.length - 1);
    v.currentTime = Math.max(
      start,
      Math.min(Math.max(start, end - 0.1), v.currentTime + delta),
    );
    sendState(true);
  }
  function tune(id) {
    const next = channels.find((c) => c.id === id);
    if (next) {
      station = next;
      play(playingNow(), true);
    }
  }
  function programmeList(screen, title, entries) {
    const el = makeLayer(screen, title, "", entries);
    el.classList.add("demo-list");
    heading(
      el,
      screen === "guide"
        ? station.name.toUpperCase() + "  ·  SAMPLE SCHEDULE"
        : "YOUR LIBRARY",
      title,
    );
    const list = document.createElement("div");
    list.className = "demo-entries";
    el.append(list);
    for (const [i, p] of entries.entries()) {
      const row = btn(
        (screen === "guide"
          ? ["Previously   ", "Now   ", "Next   "][i] || ""
          : "") + p.title,
        () => play(p),
      );
      list.append(row);
    }
    if (!entries.length) {
      const hint = document.createElement("p");
      hint.textContent = "Save a show in the player to find it here.";
      list.append(hint);
    }
    list.append(btn("Back", back));
    list.querySelector("button")?.focus();
    sendState(true);
  }
  function guide(id) {
    const next = channels.find((c) => c.id === id);
    if (next) station = next;
    programmeList(
      "guide",
      "TV guide",
      schedule().map((entry) => find(entry.id)),
    );
  }
  function recordings() {
    programmeList(
      "recordings",
      "Recordings",
      [...records].map(find).filter(Boolean),
    );
  }
  function library(provider) {
    if (!["ard", "zdf", "arte"].includes(provider)) return;
    backStack = [];
    closeLayer(false);
    window.dismissSeniorStartup?.();
    if (profile === "senior") {
      if (q("#home")?.hidden) window.navaNativeHome?.();
      q('[data-open="' + provider + '"]')?.click();
    } else {
      fullFilter = provider === "arte" ? "arte" : "";
      if (provider === "arte") {
        openingLibrary = true;
        try {
          q('.header [data-view="home"]')?.click();
          q('.header [data-view="mediathek"]')?.click();
          q('[data-yt-broadcaster="all"]')?.click();
          q('[data-yt-topic="0"]')?.click();
        } finally {
          openingLibrary = false;
        }
      } else window.NavaTube?.showBroadcaster(provider);
    }
    sendState();
  }
  let restoreFocusFrame = 0;
  function searchFromPhone(command) {
    // Android has separate focus on the two devices. Keep the software keyboard on the phone
    // while the real TV launcher restores its own remote focus during the search render.
    if (
      parent.document.activeElement?.matches(
        '#virtual-phone input[type="search"]',
      )
    ) {
      cancelAnimationFrame(restoreFocusFrame);
      document.body.inert = true;
      restoreFocusFrame = requestAnimationFrame(() => {
        restoreFocusFrame = requestAnimationFrame(() => {
          document.body.inert = false;
        });
      });
    }
    search(command);
  }
  function search(command) {
    if (!command || typeof command.query !== "string") return;
    const provider = command.provider,
      query = command.query.trim().slice(0, 160);
    if (!["ard", "zdf", "arte", "youtube", "all"].includes(provider)) return;
    backStack = [];
    closeLayer(false);
    window.dismissSeniorStartup?.();
    if (profile === "senior") {
      if (!["ard", "zdf", "arte"].includes(provider)) return;
      window.navaCompanion?.({ provider, operation: "search", value: query });
    } else {
      if (["all", "youtube"].includes(provider)) {
        fullFilter = "";
        q(
          '.header [data-view="' +
            (provider === "youtube" ? "youtube" : "mediathek") +
            '"]',
        )?.click();
        if (provider === "all") q('[data-yt-broadcaster="all"]')?.click();
      } else library(provider);
      if (query) window.NavaTube?.search(query);
      else
        window.NavaTube?.focusSearch({
          provider: provider === "youtube" ? "youtube" : "mediathek",
        });
    }
    sendState();
  }
  function playId(id) {
    if (typeof id !== "string") return false;
    if (id.startsWith("live:")) {
      tune(id.slice(5));
      return true;
    }
    return play(find(id));
  }
  function note(title, text) {
    const el = makeLayer("home", title, "");
    el.classList.add("demo-list");
    heading(el, "DEVICE PREVIEW", title);
    const box = document.createElement("div"),
      p = document.createElement("p");
    box.className = "demo-entries";
    p.textContent = text;
    box.append(p, btn("Back", back));
    el.append(box);
    box.querySelector("button").focus();
    sendState(true);
    return true;
  }
  function key(value) {
    if (!layer) {
      window.navaRemote?.(value);
      return;
    }
    if (value === "Escape") {
      back();
      return;
    }
    if (value === "Enter") {
      document.activeElement?.click();
      return;
    }
    if (active.screen === "live" && ["ArrowUp", "ArrowDown"].includes(value)) {
      const i = channels.indexOf(station) + (value === "ArrowUp" ? 1 : -1);
      tune(channels[(i + channels.length) % channels.length].id);
      return;
    }
    const buttons = [...layer.querySelectorAll("button")],
      i = buttons.indexOf(document.activeElement);
    const step = ["ArrowDown", "ArrowRight"].includes(value) ? 1 : -1;
    buttons[Math.max(0, Math.min(buttons.length - 1, i + step))]?.focus();
  }
  const keys = [
    "ArrowUp",
    "ArrowDown",
    "ArrowLeft",
    "ArrowRight",
    "Enter",
    "Escape",
  ];
  window.NavaTV = Object.freeze({
    getApps: () =>
      JSON.stringify([
        { packageName: "de.swr.avp.ard.tv", name: "ARD" },
        { packageName: "com.zdf.android.mediathek", name: "ZDF" },
        { packageName: "tv.arte.plus7", name: "ARTE" },
        { packageName: "com.google.android.youtube.tv", name: "YouTube" },
      ]),
    mediathekRequest: (id, s) => catalogue(id, s, false),
    youtubeRequest: (id, s) => catalogue(id, s, true),
    reportCatalogue: (provider) => {
      seniorProvider = provider;
      queueMicrotask(sendState);
    },
    playMediathek: playId,
    playYouTube: playId,
    playYouTubeCard: playId,
    openTelevision: (value) => {
      if (value === "programme") guide();
      else if (value === "recordings") recordings();
      else tune(station.id);
      return true;
    },
    launchApp: (pkg) => {
      if (pkg === "tv.arte.plus7") {
        library("arte");
        return true;
      }
      return false;
    },
    openOfficialMediathek: (name) =>
      note(
        name.toUpperCase(),
        "On your TV this opens the broadcaster’s installed app.",
      ),
    openUpdates: () =>
      note(
        "Software updates",
        "Your installed app checks the OpenNava release channel for signed updates.",
      ),
    openSettings: () =>
      note("Device settings", "On your TV this opens Android settings."),
    showPhoneLink: () =>
      note(
        "Pair a phone",
        "The virtual phone beside this TV is already connected.",
      ),
    hasPersonalView: () => true,
    isLauncherEnabled: () => true,
    setHomeShortcuts: () => {},
    setHelpOpen: () => {},
    setGuidePractice: () => {},
    openVisitorMode: () => changeProfile("full"),
    returnToSenior: () => changeProfile("senior"),
    exitApp: home,
  });
  document.addEventListener(
    "keydown",
    (e) => {
      if (layer && keys.includes(e.key)) {
        e.preventDefault();
        e.stopImmediatePropagation();
        key(e.key);
      }
    },
    true,
  );
  document.addEventListener(
    "click",
    (e) => {
      const filter = e.target.closest(
        '[data-yt-broadcaster],[data-view="home"],[data-view="youtube"]',
      );
      if (!openingLibrary && fullFilter && filter) {
        fullFilter = "";
        if (filter.dataset.ytBroadcaster === "all")
          q('[data-yt-topic="0"]')?.click();
      }
      requestAnimationFrame(() => sendState());
    },
    true,
  );
  window.addEventListener("message", (e) => {
    const m = e.data;
    if (
      parent === window ||
      e.source !== parent ||
      e.origin !== location.origin ||
      m?.source !== "navatv-site" ||
      m.type !== "action"
    )
      return;
    switch (m.action) {
      case "key":
        if (keys.includes(m.value)) key(m.value);
        break;
      case "open":
        if (m.value === "live") tune(station.id);
        else library(m.value);
        break;
      case "home":
        home();
        break;
      case "guide":
        guide(m.value);
        break;
      case "recordings":
        recordings();
        break;
      case "playPause":
        togglePlay();
        break;
      case "seek":
        seek(m.value);
        break;
      case "tune":
        tune(m.value);
        break;
      case "play":
        playId(m.value);
        break;
      case "search":
        searchFromPhone(m.value);
        break;
      case "save":
        changeSaved(m.value, true);
        break;
      case "delete":
        changeSaved(m.value, false);
        break;
      case "refresh":
        sendState(true);
        break;
    }
    requestAnimationFrame(() => sendState());
  });
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = "../../demo-host/device.css";
  document.head.append(link);
  document.addEventListener("DOMContentLoaded", () => {
    window.dismissSeniorStartup?.();
    new MutationObserver(() => sendState()).observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["hidden", "aria-current"],
    });
    sendState(true);
  });
})();
