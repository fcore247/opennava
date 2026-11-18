// Browser implementation of the TV WebView's native device API. Live television uses broadcaster streams. The library catalogue uses local sample content.
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
    liveView = null,
    recordingUrl = null,
    previousFocus = null,
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
  function changeProfile(next) {
    if (parent === window) location.assign("../" + next + "/index.html");
    else
      parent.postMessage(
        { source: "navatv-demo", type: "profile", profile: next },
        location.origin,
      );
    return true;
  }
  const btn = (title, action) => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = title;
    b.onclick = action;
    return b;
  };
  function schedule() {
    const pool = channelItems(station.provider);
    const index = pool.indexOf(playingNow());
    return [-1, 0, 1].map(
      (offset) => pool[(index + pool.length + offset) % pool.length],
    );
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
    }, 0);
    return true;
  }
  function closeLayer(resume = true) {
    if (!layer) return;
    liveView?.destroy();
    liveView = null;
    media()?.pause();
    if (recordingUrl) URL.revokeObjectURL(recordingUrl);
    recordingUrl = null;
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
  function makeLayer(screen, title) {
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
    active = { screen };
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
  }
  function home() {
    backStack = [];
    closeLayer(false);
    fullFilter = "";
    window.dismissSeniorStartup?.();
    window.navaNativeHome?.();
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
  }
  function saveButton(p) {
    const b = btn(
      records.has(p.id) ? "Remove from recordings" : "Save to recordings",
      () => changeSaved(p.id, !records.has(p.id)),
    );
    b.dataset.demoSave = p.id;
    return b;
  }
  function play(p) {
    if (!p) return false;
    const el = makeLayer("player", p.title);
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
    heading(el, "MEDIA LIBRARY  ·  " + p.author, p.title);
    const badge = document.createElement("span");
    badge.className = "sample-footage";
    badge.textContent = "Sample footage";
    el.append(badge);
    const toggle = btn("Pause", () => {
      if (video.paused) video.play().catch(indicate);
      else video.pause();
    });
    const indicate = () => {
      toggle.textContent = video.paused ? "Play" : "Pause";
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
    bar.append(btn("Back", back));
    el.append(bar);
    toggle.focus();
    video.play().catch(indicate);
    return true;
  }

  function liveScreen(id, initialView = "live") {
    const next = channels.find((c) => c.id === id) || station;
    station = next;
    const el = makeLayer(
      initialView,
      initialView === "guide" ? "TV guide" : "Live TV",
    );
    liveView = window.OpenNavaLiveView.mount({
      dialog: el,
      station,
      stations: channels,
      initialView,
      onStation: (next) => {
        station = next;
      },
      onBack: back,
      onLibrary: library,
      onRecordings: recordings,
      sampleFor: (ch) =>
        channelItems(ch.provider)[
          (ch.number - 1) % channelItems(ch.provider).length
        ],
      samples: () => schedule(),
      saveSample: (id) => changeSaved(id, true),
      playSample: (id) => play(find(id)),
    });
  }
  function tune(id) {
    liveScreen(id);
  }
  function programmeList(screen, title, entries) {
    const el = makeLayer(screen, title);
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
  }
  function guide(id) {
    liveScreen(id, "guide");
  }
  function recordings() {
    programmeList(
      "recordings",
      "Recordings",
      [...records].map(find).filter(Boolean),
    );
    const box = layer.querySelector(".demo-entries");
    const saved = window.OpenNavaLiveView.recordings();
    if (saved.length) box.querySelector("p")?.remove();
    const last = box.lastElementChild;
    for (const entry of saved) {
      const line = document.createElement("div");
      line.className = "demo-recording";
      const action = btn(entry.title, async () => {
        if (entry.kind === "reminder") {
          guide(channels.find((c) => c.name === entry.station)?.id);
          return;
        }
        if (entry.kind === "link") {
          const url = window.OpenNavaLiveView.trustedReplay(entry.url);
          if (url) window.open(url, "_blank", "noopener,noreferrer");
          return;
        }
        const before = layer;
        const blob = await window.OpenNavaLiveView.file(entry.id).catch(
          () => null,
        );
        if (layer !== before) return;
        if (!blob) {
          note(
            "Recording unavailable",
            "The recording file is no longer stored in this browser.",
          );
          return;
        }
        const el = makeLayer("player", entry.title);
        const video = document.createElement("video");
        video.controls = true;
        video.playsInline = true;
        video.autoplay = true;
        recordingUrl = URL.createObjectURL(blob);
        video.src = recordingUrl;
        const bar = document.createElement("div");
        bar.className = "demo-controls";
        bar.append(btn("Back", back));
        el.append(video, bar);
        video.play().catch(() => {});
      });
      action.append(
        Object.assign(document.createElement("small"), {
          textContent:
            entry.station +
            " · " +
            (entry.kind === "link"
              ? "Open broadcaster website"
              : entry.kind === "reminder"
                ? "Reminder"
                : "Browser recording"),
        }),
      );
      const remove = btn("Delete", async () => {
        const before = layer;
        await window.OpenNavaLiveView.remove(entry.id);
        if (layer === before) recordings();
      });
      remove.setAttribute("aria-label", "Delete " + entry.title);
      line.append(action, remove);
      box.insertBefore(line, last);
    }
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
    const el = makeLayer("home", title);
    el.classList.add("demo-list");
    heading(el, "DEVICE PREVIEW", title);
    const box = document.createElement("div"),
      p = document.createElement("p");
    box.className = "demo-entries";
    p.textContent = text;
    box.append(p, btn("Back", back));
    el.append(box);
    box.querySelector("button").focus();
    return true;
  }
  function handlePlayerKey(value) {
    if (value === "Escape") {
      back();
      return;
    }
    if (value === "Enter") {
      document.activeElement?.click();
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
  function websiteWatchlist() {
    if (profile !== "full" || parent === window) return null;
    try { return parent.openNavaLunaDemo; } catch { return null; }
  }
  window.NavaTV = Object.freeze({
    getLunaWatchlist: () => JSON.stringify(websiteWatchlist()?.list() || []),
    removeLunaProgramme: (id) => websiteWatchlist()?.remove(id) || false,
    openLunaProgramme: (id) => {
      const item = websiteWatchlist()?.list().find((entry) => entry.id === id);
      if (!item) return false;
      if (id === "netflix") {
        note(item.title, "Auf dem Fernseher übernimmt die installierte Netflix-App. Je nach Gerät öffnet sie den Titel oder ihre Startseite.");
        const link = document.createElement("a");
        link.href = "https://www.netflix.com/de/title/80049832";
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = "Titelseite bei Netflix öffnen";
        layer.querySelector(".demo-entries").prepend(link);
        return true;
      }
      const programme = find(id);
      return programme ? play({ ...programme, title: item.title }) : false;
    },
    getApps: () =>
      JSON.stringify([
        { packageName: "de.swr.avp.ard.tv", name: "ARD" },
        { packageName: "com.zdf.android.mediathek", name: "ZDF" },
        { packageName: "tv.arte.plus7", name: "ARTE" },
        { packageName: "com.google.android.youtube.tv", name: "YouTube" },
      ]),
    mediathekRequest: (id, s) => catalogue(id, s, false),
    youtubeRequest: (id, s) => catalogue(id, s, true),
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
        "Install OpenNava Companion on an Android phone to pair it with your own TV.",
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
      if (layer && !liveView && keys.includes(e.key)) {
        e.preventDefault();
        e.stopImmediatePropagation();
        handlePlayerKey(e.key);
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
    },
    true,
  );
  document.addEventListener("DOMContentLoaded", () => {
    window.dismissSeniorStartup?.();
    document.documentElement.dataset.launcherReady = "true";
  });
})();
