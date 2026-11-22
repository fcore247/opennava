(() => {
  "use strict";
  const root = new URL(".", document.currentScript.src);
  const languages = Object.freeze({
    de: "Deutsch",
    en: "English",
    fr: "Français",
    es: "Español",
    it: "Italiano",
    pt: "Português",
    nl: "Nederlands",
    da: "Dansk",
    sv: "Svenska",
    no: "Norsk",
    fi: "Suomi",
    pl: "Polski",
    cs: "Čeština",
    sk: "Slovenčina",
    hu: "Magyar",
    ro: "Română",
    tr: "Türkçe",
    uk: "Українська",
    ja: "日本語",
    ko: "한국어",
  });
  const dictionaries = new Map();
  const preferenceKey = "openNavaSiteLanguage";
  const pagePaths = new Set(
    ["", "demo/", "produkte/", "einrichten/", "handy/", "download/", "tvsettings/", "luna/", "luna/download/", "sprache/"].map(
      (path) => new URL(path, root).pathname,
    ),
  );
  let messages = {};
  let revision = 0;
  let language = "de";
  function supported(raw) {
    let code =
      typeof raw === "string" ? raw.toLowerCase().split(/[-_]/)[0] : "";
    if (code === "nb" || code === "nn") code = "no";
    return Object.hasOwn(languages, code) ? code : null;
  }
  function preference() {
    const explicit = supported(new URL(location.href).searchParams.get("lang"));
    if (explicit) return explicit;
    try {
      return supported(localStorage.getItem(preferenceKey));
    } catch {
      return null;
    }
  }
  function storePreference(code) {
    try {
      if (code) localStorage.setItem(preferenceKey, code);
      else localStorage.removeItem(preferenceKey);
    } catch {}
  }
  function withLanguage(url, code) {
    if (code) url.searchParams.set("lang", code);
    else url.searchParams.delete("lang");
    return url.href;
  }
  function pageURL(path) {
    return withLanguage(new URL(path, root), preference());
  }
  function syncPageLinks(code) {
    for (const link of document.querySelectorAll("a[href]")) {
      const href = link.getAttribute("href");
      if (href.startsWith("#")) continue;
      const url = new URL(href, document.baseURI);
      const path = url.pathname.replace(/index\.html$/, "").replace(/([^/])$/, "$1/");
      if (url.origin !== root.origin || !pagePaths.has(path)) continue;
      const destination = withLanguage(url, code);
      if (link.href !== destination) link.href = destination;
    }
  }
  function syncLanguageLinks() {
    const destination = new URL("sprache/", root);
    const current = new URL(location.href);
    const onLanguagePage = current.pathname.replace(/index\.html$/, "") === destination.pathname;
    const returnTo = onLanguagePage
      ? current.searchParams.get("return")
      : current.pathname + current.search + current.hash;
    if (returnTo) destination.searchParams.set("return", returnTo);
    const href = withLanguage(destination, preference());
    for (const link of document.querySelectorAll("[data-language-link]")) link.href = href;
  }
  function detect() {
    return (
      preference() ||
      (navigator.languages || [navigator.language])
        .map(supported)
        .find(Boolean) ||
      "en"
    );
  }
  function dictionary(code) {
    if (!dictionaries.has(code))
      dictionaries.set(
        code,
        fetch(new URL("locales/" + code + ".json", root))
          .then((response) => {
            if (!response.ok) throw Error("Locale unavailable");
            return response.json();
          })
          .catch((error) => {
            dictionaries.delete(code);
            throw error;
          }),
      );
    return dictionaries.get(code);
  }
  function t(key, values = {}) {
    return (messages[key] || key).replace(/\{(\w+)\}/g, (token, name) =>
      Object.hasOwn(values, name) ? String(values[name]) : token,
    );
  }
  function localize() {
    for (const node of document.querySelectorAll("[data-i18n]"))
      node.textContent = t(node.dataset.i18n);
    for (const attr of ["aria-label", "content", "title"]) {
      for (const node of document.querySelectorAll("[data-i18n-" + attr + "]"))
        node.setAttribute(attr, t(node.getAttribute("data-i18n-" + attr)));
    }
    syncPageLinks(preference());
    syncLanguageLinks();
    document.documentElement.lang = language;
    document.documentElement.dataset.siteLanguage = language;
    document.dispatchEvent(new Event("opennava:site-language"));
  }
  async function load() {
    const id = ++revision;
    const explicit = supported(new URL(location.href).searchParams.get("lang"));
    if (explicit) storePreference(explicit);
    const target = detect();
    let data,
      code = target;
    try {
      data = await dictionary(target);
    } catch {
      code = "en";
      try {
        data = await dictionary(code);
      } catch {
        return;
      }
    }
    if (id !== revision) return;
    language = code;
    messages = data;
    localize();
  }
  function change(code) {
    const next = supported(code);
    const url = new URL(location.href);
    if (next) url.searchParams.set("lang", next);
    else url.searchParams.delete("lang");
    history.replaceState(null, "", url);
    storePreference(next);
    return load();
  }
  // Setup and demo pages can change their URL after the initial localization.
  // Capture that current location when the language link is used.
  for (const type of ["pointerover", "focusin", "click", "contextmenu"]) {
    document.addEventListener(type, (event) => {
      if (event.target.closest?.("[data-language-link]")) syncLanguageLinks();
    });
  }
  window.addEventListener("languagechange", load);
  window.addEventListener("popstate", load);
  const ready = load();
  window.OpenNavaSite = Object.freeze({
    languages,
    t,
    ready,
    supported,
    pageURL,
    setLanguage: change,
    get preference() {
      return preference() || "auto";
    },
    get language() {
      return language;
    },
  });
})();
