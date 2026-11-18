(() => {
  "use strict";
  const films = {
    alpine: {
      title: "Über den Wolken",
      provider: "ARD",
      kind: "ARD · Demofilm",
      address: "ardmediathek.de",
      art: "./demo/full/assets/mountains.jpg",
      description: "Eine Reise an die stillen Seen und Gipfel der Alpen.",
    },
    ocean: {
      title: "Die blaue Stunde",
      provider: "ARTE",
      kind: "ARTE · Demofilm",
      address: "arte.tv",
      art: "./demo/full/assets/ocean.jpg",
      description: "Ein Abend an der Atlantikküste, kurz vor Sonnenuntergang.",
    },
    netflix: {
      title: "Unser Planet",
      provider: "Netflix",
      kind: "Netflix · Titellink",
      address: "netflix.com/de/title/80049832",
      art: "./demo/full/assets/earth.jpg",
      description:
        "Öffne die offizielle Netflix-Titelseite über deine Merkliste.",
    },
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
    $("#film-kind").textContent = film.kind;
    $("#film-title").textContent = film.title;
    $("#film-description").textContent = film.description;
    $("#film-art").src = film.art;
    const marked = saved.includes(selected);
    saveButton.setAttribute("aria-pressed", String(marked));
    saveButton.querySelector("span").textContent = marked
      ? "Vorgemerkt"
      : "Für OpenNava vormerken";
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
        title: films[id].title,
        provider: films[id].provider,
      })),
    remove,
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
    $("#demo-status").textContent = removed
      ? films[selected].title + " wurde von der Demo-Merkliste entfernt."
      : films[selected].title + " ist auf dem Demo-TV vorgemerkt.";
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
    $(".device-tabs").style.setProperty("--tab-x", useTV ? "100%" : "0");
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

  const clip = $("#clip-dialog");
  const video = clip.querySelector("video");
  $("#hero-film").addEventListener("click", () => {
    clip.showModal();
    video.play().catch(() => {
      /* Native controls remain usable. */
    });
  });
  $("#close-film").addEventListener("click", () => clip.close());
  clip.addEventListener("close", () => video.pause());
})();
