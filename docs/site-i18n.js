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
    const picker = document.querySelector("#site-language");
    if (picker) {
      picker.replaceChildren(
        new Option(t("auto"), "auto"),
        ...Object.entries(languages).map(
          ([code, name]) => new Option(name, code),
        ),
      );
      picker.value = preference() || "auto";
    }
    document.documentElement.lang = language;
    document.documentElement.dataset.siteLanguage = language;
    document.dispatchEvent(new Event("opennava:site-language"));
  }
  async function load() {
    const id = ++revision;
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
    try {
      if (next) localStorage.setItem(preferenceKey, next);
      else localStorage.removeItem(preferenceKey);
    } catch {}
    return load();
  }
  document
    .querySelector("#site-language")
    ?.addEventListener("change", (event) => change(event.target.value));
  window.addEventListener("languagechange", load);
  window.addEventListener("popstate", load);
  const ready = load();
  window.OpenNavaSite = Object.freeze({
    languages,
    t,
    ready,
    supported,
    get language() {
      return language;
    },
  });
})();
