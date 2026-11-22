(() => {
  "use strict";
  // Older releases and shared links used fragments on the single-page website.
  const current = new URL(location.href);
  const routes = {
    "#demo": "./demo/",
    "#preview": "./demo/",
    "#produkte": "./produkte/",
    "#downloads": "./produkte/",
    "#rust-prototype": "./handy/#rust-prototype",
    "#setup": "./einrichten/",
    "#questions": "./einrichten/",
  };
  const profile = current.searchParams.get("profile");
  const destination =
    routes[current.hash] ||
    (["full", "senior"].includes(profile) ? "./demo/" : null);
  if (destination) {
    const next = new URL(destination, current);
    next.search = current.search;
    if (current.hash === "#preview" || current.hash === "#questions")
      next.hash = current.hash;
    location.replace(next.href);
    return;
  }

  const clip = document.querySelector("#clip-dialog");
  document
    .querySelector("#close-film")
    .addEventListener("click", () => clip.close());
})();
