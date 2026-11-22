(() => {
  "use strict";
  const prefs = window.OpenNavaDemoSettings;
  const site = window.OpenNavaSite;
  const $ = (selector) => document.querySelector(selector);
  let draft = prefs.read();
  try {
    const theme = sessionStorage.getItem("openNavaLunaTheme");
    if (["rose", "ocean", "graphite"].includes(theme))
      document.body.dataset.lunaTheme = theme;
  } catch {}
  function selectPane(section) {
    for (const button of document.querySelectorAll("[data-section]")) {
      if (button.dataset.section === section)
        button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    }
    for (const pane of document.querySelectorAll("[data-pane]"))
      pane.hidden = pane.dataset.pane !== section;
  }
  for (const button of document.querySelectorAll("[data-section]"))
    button.addEventListener("click", () => selectPane(button.dataset.section));
  const names = {
    luna: "watchlist",
    ard: "ARD",
    zdf: "ZDF",
    arte: "ARTE",
    youtube: "YouTube",
    netflix: "Netflix",
  };
  const name = (id) =>
    ["ard", "zdf", "arte", "youtube", "netflix"].includes(id)
      ? names[id]
      : site.t(names[id] || id);
  function fill(select, options, value) {
    select.replaceChildren(
      ...options.map(([id, label]) => new Option(label, id)),
    );
    select.value = value;
  }
  function render() {
    const available = prefs.tileIds.filter(
      (id) => !prefs.libraryIds.includes(id) || draft.libraries.includes(id),
    );
    const tiles = $("#tile-selects");
    if (!tiles.children.length) {
      for (let i = 0; i < 3; i++) {
        const label = document.createElement("label"),
          caption = document.createElement("span"),
          select = document.createElement("select");
        select.dataset.slot = String(i);
        label.append(caption, select);
        tiles.append(label);
      }
    }
    for (const select of tiles.querySelectorAll("select")) {
      const i = Number(select.dataset.slot);
      select.previousElementSibling.textContent = site.t("position", {
        n: i + 1,
      });
      fill(
        select,
        available
          .filter((id) => id === draft.tiles[i] || !draft.tiles.includes(id))
          .map((id) => [id, name(id)]),
        draft.tiles[i],
      );
    }
    for (const box of document.querySelectorAll("[name=library]"))
      box.checked = draft.libraries.includes(box.value);
    fill(
      $("#search-provider"),
      ["auto", ...draft.libraries, "youtube", "netflix"].map((id) => [
        id,
        name(id),
      ]),
      draft.defaultSearchService,
    );
    fill(
      $("#tv-language"),
      [["auto", site.t("auto")], ...Object.entries(site.languages)],
      draft.language,
    );
    $("#tv-appearance").value = draft.appearance;
    $("#show-clock").checked = draft.showClock;
    $("#show-count").checked = draft.showWatchlistCount;
  }
  $("#demo-tv-settings").addEventListener("change", (event) => {
    const target = event.target;
    if (target.dataset.slot !== undefined)
      draft.tiles[Number(target.dataset.slot)] = target.value;
    if (target.name === "library")
      draft.libraries = Array.from(
        document.querySelectorAll("[name=library]:checked"),
        (el) => el.value,
      );
    if (target.id === "search-provider")
      draft.defaultSearchService = target.value;
    if (target.id === "tv-language") draft.language = target.value;
    if (target.id === "tv-appearance") draft.appearance = target.value;
    if (target.id === "show-clock") draft.showClock = target.checked;
    if (target.id === "show-count") draft.showWatchlistCount = target.checked;
    draft = prefs.normalize(draft);
    render();
  });
  $("#reload-tv").addEventListener("click", () => {
    draft = prefs.read();
    render();
  });
  $("#demo-tv-settings").addEventListener("submit", (event) => {
    event.preventDefault();
    prefs.save(draft);
    location.assign(site.pageURL("demo/#preview"));
  });
  document.addEventListener("opennava:site-language", render);
  site.ready.then(render);
})();
