(() => {
  "use strict";

  const scene = document.querySelector("#home-demo");
  if (!scene) return;
  const site = window.OpenNavaSite;
  const assetRoot = new URL("demo/full/assets/", document.currentScript.src);
  const $ = (selector) => scene.querySelector(selector);
  const $$ = (selector) => [...scene.querySelectorAll(selector)];
  const views = $$("[data-hd-view]");
  const nav = $(".hd-nav");
  const query = $("#hd-query");
  const keyboard = $("#hd-keyboard");
  const dialog = document.querySelector("#clip-dialog");
  const video = dialog.querySelector("video");
  const brightness = $("#hd-dim");
  const providers = {
    ard: "ARD",
    zdf: "ZDF",
    arte: "ARTE",
    netflix: "Netflix",
  };
  const programmes = [
    {
      id: "alpine",
      service: "ard",
      title: "alpine",
      art: "mountains",
      description: "alpineDesc",
    },
    {
      id: "ocean",
      service: "arte",
      title: "oceanFilm",
      art: "ocean",
      description: "oceanDesc",
    },
    { id: "forest", service: "zdf", title: "hdForest", art: "forest" },
    { id: "earth", service: "zdf", title: "hdEarth", art: "earth" },
    {
      id: "netflix",
      service: "netflix",
      title: "netflixFilm",
      art: "earth",
      url: "https://www.netflix.com/title/80049832",
    },
  ];
  const programmeById = new Map(programmes.map((item) => [item.id, item]));
  const letters = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNMÄÖÜß"];
  const symbols = ["1234567890", ".,:;!?-_/", "+&()@#%"];
  const pairedKey = "openNavaLunaDemo.v1";
  const extraKey = "openNavaHomeSamples.v1";
  const pairedIds = ["alpine", "ocean", "netflix"];
  let activeView = "home";
  let service = "all";
  let heading = "hdSearch";
  let numbers = false;
  let playing = null;
  let saved = [];

  function loadSaved() {
    try {
      const parsed = (key) => {
        const ids = JSON.parse(sessionStorage.getItem(key) || "[]");
        return Array.isArray(ids) ? ids : [];
      };
      saved = [
        ...new Set([
          ...parsed(pairedKey).filter((id) => pairedIds.includes(id)),
          ...parsed(extraKey).filter((id) => !pairedIds.includes(id)),
        ]),
      ].filter((id) => programmeById.has(id));
    } catch {
      // Keep this page's in-memory list when session storage is unavailable.
    }
  }

  function toggleSaved(id) {
    if (!programmeById.has(id)) return;
    if (saved.includes(id)) saved = saved.filter((item) => item !== id);
    else saved.unshift(id);
    try {
      sessionStorage.setItem(
        pairedKey,
        JSON.stringify(saved.filter((item) => pairedIds.includes(item))),
      );
      sessionStorage.setItem(
        extraKey,
        JSON.stringify(saved.filter((item) => !pairedIds.includes(item))),
      );
    } catch {}
    renderCards();
  }

  function card(item) {
    const article = document.createElement("article");
    article.className = "hd-result hd-result-card";
    const open = document.createElement(item.url ? "a" : "button");
    open.className = "hd-result-open";
    open.dataset.hdResult = item.id;
    if (item.url) {
      open.href = item.url;
      open.target = "_blank";
      open.rel = "noopener noreferrer";
    } else {
      open.type = "button";
    }
    const img = document.createElement("img");
    img.src = new URL(item.art + ".jpg", assetRoot).href;
    img.alt = "";
    img.decoding = "async";
    const info = document.createElement("span");
    info.className = "hd-result-info";
    const kind = document.createElement("small");
    kind.textContent = item.url
      ? site.t("netflixLink")
      : providers[item.service] + " · " + site.t("hdSample");
    const title = document.createElement("strong");
    title.textContent = site.t(item.title);
    info.append(kind, title);
    open.append(img, info);
    const save = document.createElement("button");
    save.type = "button";
    save.className = "hd-save hd-result-save";
    save.dataset.hdSave = item.id;
    const marked = saved.includes(item.id);
    save.setAttribute("aria-pressed", String(marked));
    save.setAttribute(
      "aria-label",
      site.t(marked ? "hdRemove" : "hdSave") + " · " + title.textContent,
    );
    save.title = save.getAttribute("aria-label");
    save.textContent = site.t(marked ? "hdSaved" : "hdSave");
    article.append(open, save);
    return article;
  }

  function showDownloadOffer(container) {
    let offer = container.querySelector(".hd-empty-download");
    if (!offer) {
      offer = document.createElement("div");
      offer.className = "hd-empty-download";
      const logo = $(".hd-search-download img").cloneNode(false);
      const title = document.createElement("strong");
      title.className = "hd-empty-download-title";
      const note = document.createElement("p");
      note.className = "hd-empty-download-note";
      const link = document.createElement("a");
      link.className = "hd-empty-download-button";
      offer.append(logo, title, note, link);
      container.replaceChildren(offer);
    }
    const title = offer.querySelector("strong");
    const note = offer.querySelector("p");
    const link = offer.querySelector("a");
    const label = site.t("searchDownloadNow");
    const description = site.t("searchDemoNote");
    const action = site.t("download");
    if (title.textContent !== label) title.textContent = label;
    if (note.textContent !== description) note.textContent = description;
    if (link.textContent !== action) link.textContent = action;
    link.href = site.pageURL("download/");
  }

  function results(container, items) {
    const search = container.id === "hd-results";
    const focused = document.activeElement;
    const model = container.contains(focused) ? focused.dataset : null;
    const downloadFocused =
      search &&
      focused.matches(".hd-empty-download-button, .hd-search-download-button");
    const focusSelector = model?.hdSave
      ? '[data-hd-save="' + model.hdSave + '"]'
      : model?.hdResult
        ? '[data-hd-result="' + model.hdResult + '"]'
        : null;
    if (search) {
      container.dataset.empty = String(items.length === 0);
      $(".hd-search-download").hidden = items.length === 0;
    }
    if (items.length) container.replaceChildren(...items.map(card));
    else if (search) showDownloadOffer(container);
    else {
      const empty = document.createElement("p");
      empty.className = "hd-empty";
      empty.textContent = site.t("hdSavedEmpty");
      container.replaceChildren(empty);
    }
    if (downloadFocused) {
      const link = $(
        items.length
          ? ".hd-search-download-button"
          : ".hd-empty-download-button",
      );
      if (document.activeElement !== link) link.focus({ preventScroll: true });
    } else if (focusSelector) {
      const next =
        container.querySelector(focusSelector) ||
        container.querySelector(
          "[data-hd-result], .hd-empty-download-button",
        ) ||
        nav.querySelector('[data-hd-nav="home"]');
      next?.focus({ preventScroll: true });
    }
  }
  const normalize = (s) =>
    s
      .toLocaleLowerCase()
      .normalize("NFD")
      .replace(/\p{M}/gu, "")
      .replaceAll("ß", "ss");
  function renderCards() {
    const terms = normalize(query.value.trim()).split(/\s+/);
    const matches = programmes.filter((item) => {
      if (service !== "all" && service !== item.service) return false;
      if (heading === "hdLive" && item.url) return false;
      const index = normalize(
        [
          site.t(item.title),
          providers[item.service],
          item.description ? site.t(item.description) : "",
        ].join(" "),
      );
      return terms.every((term) => index.includes(term));
    });
    if (activeView === "search") results($("#hd-results"), matches.slice(0, 3));
    if (activeView === "watchlist")
      results(
        $("#hd-saved-list"),
        saved.map((id) => programmeById.get(id)),
      );
    $("#hd-clear").disabled = !query.value;
  }

  function keyButton(key, value, label) {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset[key] = value;
    button.textContent = label;
    return button;
  }
  function renderKeyboard() {
    keyboard.replaceChildren();
    for (const row of numbers ? symbols : letters) {
      const group = document.createElement("div");
      group.className = "hd-key-row";
      for (const character of row)
        group.append(keyButton("hdKey", character, character));
      keyboard.append(group);
    }
    const actions = document.createElement("div");
    actions.className = "hd-key-actions";
    for (const [command, label] of [
      ["mode", numbers ? "hdLetters" : "hdSymbols"],
      ["space", "hdSpace"],
      ["delete", "hdDelete"],
      ["submit", "hdSearch"],
    ]) {
      const key = keyButton("hdCommand", command, site.t(label));
      if (command === "mode") {
        key.setAttribute("aria-label", key.textContent);
        key.textContent = numbers ? "ABC" : "123";
      }
      actions.append(key);
    }
    keyboard.append(actions);
  }

  function setView(view, focus = true) {
    if (activeView === "search" && activeView !== view)
      $("#hd-results").replaceChildren();
    if (activeView === "watchlist" && activeView !== view)
      $("#hd-saved-list").replaceChildren();
    activeView = view;
    scene.dataset.view = view;
    for (const section of views)
      section.hidden = section.dataset.hdView !== view;
    const activeNav = ["search", "youtube"].includes(view) ? "home" : view;
    for (const button of nav.querySelectorAll("[data-hd-nav]")) {
      if (button.dataset.hdNav === activeNav)
        button.setAttribute("aria-current", "true");
      else button.removeAttribute("aria-current");
    }
    if (view === "watchlist" || view === "search") renderCards();
    window.LiquidNav?.refresh(nav);
    document.dispatchEvent(
      new CustomEvent("home-demo:view", { detail: { view } }),
    );
    if (focus) {
      const section = views.find((v) => v.dataset.hdView === view);
      const target =
        view === "search"
          ? query
          : section?.querySelector("button:not(:disabled), a[href]") ||
            (view === "youtube" ? $("#hd-youtube-root") : null) ||
            nav.querySelector("[aria-current]");
      target?.focus({ preventScroll: true });
    }
  }

  function openSearch(label = "hdSearch", filter = "all") {
    heading = label;
    service = filter;
    query.value = "";
    setSearchLabels();
    setView("search");
  }
  function setSearchLabels() {
    const title = $('[data-hd-view="search"] h2');
    if (title) title.textContent = site.t(heading);
    for (const button of $$("[data-hd-service]")) {
      button.setAttribute(
        "aria-pressed",
        String(button.dataset.hdService === service),
      );
    }
  }
  function updateQuery(text) {
    query.value = Array.from(text).slice(0, 96).join("");
    renderCards();
  }
  function edit(command, text) {
    if (command === "type") updateQuery(query.value + text);
    if (command === "space") updateQuery(query.value + " ");
    if (command === "delete")
      updateQuery(Array.from(query.value).slice(0, -1).join(""));
    if (command === "clear") {
      updateQuery("");
      query.focus({ preventScroll: true });
    }
    if (command === "mode") {
      numbers = !numbers;
      renderKeyboard();
      keyboard
        .querySelector("[data-hd-command=mode]")
        .focus({ preventScroll: true });
    }
    if (command === "submit") {
      const target =
        $("#hd-results [data-hd-result], .hd-empty-download-button") || query;
      target.focus({ preventScroll: true });
    }
  }

  function play(item) {
    if (!item || item.url) return;
    playing = item;
    const title = site.t(item.title);
    document.querySelector("#clip-title").textContent = title;
    video.setAttribute("aria-label", title);
    video.poster = new URL(item.art + ".jpg", assetRoot).href;
    video.src = new URL(item.art + ".mp4", assetRoot).href;
    dialog.showModal();
    video.play().catch(() => {});
  }
  dialog.addEventListener("close", () => {
    video.pause();
    playing = null;
  });

  scene.addEventListener("click", (event) => {
    const button = event.target.closest("button, [data-hd-result]");
    if (!button || !scene.contains(button)) return;
    const {
      hdNav,
      hdOpen,
      hdApp,
      hdService,
      hdSave,
      hdResult,
      hdKey,
      hdCommand,
    } = button.dataset;
    if (hdNav) setView(hdNav);
    else if (hdOpen === "youtube") setView("youtube");
    else if (hdOpen === "live" || hdOpen === "library")
      openSearch(hdOpen === "live" ? "hdLive" : "hdLibrary");
    else if (
      button.id === "hd-search-open" ||
      button.hasAttribute("data-hd-search")
    )
      openSearch();
    else if (hdApp) openSearch("hdSearch", hdApp);
    else if (hdService) {
      service = hdService;
      heading = "hdSearch";
      setSearchLabels();
      renderCards();
    } else if (hdResult) play(programmeById.get(hdResult));
    else if (hdSave) toggleSaved(hdSave);
    else if (hdKey) edit("type", hdKey);
    else if (hdCommand) edit(hdCommand);
    else if (button.id === "hd-clear") edit("clear");
    else if (button.id === "hd-quick-toggle") {
      const panel = $("#hd-quick-panel");
      panel.hidden = !panel.hidden;
      button.setAttribute("aria-expanded", String(!panel.hidden));
    }
  });
  brightness.addEventListener("input", () => {
    scene.style.setProperty(
      "--hd-shade",
      String((Number(brightness.max) - Number(brightness.value)) / 100),
    );
  });

  function directionalFocus(from, key) {
    const rect = from.getBoundingClientRect();
    const x = rect.x + rect.width / 2,
      y = rect.y + rect.height / 2;
    const horizontal = key === "ArrowLeft" || key === "ArrowRight";
    const sign = key === "ArrowLeft" || key === "ArrowUp" ? -1 : 1;
    let nearest = null,
      best = Infinity;
    for (const node of $$(
      "button:not(:disabled), a[href], input:not(:disabled)",
    )) {
      if (node === from || !node.getClientRects().length) continue;
      const r = node.getBoundingClientRect();
      const dx = r.x + r.width / 2 - x,
        dy = r.y + r.height / 2 - y;
      const ahead = sign * (horizontal ? dx : dy);
      if (ahead <= 2) continue;
      const side = Math.abs(horizontal ? dy : dx);
      const score = ahead + side * 6;
      if (score < best) {
        best = score;
        nearest = node;
      }
    }
    if (nearest) {
      nearest.focus({ preventScroll: true });
      nearest.scrollIntoView({
        block: "nearest",
        inline: "nearest",
        behavior: "instant",
      });
    }
    return Boolean(nearest);
  }
  scene.addEventListener("keydown", (event) => {
    if (
      event.defaultPrevented ||
      event.isComposing ||
      event.altKey ||
      event.ctrlKey ||
      event.metaKey ||
      document.querySelector("dialog[open]")
    )
      return;
    const key = event.key;
    if (key === "Escape" && activeView !== "home") {
      event.preventDefault();
      setView("home");
    } else if (
      ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(key) &&
      (event.target === query ||
        !event.target.closest(
          "input, textarea, select, [contenteditable=true]",
        ))
    ) {
      if (directionalFocus(event.target, key)) event.preventDefault();
    } else if (activeView === "search") {
      if (key === "Backspace" || key === "Delete") {
        event.preventDefault();
        edit("delete");
      } else if (key === "Enter" && event.target === query) {
        event.preventDefault();
        edit("submit");
      } else if (
        Array.from(key).length === 1 &&
        (key !== " " || event.target === query)
      ) {
        event.preventDefault();
        edit("type", key);
      }
    }
  });
  query.addEventListener("paste", (event) => {
    const text = event.clipboardData?.getData("text/plain");
    if (text) {
      event.preventDefault();
      edit("type", text.replace(/[\r\n]+/g, " "));
    }
  });

  function showTime() {
    const now = new Date();
    const time = $("#hd-clock");
    time.textContent = now.toLocaleTimeString(site.language, {
      hour: "2-digit",
      minute: "2-digit",
    });
    time.dateTime = now.toTimeString().slice(0, 5);
  }
  function translate() {
    query.placeholder = site.t("hdSearchPrompt");
    showTime();
    const focused = document.activeElement;
    const key = keyboard.contains(focused) ? focused.dataset : null;
    setSearchLabels();
    renderKeyboard();
    renderCards();
    if (key?.hdKey || key?.hdCommand) {
      [...keyboard.querySelectorAll("button")]
        .find((b) =>
          key.hdKey
            ? b.dataset.hdKey === key.hdKey
            : b.dataset.hdCommand === key.hdCommand,
        )
        ?.focus({ preventScroll: true });
    }
    if (playing) {
      document.querySelector("#clip-title").textContent = site.t(playing.title);
      video.setAttribute("aria-label", site.t(playing.title));
    }
  }
  loadSaved();
  setView("home", false);
  site.ready.then(translate);
  setInterval(showTime, 30_000);
  document.addEventListener("opennava:site-language", translate);
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) {
      loadSaved();
      renderCards();
    }
  });
})();
