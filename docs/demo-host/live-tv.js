// Browser equivalent of the native LiveTvActivity, LiveTvControls and PlayerChrome.
(() => {
  "use strict";
  const paths = {
    play: "M8 5v14l11-7z",
    pause: "M8 5v14m8-14v14",
    record: "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16",
    stop: "M6 6h12v12H6z",
    library:
      "M3 5.5h5.5a4 4 0 0 1 3.5 2a4 4 0 0 1 3.5-2H21v12h-5.5c-1.5 0-2.9.7-3.5 2c-.6-1.3-2-2-3.5-2H3zM12 8v11",
    channels: "M4 6h16M4 12h16M4 18h16",
    guide: "M4 5h16v15H4zM4 10h16M8 3v5m8-5v5",
    sound: "M3 9h4l5-4v14l-5-4H3zM16 8c2 2 2 6 0 8m3-11c4 4 4 10 0 14",
    mute: "M3 9h4l5-4v14l-5-4H3zM16 9l5 6m0-6-5 6",
    back: "M19 12H5m6-6-6 6 6 6",
    retry: "M4 8c1.8-4 7-6 11-4s6 7 4 11-7 6-11 4m-4-14v5h5",
    star: "m12 3 2.8 5.7 6.3.9-4.5 4.4 1.1 6.3-5.7-3-5.7 3 1.1-6.3L3 9.6l6.2-.9z",
    check: "m4 12 5 5L20 6",
  };
  const make = (tag, text, cls) => {
    const n = document.createElement(tag);
    if (text != null) n.textContent = text;
    if (cls) n.className = cls;
    return n;
  };
  const icon = (name) => {
    const ns = "http://www.w3.org/2000/svg",
      s = document.createElementNS(ns, "svg"),
      p = document.createElementNS(ns, "path");
    s.setAttribute("viewBox", "0 0 24 24");
    s.setAttribute("aria-hidden", "true");
    p.setAttribute("d", paths[name] || "");
    s.append(p);
    return s;
  };
  function button(label, run, cls, name) {
    const b = make("button", null, cls);
    b.type = "button";
    if (name) b.append(icon(name));
    b.append(make("span", label));
    b.addEventListener("click", run);
    return b;
  }
  function setButton(b, label, name) {
    b.replaceChildren(icon(name), make("span", label));
  }
  function localTime(value) {
    const d = new Date(value);
    return Number.isNaN(+d)
      ? ""
      : d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
  }
  const rangeTime = (entry) =>
    entry
      ? [localTime(entry.start), localTime(entry.end)]
          .filter(Boolean)
          .join(" – ")
      : "";
  function readStored(key) {
    try {
      return JSON.parse(localStorage.getItem(key) || "[]");
    } catch {
      return [];
    }
  }
  function store(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch {
      return false;
    }
  }
  const recordsKey = "openNavaLiveArchive";
  function openDB() {
    return new Promise((resolve, reject) => {
      const job = indexedDB.open("opennava-live-capture", 1);
      job.onupgradeneeded = () => job.result.createObjectStore("video");
      job.onsuccess = () => resolve(job.result);
      job.onerror = () => reject(job.error);
    });
  }
  async function mediaFile(method, id, blob) {
    const db = await openDB();
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(
          "video",
          method === "get" ? "readonly" : "readwrite",
        );
        const s = tx.objectStore("video");
        const request =
          method === "put"
            ? s.put(blob, id)
            : method === "get"
              ? s.get(id)
              : s.delete(id);
        tx.oncomplete = () => resolve(request.result);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
      });
    } finally {
      db.close();
    }
  }
  function saveEntry(entry) {
    const list = readStored(recordsKey).filter((v) => v.id !== entry.id);
    list.unshift(entry);
    return store(recordsKey, list);
  }
  const trustedReplay = (url) => {
    try {
      const u = new URL(url);
      return u.protocol === "https:" &&
        /(^|\.)(ardmediathek\.de|ard\.de|daserste\.de|zdf\.de|arte\.tv|3sat\.de|phoenix\.de|kika\.de|tagesschau\.de)$/.test(
          u.hostname,
        )
        ? u.href
        : null;
    } catch {
      return null;
    }
  };
  function mount(opts) {
    const { dialog, stations, onStation, onBack, onLibrary, sampleFor } = opts;
    let station = opts.station,
      guideOnly = opts.initialView === "guide",
      source = "live",
      status = "loading",
      statusDetail = "",
      currentGuide = null,
      currentSample = null;
    let choosingGuide = false;
    let version = 0,
      epgVersion = 0,
      closed = false,
      controller = null,
      panel = "",
      shown = true,
      selected = 0,
      manual = false,
      errorTimer = 0,
      hideTimer = 0,
      toastTimer = 0,
      ticker = 0;
    let holdKey = "",
      holdTimer = 0,
      repeatTimer = 0,
      held = false,
      scrubAt = null,
      listMode = "all",
      recording = null,
      requestPlaying = true;
    const favourites = new Set(readStored("openNavaFavouriteStations"));
    const favouriteMode = () => listMode === "favourite";
    dialog.classList.add("live-stage");
    dialog.setAttribute("aria-label", "Live TV");
    dialog.tabIndex = -1;
    const video = make("video", null, "live-video");
    video.playsInline = true;
    video.muted = false;
    video.autoplay = true;
    const chrome = make("div", null, "live-chrome"),
      mast = make("div", null, "live-mast"),
      mark = make("span", null, "live-mark");
    mark.setAttribute("aria-hidden", "true");
    mark.append(make("i"), make("i"), make("i"));
    const back = button("Live TV", onBack, "live-brand");
    back.prepend(mark);
    back.title = "Leave Live TV";
    back.setAttribute("aria-label", "Back from Live TV");
    const tools = make("div", null, "live-tools"),
      clock = make("time", null, "live-clock");
    const channelTool = button(
        "Channels",
        () => openChannels(),
        "live-tool",
        "channels",
      ),
      guideTool = button("TV guide", () => openGuide(), "live-tool", "guide");
    const sound = button(
      "Mute",
      () => {
        video.muted = !video.muted;
        updateSound();
      },
      "live-tool live-sound",
      "sound",
    );
    tools.append(channelTool, guideTool, sound, clock);
    mast.append(back, tools);
    const meta = make("div", null, "live-meta"),
      kicker = make("div", null, "live-kicker"),
      stationName = make("h1"),
      programme = make("p", null, "live-programme"),
      timeline = make("div", null, "live-timeline");
    const start = make("time"),
      prog = make("progress"),
      end = make("time");
    prog.max = 1;
    timeline.append(start, prog, end);
    const nextRow = make("div", null, "live-upnext");
    const nextTitle = make("span");
    nextRow.append(make("b", "UP NEXT"), nextTitle);
    meta.append(kicker, stationName, programme, timeline, nextRow);
    const big = make("div", null, "live-number");
    big.setAttribute("aria-hidden", "true");
    const strip = make("div", null, "live-actions");
    strip.setAttribute("role", "group");
    strip.setAttribute("aria-label", "Live playback controls");
    const pause = button("Pause", togglePlay, "live-action", "pause"),
      record = button(
        "Record",
        () => (recording ? stopRecord() : openRecorder()),
        "live-action",
        "record",
      ),
      library = button(
        "Library",
        () => onLibrary(station.provider),
        "live-action",
        "library",
      );
    const actions = [pause, record, library];
    strip.append(...actions);
    actions.forEach((b, i) =>
      b.addEventListener("focus", () => {
        selected = i;
        highlight();
      }),
    );
    const scrim = make("div", null, "live-mist");
    chrome.append(scrim, mast, meta, big, strip);
    const notice = make("div", null, "live-notice");
    notice.setAttribute("role", "status");
    notice.hidden = true;
    const feedback = make("div", null, "live-source");
    const title = make("strong"),
      description = make("p");
    const choices = make("div", null, "live-source-actions");
    const retry = button(
        "Retry live stream",
        () => begin(false),
        "live-pill",
        "retry",
      ),
      sample = button("Play sample footage", () => begin(true), "live-pill");
    choices.append(retry, sample);
    feedback.append(title, description, choices);
    feedback.hidden = true;
    const panels = make("div", null, "live-panels");
    panels.hidden = true;
    dialog.append(video, chrome, feedback, notice, panels);
    function busy() {
      return status === "loading" || status === "unavailable";
    }
    function say(message) {
      notice.textContent = message;
      notice.hidden = !message;
      clearTimeout(toastTimer);
      if (message) toastTimer = setTimeout(() => (notice.hidden = true), 3000);
    }
    function highlight() {
      actions.forEach((b, i) => b.classList.toggle("selected", i === selected));
    }
    function reveal(expanded = true, focus = false) {
      shown = true;
      manual = expanded;
      chrome.classList.remove("is-hidden");
      chrome.inert = false;
      highlight();
      if (focus && !panel) actions[selected]?.focus({ preventScroll: true });
      scheduleHide();
    }
    function hide(explicit = false) {
      if (panel || (!explicit && (busy() || !requestPlaying))) return;
      shown = false;
      manual = false;
      chrome.classList.add("is-hidden");
      chrome.inert = true;
      dialog.focus({ preventScroll: true });
    }
    function scheduleHide() {
      clearTimeout(hideTimer);
      if (!panel && !busy() && requestPlaying)
        hideTimer = setTimeout(hide, manual ? 10000 : 7500);
    }
    function popup(name) {
      clearTimeout(hideTimer);
      panel = name;
      chrome.inert = true;
      chrome.classList.add("has-panel");
      panels.replaceChildren();
      panels.className = "live-panels " + name;
      panels.hidden = false;
      feedback.hidden = true;
      return panels;
    }
    function closePanel() {
      panels.hidden = true;
      panels.replaceChildren();
      panel = "";
      chrome.classList.remove("has-panel");
      if (guideOnly) {
        onBack();
        return;
      }
      reveal(true, true);
      showSource();
    }
    function backAction() {
      if (panel) closePanel();
      else if (manual && shown && !busy()) {
        hide(true);
      } else onBack();
    }
    function updateSound() {
      setButton(
        sound,
        video.muted ? "Unmute" : "Mute",
        video.muted ? "mute" : "sound",
      );
    }
    function showSource() {
      clearTimeout(errorTimer);
      feedback.hidden = !!panel || !busy();
      if (feedback.hidden) return;
      const unavailable = status === "unavailable";
      title.textContent = unavailable
        ? "Live stream unavailable"
        : "Connecting to " + station.name;
      description.textContent = unavailable
        ? statusDetail || "The broadcaster could not be reached."
        : "Opening the broadcaster’s live stream…";
      retry.hidden = !unavailable;
      sample.hidden = !unavailable;
      if (!unavailable)
        errorTimer = setTimeout(() => {
          if (!closed && status === "loading" && !panel) sample.hidden = false;
        }, 3500);
    }
    function nowGuide() {
      return currentGuide?.now;
    }
    function refresh() {
      if (closed) return;
      const num = String(station.number).padStart(2, "0");
      big.textContent = num;
      const now = nowGuide(),
        next = currentGuide?.next;
      const behind = video.seekable.length
        ? video.seekable.end(video.seekable.length - 1) - video.currentTime
        : 0;
      const mode =
        source === "sample"
          ? "SAMPLE FOOTAGE"
          : status === "unavailable"
            ? "UNAVAILABLE"
            : status === "loading"
              ? "CONNECTING"
              : !requestPlaying
                ? "PAUSED"
                : behind > 60
                  ? "TIME SHIFT"
                  : "LIVE";
      kicker.textContent = "CHANNEL " + num + "  ·  " + mode;
      stationName.textContent = station.name;
      programme.textContent =
        source === "sample" ? currentSample.title : now?.title || "";
      programme.hidden = !programme.textContent;
      timeline.hidden = source === "sample" || !now?.start || !now?.end;
      nextRow.hidden = source === "sample" || !next?.title;
      if (!timeline.hidden) {
        const from = +new Date(now.start),
          to = +new Date(now.end),
          remain = Math.max(1, Math.round((to - Date.now()) / 60000));
        start.textContent = localTime(from);
        end.textContent = localTime(to) + " · " + remain + " min left";
        prog.value = Math.max(
          0,
          Math.min(1, (Date.now() - from) / (to - from || 1)),
        );
      }
      if (!nextRow.hidden)
        nextTitle.textContent = [localTime(next.start), next.title]
          .filter(Boolean)
          .join("  ");
      clock.textContent = localTime(Date.now()) + " local time";
      setButton(
        pause,
        status === "unavailable" ? "Reload" : requestPlaying ? "Pause" : "Play",
        status === "unavailable" ? "retry" : requestPlaying ? "pause" : "play",
      );
      setButton(
        record,
        recording ? "Stop recording" : "Record",
        recording ? "stop" : "record",
      );
      record.disabled = !recording && busy();
      highlight();
    }
    async function getGuide() {
      const v = ++epgVersion;
      currentGuide = null;
      refresh();
      try {
        const data = await window.OpenNavaLive?.guide(station.id);
        if (closed || v !== epgVersion) return;
        currentGuide = data || null;
      } catch {
        if (closed || v !== epgVersion) return;
        currentGuide = null;
      }
      refresh();
      if (panel === "guide") drawGuide();
    }
    function sampleGuide() {
      const items = opts.samples(station),
        start = Math.floor(Date.now() / 1800000) * 1800000;
      return Object.fromEntries(
        ["previous", "now", "next"].map((slot, i) => [
          slot,
          {
            ...items[i],
            start: start + (i - 1) * 1800000,
            end: start + i * 1800000,
          },
        ]),
      );
    }
    function setStatus(value, message, token) {
      if (closed || token !== version) return;
      status = value;
      if (value === "live") requestPlaying = !video.paused;
      statusDetail = typeof message === "string" ? message : "";
      showSource();
      refresh();
      scheduleHide();
    }
    function stopSource() {
      version++;
      controller?.destroy();
      controller = null;
      video.pause();
      video.removeAttribute("src");
      video.load();
    }
    function begin(isSample = false) {
      clearHold();
      stopRecord();
      stopSource();
      const token = version;
      source = isSample ? "sample" : "live";
      currentSample = sampleFor(station);
      requestPlaying = true;
      guideOnly = false;
      status = isSample ? "sample" : "loading";
      statusDetail = "";
      onStation(station);
      showSource();
      closePanelSilently();
      reveal(false, true);
      refresh();
      if (isSample) {
        ++epgVersion;
        currentGuide = sampleGuide();
        video.loop = true;
        video.poster = "../full/assets/" + currentSample.art + ".jpg";
        video.src = "../full/assets/" + currentSample.art + ".mp4";
        video.play().catch(() => {
          if (token === version) {
            requestPlaying = false;
            refresh();
          }
        });
        showSource();
        return;
      }
      video.loop = false;
      video.removeAttribute("poster");
      getGuide();
      const api = window.OpenNavaLive;
      if (!api) {
        setStatus(
          "unavailable",
          "Live streaming is unavailable in this browser.",
          token,
        );
        return;
      }
      try {
        controller = api.attach(video, station.id, {
          onStatus: (s, msg) => {
            setStatus(s, msg, token);
          },
        });
      } catch {
        setStatus("unavailable", "The stream could not be opened.", token);
      }
    }
    function closePanelSilently() {
      panels.hidden = true;
      panels.replaceChildren();
      panel = "";
      chrome.classList.remove("has-panel");
      chrome.inert = false;
    }
    function tune(next) {
      if (!next) return;
      station = next;
      begin(false);
    }
    function step(delta) {
      const order =
        favouriteMode() && favourites.size
          ? stations.filter((s) => favourites.has(s.id))
          : stations;
      let i = order.findIndex((s) => s.id === station.id);
      if (i < 0) i = delta > 0 ? -1 : 0;
      tune(order[(i + delta + order.length) % order.length]);
    }
    function togglePlay() {
      if (status === "unavailable") {
        begin(false);
        return;
      }
      if (status === "loading") return;
      if (video.paused) {
        requestPlaying = true;
        video.play().catch(() => {
          requestPlaying = false;
          refresh();
        });
      } else {
        requestPlaying = false;
        video.pause();
      }
      refresh();
      reveal(true);
    }
    function seekRange() {
      const r = video.seekable;
      return r.length
        ? {
            start: r.start(0),
            end: Math.max(r.start(0), r.end(r.length - 1) - 0.15),
          }
        : null;
    }
    function limit(t, r) {
      return Math.max(r.start, Math.min(r.end, t));
    }
    function seekBy(delta) {
      const range = seekRange();
      if (!range) {
        say("Seeking is unavailable on this channel.");
        return;
      }
      video.currentTime = limit(video.currentTime + delta, range);
      reveal();
    }
    function skip(dir) {
      const g = dir < 0 ? currentGuide?.previous : currentGuide?.now,
        range = seekRange();
      if (source === "sample" && dir < 0 && g?.id) {
        opts.playSample(g.id);
        return;
      }
      if (source === "live" && g?.start && range) {
        const target =
          range.end - (Date.now() - new Date(g.start).getTime()) / 1000;
        if (target >= range.start && target <= range.end) {
          video.currentTime = target;
          say(g.title);
          reveal();
          return;
        }
      }
      if (source === "live" && dir < 0 && trustedReplay(g?.url)) {
        openGuide();
        return;
      }
      seekBy(dir * 10);
    }
    function panelHead(kind, text) {
      const h = make("div", null, "live-panel-head"),
        section = make("div");
      section.append(make("small", kind), make("h2", text));
      h.append(section, button("Back", closePanel, "live-pill", "back"));
      return h;
    }
    function openChannels() {
      choosingGuide = panel === "guide";
      popup("channels");
      drawChannels();
    }
    function drawChannels(focusId = station.id) {
      panels.replaceChildren();
      const side = make("section", null, "live-station-panel");
      side.append(panelHead("LIVE TV", "Channels"));
      const tabs = make("div", null, "live-tabs");
      for (const [id, name] of [
        ["all", "All channels"],
        ["favourite", "My channels"],
      ]) {
        const tab = button(
          name,
          () => {
            listMode = id;
            drawChannels();
          },
          "live-pill",
        );
        tab.setAttribute("aria-pressed", String(listMode === id));
        tabs.append(tab);
      }
      side.append(tabs);
      const menu = make("div", null, "live-station-list");
      const rows = favouriteMode()
        ? stations.filter((s) => favourites.has(s.id))
        : stations;
      for (const ch of rows) {
        const row = make("div", null, "live-station-row"),
          select = button(
            ch.name,
            () => {
              if (choosingGuide) {
                choosingGuide = false;
                station = ch;
                if (!guideOnly) begin(false);
                else {
                  onStation(ch);
                  getGuide();
                }
                openGuide();
              } else tune(ch);
            },
            "live-station-select",
          );
        select.dataset.station = ch.id;
        select.prepend(make("small", String(ch.number).padStart(2, "0")));
        if (ch.id === station.id) select.append(make("em", "On screen"));
        const star = button(
          favourites.has(ch.id) ? "Remove favourite" : "Add favourite",
          () => {
            if (favourites.has(ch.id)) favourites.delete(ch.id);
            else favourites.add(ch.id);
            store("openNavaFavouriteStations", [...favourites]);
            drawChannels(ch.id);
          },
          "live-star",
          "star",
        );
        star.setAttribute(
          "aria-label",
          (favourites.has(ch.id) ? "Remove " : "Add ") +
            ch.name +
            (favourites.has(ch.id) ? " from favourites" : " to favourites"),
        );
        star.setAttribute("aria-pressed", String(favourites.has(ch.id)));
        row.append(select, star);
        menu.append(row);
      }
      if (!rows.length)
        menu.append(
          make(
            "p",
            "Add a favourite using the star next to a station in All channels.",
          ),
        );
      side.append(menu);
      panels.append(side);
      const current =
        Array.from(menu.querySelectorAll("[data-station]")).find(
          (b) => b.dataset.station === focusId,
        ) ||
        menu.querySelector("button") ||
        tabs.querySelector("button");
      current?.focus({ preventScroll: true });
      current?.scrollIntoView({ block: "nearest" });
    }
    function openGuide() {
      popup("guide");
      drawGuide();
    }
    function drawGuide() {
      const focusKey = document.activeElement?.dataset.start;
      panels.replaceChildren();
      const page = make("section", null, "live-guide-page");
      const sidebar = make("aside", null, "live-guide-cover");
      const hour = new Date().getHours();
      sidebar.dataset.scene =
        hour < 6 || hour >= 22
          ? "night"
          : hour < 9
            ? "dawn"
            : hour >= 17
              ? "evening"
              : "day";
      sidebar.append(make("h2", "TV guide"));
      const bar = make("div", null, "live-guide-station");
      bar.append(
        make("small", "CHANNEL"),
        make("h3", station.name),
        button("Change channel", openChannels, "live-pill", "channels"),
        button(
          "Recordings & reminders",
          opts.onRecordings,
          "live-pill",
          "guide",
        ),
      );
      sidebar.append(bar);
      const schedule = make("section", null, "live-guide-content");
      const head = make("div", null, "live-guide-heading");
      const day = make("div");
      day.append(
        make("h3", "Today"),
        make(
          "p",
          new Intl.DateTimeFormat("en-GB", {
            weekday: "long",
            day: "numeric",
            month: "long",
          }).format(new Date()),
        ),
      );
      head.append(day, button("Back", closePanel, "live-pill", "back"));
      schedule.append(head);
      page.append(sidebar, schedule);
      const rows = make("div", null, "live-guide-rows");
      const list = currentGuide?.entries?.length
        ? currentGuide.entries
        : ["previous", "now", "next"]
            .map((slot) => currentGuide?.[slot])
            .filter(Boolean);
      const saved = readStored(recordsKey);
      for (const entry of list) {
        const numericStart = +new Date(entry.start);
        const closest = ["previous", "now", "next"].find(
          (key) => currentGuide?.[key]?.start === entry.start,
        );
        const slot =
          closest || (numericStart > Date.now() ? "later" : "earlier");
        const upcoming = slot === "next" || slot === "later";
        const labels = {
          previous: "Previously",
          now: "On now",
          next: "Up next",
          later: "Later",
          earlier: "Earlier",
        };
        const label = labels[slot];
        const reminderID = station.id + ":" + entry.start;
        const remembered = saved.some(
          (item) => item.kind === "reminder" && item.id === reminderID,
        );
        const row = button(
          entry.title,
          () => {
            if (slot === "now") {
              if (guideOnly) begin(source === "sample");
              else closePanel();
              return;
            }
            if (upcoming) {
              const success = remembered
                ? store(
                    recordsKey,
                    readStored(recordsKey).filter(
                      (item) => item.id !== reminderID,
                    ),
                  )
                : saveEntry({
                    id: reminderID,
                    kind: "reminder",
                    station: station.name,
                    title: entry.title,
                    start: entry.start,
                  });
              say(
                success
                  ? remembered
                    ? "Reminder removed."
                    : "Reminder saved in this browser."
                  : "Could not save the reminder.",
              );
              drawGuide();
              return;
            }
            if (source === "sample") {
              opts.playSample(entry.id);
              return;
            }
            const replay = trustedReplay(entry.url);
            if (replay) {
              window.open(replay, "_blank", "noopener,noreferrer");
              return;
            }
            const r = seekRange(),
              at = r
                ? r.end - (Date.now() - new Date(entry.start).getTime()) / 1000
                : -1;
            if (r && at >= r.start && at <= r.end && !guideOnly) {
              closePanel();
              video.currentTime = at;
              reveal(true);
              return;
            }
            say("No replay is available for this programme.");
          },
          "live-guide-row",
        );
        row.dataset.slot = slot;
        row.dataset.start = String(entry.start);
        row.prepend(make("small", rangeTime(entry) + "  ·  " + label));
        row.append(
          make(
            "em",
            upcoming
              ? remembered
                ? "Remove reminder"
                : "Set reminder"
              : slot === "now"
                ? "Watch live"
                : source === "sample"
                  ? "Play sample"
                  : trustedReplay(entry.url)
                    ? "Broadcaster website"
                    : "View programme",
          ),
        );
        rows.append(row);
      }
      if (!rows.children.length)
        rows.append(
          make("p", "Programme listings are unavailable for this channel."),
        );
      schedule.append(rows);
      if (source === "sample")
        schedule.append(make("p", "Sample schedule", "live-support"));
      panels.append(page);
      const buttonToFocus =
        Array.from(rows.children).find(
          (row) => focusKey && row.dataset.start === focusKey,
        ) ||
        rows.querySelector('[data-slot="now"]') ||
        rows.querySelector("button") ||
        bar.querySelector("button");
      buttonToFocus?.focus({ preventScroll: true });
      if (buttonToFocus && rows.contains(buttonToFocus))
        rows.scrollTop = Math.max(
          0,
          rows.scrollTop +
            buttonToFocus.getBoundingClientRect().top -
            rows.getBoundingClientRect().top -
            rows.clientHeight * 0.33,
        );
    }
    function archiveAction(entry) {
      const replay = trustedReplay(entry?.url);
      if (source === "sample") {
        opts.saveSample(currentSample.id);
        return "Sample saved in Recordings.";
      }
      if (replay && entry) {
        if (
          saveEntry({
            id: station.id + ":" + entry.start,
            kind: "link",
            title: entry.title,
            station: station.name,
            url: replay,
            start: entry.start,
          })
        )
          return "Broadcaster link saved in Recordings.";
      }
      return "";
    }
    function openRecorder() {
      if (busy()) return;
      popup("record");
      const card = make("section", null, "live-record-card");
      card.append(
        panelHead(
          station.name + " · Live TV",
          "How long would you like to record?",
        ),
      );
      const entry = nowGuide();
      const sub = make(
        "p",
        source === "sample"
          ? currentSample.title
          : entry?.title || station.name,
      );
      card.append(sub);
      const options = make("div", null, "live-record-options"),
        left = (new Date(entry?.end).getTime() - Date.now()) / 60000;
      if (left > 0 && left <= 360)
        options.append(
          button(
            "Until the programme ends",
            () => capture(left),
            "live-choice",
          ),
        );
      for (const min of [1, 5, 30, 60])
        options.append(
          button(
            min === 1 ? "1 minute" : min + " minutes",
            () => capture(min),
            "live-choice",
          ),
        );
      card.append(
        options,
        make(
          "p",
          "Recorded in this browser while this player is open.",
          "live-support",
        ),
      );
      if (source === "sample" || trustedReplay(entry?.url))
        card.append(
          button(
            source === "sample"
              ? "Save sample programme"
              : "Save broadcaster link",
            () => {
              const msg = archiveAction(entry);
              closePanel();
              say(msg || "Could not save the programme.");
            },
            "live-pill",
          ),
        );
      panels.append(card);
      options.querySelector("button")?.focus({ preventScroll: true });
    }
    async function capture(minutes) {
      const token = version;
      let stream, rec;
      try {
        if (!window.MediaRecorder || typeof video.captureStream !== "function")
          throw Error();
        if (video.readyState < 2) throw Error();
        const db = await openDB();
        db.close();
        if (closed || token !== version || panel !== "record") return;
        stream = video.captureStream();
        if (!stream.getVideoTracks().length) throw Error();
        const format = [
          "video/webm;codecs=vp9,opus",
          "video/webm;codecs=vp8,opus",
          "video/webm",
          "video/mp4",
        ].find((t) => MediaRecorder.isTypeSupported(t));
        rec = format
          ? new MediaRecorder(stream, { mimeType: format })
          : new MediaRecorder(stream);
      } catch {
        say(
          "This browser cannot capture this stream. Use a supported desktop browser or the TV app.",
        );
        return;
      }
      const id = crypto.randomUUID(),
        entry = {
          id,
          kind: "capture",
          title:
            source === "sample"
              ? currentSample.title
              : nowGuide()?.title || station.name,
          station: station.name,
          start: new Date().toISOString(),
        };
      const chunks = [];
      const session = { rec, timer: 0 };
      rec.ondataavailable = (e) => {
        if (e.data.size) chunks.push(e.data);
      };
      rec.onerror = () => {
        if (!closed) say("The browser interrupted the recording.");
      };
      rec.onstop = async () => {
        clearTimeout(session.timer);
        stream.getTracks().forEach((t) => t.stop());
        if (recording === session) recording = null;
        const blob = new Blob(chunks, { type: rec.mimeType || "video/webm" });
        try {
          if (!blob.size) throw Error();
          await mediaFile("put", id, blob);
          if (!saveEntry(entry)) {
            await mediaFile("delete", id);
            throw Error();
          }
          if (!closed) say("Saved in Recordings.");
        } catch {
          if (!closed) say("The browser could not save this recording.");
        }
        if (!closed) refresh();
      };
      try {
        rec.start(700);
      } catch {
        stream.getTracks().forEach((t) => t.stop());
        say("This stream does not allow browser recording.");
        return;
      }
      recording = session;
      session.timer = setTimeout(stopRecord, minutes * 60000);
      closePanel();
      refresh();
      say("Recording in this browser.");
    }
    function stopRecord() {
      if (!recording) return;
      clearTimeout(recording.timer);
      if (recording.rec.state !== "inactive") recording.rec.stop();
    }
    function clearHold() {
      clearTimeout(holdTimer);
      clearInterval(repeatTimer);
      holdKey = "";
      held = false;
      scrubAt = null;
    }
    function longPress(key) {
      held = true;
      if (key === "Enter" && !panel) {
        togglePlay();
        return;
      }
      if (key === "ArrowUp") {
        openGuide();
        return;
      }
      if (key === "ArrowDown") {
        openChannels();
        return;
      }
      if (key === "ArrowLeft" || key === "ArrowRight") {
        const r = seekRange();
        if (!r) {
          say("Seeking is unavailable on this channel.");
          return;
        }
        const dir = key === "ArrowLeft" ? -1 : 1,
          began = Date.now();
        scrubAt = video.currentTime;
        reveal(true);
        function tick() {
          const range = seekRange();
          if (!range) return;
          const delta = Date.now() - began;
          scrubAt = limit(
            scrubAt + dir * (delta < 1800 ? 10 : delta < 4500 ? 30 : 60),
            range,
          );
          say(
            scrubAt <= range.start
              ? "Start of available stream"
              : scrubAt >= range.end
                ? "Back to live"
                : (dir < 0 ? "Rewind" : "Fast forward") +
                  " · " +
                  Math.round(range.end - scrubAt) +
                  " s behind",
          );
        }
        tick();
        repeatTimer = setInterval(tick, 250);
      }
    }
    function keyPanel(e) {
      const k = e.key,
        items = [...panels.querySelectorAll("button:not([disabled])")];
      if (k === "Escape") {
        if (panel === "channels" && choosingGuide) {
          choosingGuide = false;
          openGuide();
        } else closePanel();
        return;
      }
      if (k === "Enter" || k === " ") {
        document.activeElement?.click();
        return;
      }
      const active = document.activeElement;
      if (
        panel === "guide" &&
        k === "ArrowLeft" &&
        active?.matches(".live-guide-row")
      ) {
        panels.querySelector(".live-guide-station button")?.focus();
        return;
      }
      if (
        panel === "guide" &&
        k === "ArrowRight" &&
        active?.closest(".live-guide-station")
      ) {
        (
          panels.querySelector('[data-slot="now"]') ||
          panels.querySelector(".live-guide-row")
        )?.focus();
        return;
      }
      if (
        panel === "channels" &&
        ["ArrowUp", "ArrowDown"].includes(k) &&
        active?.matches(".live-station-select,.live-star")
      ) {
        const r = active.parentElement;
        const next =
          k === "ArrowDown" ? r.nextElementSibling : r.previousElementSibling;
        if (next)
          next
            .querySelector(
              active.classList.contains("live-star")
                ? ".live-star"
                : ".live-station-select",
            )
            ?.focus();
        return;
      }
      if (
        panel === "channels" &&
        ["ArrowLeft", "ArrowRight"].includes(k) &&
        active?.matches(".live-station-select")
      ) {
        listMode = k === "ArrowLeft" ? "favourite" : "all";
        drawChannels();
        return;
      }
      const dir = k === "ArrowUp" || k === "ArrowLeft" ? -1 : 1;
      const idx = items.indexOf(active);
      items[Math.max(0, Math.min(items.length - 1, idx + dir))]?.focus();
    }
    function keyDown(e) {
      if (
        ![
          "ArrowUp",
          "ArrowDown",
          "ArrowLeft",
          "ArrowRight",
          "Enter",
          "Escape",
          " ",
          "PageDown",
          "PageUp",
        ].includes(e.key)
      )
        return;
      e.preventDefault();
      e.stopImmediatePropagation();
      if (panel) {
        if (!e.repeat) keyPanel(e);
        return;
      }
      if (e.key === "Escape") {
        if (!e.repeat) backAction();
        return;
      }
      if (e.key === " ") {
        if (!e.repeat) togglePlay();
        return;
      }
      if (e.key === "PageUp" || e.key === "PageDown") {
        if (!e.repeat) step(e.key === "PageUp" ? 1 : -1);
        return;
      }
      if (
        e.key === "Enter" &&
        document.activeElement?.closest(".live-source,.live-tools,.live-brand")
      ) {
        if (!e.repeat) document.activeElement.click();
        return;
      }
      if (e.repeat || holdKey === e.key) return;
      clearHold();
      holdKey = e.key;
      holdTimer = setTimeout(() => longPress(e.key), 500);
    }
    function keyUp(e) {
      if (e.key !== holdKey) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      const key = holdKey,
        wasHeld = held,
        target = scrubAt;
      clearHold();
      if (wasHeld) {
        const r = seekRange();
        if (target !== null && r) video.currentTime = limit(target, r);
        scheduleHide();
        return;
      }
      if (key === "ArrowUp" || key === "ArrowDown") {
        step(key === "ArrowUp" ? 1 : -1);
        return;
      }
      if (key === "Enter") {
        if (!shown) reveal(true, true);
        else actions[selected]?.click();
        return;
      }
      if (shown) {
        const dir = key === "ArrowLeft" ? -1 : 1;
        let i = selected + dir;
        while (actions[i]?.disabled) i += dir;
        if (actions[i]) {
          selected = i;
          reveal(true, true);
        }
      } else skip(key === "ArrowLeft" ? -1 : 1);
    }
    document.addEventListener("keydown", keyDown, true);
    document.addEventListener("keyup", keyUp, true);
    video.addEventListener("click", () => {
      if (manual && shown) hide(true);
      else reveal(true, true);
    });
    video.addEventListener("play", () => {
      if (closed) return;
      requestPlaying = true;
      refresh();
      scheduleHide();
    });
    video.addEventListener("pause", () => {
      if (closed || video.readyState < 2) return;
      requestPlaying = false;
      reveal(true);
      refresh();
    });
    let lastPointer = 0;
    dialog.addEventListener("pointermove", (e) => {
      if (
        e.pointerType === "mouse" &&
        !panel &&
        Date.now() - lastPointer > 380
      ) {
        lastPointer = Date.now();
        reveal(false);
      }
    });
    window.addEventListener("blur", clearHold);
    ticker = setInterval(refresh, 15000);
    let guideTicker = setInterval(() => {
      if (!closed && source === "live") getGuide();
    }, 150000);
    if (guideOnly) {
      onStation(station);
      openGuide();
      getGuide();
    } else begin(false);
    return {
      destroy() {
        closed = true;
        clearHold();
        clearTimeout(errorTimer);
        clearTimeout(hideTimer);
        clearTimeout(toastTimer);
        clearInterval(ticker);
        clearInterval(guideTicker);
        stopRecord();
        stopSource();
        document.removeEventListener("keydown", keyDown, true);
        document.removeEventListener("keyup", keyUp, true);
        window.removeEventListener("blur", clearHold);
      },
    };
  }
  window.OpenNavaLiveView = Object.freeze({
    mount,
    recordings: () => readStored(recordsKey),
    file: (id) => mediaFile("get", id),
    remove: async (id) => {
      await mediaFile("delete", id).catch(() => {});
      store(
        recordsKey,
        readStored(recordsKey).filter((e) => e.id !== id),
      );
    },
    trustedReplay,
  });
})();
