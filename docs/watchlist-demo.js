(() => {
  "use strict";
  const films = {
    alpine: {
      provider: "ARD",
      address: "ardmediathek.de",
      art: "./full/assets/mountains.jpg",
    },
    ocean: {
      provider: "ARTE",
      address: "arte.tv",
      art: "./full/assets/ocean.jpg",
    },
    netflix: {
      provider: "Netflix",
      address: "netflix.com/de/title/80049832",
      art: "./full/assets/earth.jpg",
    },
  };
  const site = window.OpenNavaSite;
  const titles = {
    alpine: "alpine",
    ocean: "oceanFilm",
    netflix: "netflixFilm",
  };
  const kinds = {
    alpine: "ardFilm",
    ocean: "arteFilm",
    netflix: "netflixLink",
  };
  const storageKey = "openNavaLunaDemo.v1";
  const $ = (selector) => document.querySelector(selector);
  let selected = "alpine";
  let saved = [];
  try {
    const stored = JSON.parse(sessionStorage.getItem(storageKey) || "[]");
    if (Array.isArray(stored))
      saved = [...new Set(stored.filter((id) => Object.hasOwn(films, id)))];
  } catch {
    /* The demo can also run in memory. */
  }
  const saveButton = $("#save-film");
  const tv = $("#demo-tv");
  const deviceTabs = [$("#luna-tab"), $("#tv-tab")];
  const themePicker = $("#luna-theme");
  function applyTheme() {
    $(".browser-mock").dataset.lunaTheme = themePicker.value;
  }
  try {
    const theme = sessionStorage.getItem("openNavaLunaTheme");
    if ([...themePicker.options].some((option) => option.value === theme))
      themePicker.value = theme;
  } catch {
    /* Use the initial palette when storage is unavailable. */
  }
  applyTheme();
  themePicker.addEventListener("change", () => {
    applyTheme();
    try {
      sessionStorage.setItem("openNavaLunaTheme", themePicker.value);
    } catch {}
  });

  function renderBrowser() {
    const film = films[selected];
    $("#address").textContent = film.address;
    $("#film-kind").textContent = site.t(kinds[selected]);
    $("#film-title").textContent = site.t(titles[selected]);
    $("#film-description").textContent = site.t(selected + "Desc");
    $("#film-art").src = film.art;
    const marked = saved.includes(selected);
    saveButton.setAttribute("aria-pressed", String(marked));
    saveButton.querySelector("span").textContent = marked
      ? site.t("saved")
      : site.t("bookmark");
    saveButton
      .querySelector("use")
      .setAttribute("href", marked ? "#i-check" : "#i-bookmark");
    for (const button of document.querySelectorAll("[data-film]")) {
      button.setAttribute(
        "aria-pressed",
        String(button.dataset.film === selected),
      );
    }
  }
  function persist() {
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(saved));
    } catch {
      /* In-memory session. */
    }
    renderBrowser();
  }
  function remove(id) {
    if (!saved.includes(id)) return false;
    saved = saved.filter((entry) => entry !== id);
    persist();
    return true;
  }
  // The embedded launcher consumes the same bridge shape as the Android TV client.
  window.openNavaLunaDemo = Object.freeze({
    list: () =>
      saved.map((id) => ({
        id,
        title: site.t(titles[id]),
        provider: films[id].provider,
      })),
    remove,
    getSettings: window.OpenNavaDemoSettings.read,
    openSettings: () => {
      location.assign(site.pageURL("tvsettings/"));
      return true;
    },
    t: site.t,
  });
  $("#film-list").addEventListener("click", (event) => {
    const button = event.target.closest("[data-film]");
    if (!button) return;
    selected = button.dataset.film;
    renderBrowser();
  });
  saveButton.addEventListener("click", () => {
    const removed = remove(selected);
    if (!removed) {
      saved.unshift(selected);
      persist();
    }
    $("#demo-status").textContent = site.t(removed ? "removed" : "added", {
      title: site.t(titles[selected]),
    });
  });
  function selectDevice(tab, focus = false) {
    const useTV = tab === deviceTabs[1];
    for (const button of deviceTabs) {
      const active = tab === button;
      button.tabIndex = active ? 0 : -1;
      button.setAttribute("aria-selected", String(active));
      document.getElementById(button.getAttribute("aria-controls")).hidden =
        !active;
    }
    window.LiquidNav?.refresh($(".device-tabs"));
    if (useTV) loadTelevision(document.body.dataset.profile);
    else if (tv.getAttribute("src") !== "about:blank") tv.src = "about:blank";
    if (focus) tab.focus();
  }
  for (const tab of deviceTabs) {
    tab.addEventListener("click", () => selectDevice(tab));
    tab.addEventListener("keydown", (event) => {
      let target;
      if (event.key === "ArrowRight" || event.key === "ArrowLeft")
        target = deviceTabs.find((item) => item !== tab);
      if (event.key === "Home") target = deviceTabs[0];
      if (event.key === "End") target = deviceTabs[1];
      if (target) {
        event.preventDefault();
        selectDevice(target, true);
      }
    });
  }
  $("#start-tv").addEventListener("click", () =>
    selectDevice(deviceTabs[1], true),
  );
  function openLinkedDevice() {
    if (
      location.hash === "#preview" ||
      new URL(location.href).searchParams.has("profile")
    )
      selectDevice(deviceTabs[1]);
  }
  openLinkedDevice();
  window.addEventListener("hashchange", openLinkedDevice);
  renderBrowser();
  document.addEventListener("opennava:site-language", () => {
    renderBrowser();
    if (tv.getAttribute("src") !== "about:blank") {
      try {
        tv.contentWindow?.NavaHome?.watchlistChanged();
      } catch {}
    }
  });
})();
