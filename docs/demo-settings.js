(() => {
  "use strict";
  const libraryIds = ["ard", "zdf", "arte"];
  const tileIds = [
    "luna",
    "live",
    "library",
    "apps",
    "youtube",
    "netflix",
    "search",
    ...libraryIds,
    "settings",
  ];
  const defaults = {
    version: 1,
    tiles: ["luna", "live", "library"],
    libraries: libraryIds,
    language: "auto",
    appearance: "graphite",
    showClock: true,
    showWatchlistCount: true,
    defaultSearchService: "auto",
  };
  const storageKey = "openNavaDemoTVSettings.v1";
  let localCopy = defaults;
  function normalize(input = {}) {
    if (!input || typeof input !== "object") input = {};
    const libraries = Array.isArray(input.libraries)
      ? libraryIds.filter((id) => input.libraries.includes(id))
      : [...libraryIds];
    const available = tileIds.filter(
      (id) => !libraryIds.includes(id) || libraries.includes(id),
    );
    const tiles = [
      ...new Set(
        [
          ...(Array.isArray(input.tiles) ? input.tiles : []),
          ...defaults.tiles,
        ].filter((id) => available.includes(id)),
      ),
    ].slice(0, 3);
    const providers = ["auto", ...libraries, "youtube", "netflix"];
    return {
      version: 1,
      tiles,
      libraries,
      language: window.OpenNavaSite?.supported(input.language) || "auto",
      appearance: input.appearance === "rose" ? "rose" : "graphite",
      showClock: input.showClock !== false,
      showWatchlistCount: input.showWatchlistCount !== false,
      defaultSearchService: providers.includes(input.defaultSearchService)
        ? input.defaultSearchService
        : "auto",
    };
  }
  function read() {
    try {
      const saved = sessionStorage.getItem(storageKey);
      if (saved) return normalize(JSON.parse(saved));
    } catch {}
    return normalize(localCopy);
  }
  function save(settings) {
    localCopy = normalize(settings);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(localCopy));
    } catch {}
    return localCopy;
  }
  window.OpenNavaDemoSettings = Object.freeze({
    read,
    save,
    normalize,
    libraryIds,
    tileIds,
  });
})();
