const tvFrame = document.querySelector("#demo-tv");
const viewport = document.querySelector("#tv-viewport");

function loadTelevision(profile) {
  if (document.getElementById("tv-panel").hidden) return;
  const view = profile === "senior" ? "senior" : "full";
  const url = "./demo/" + view + "/index.html";
  if (tvFrame.getAttribute("src") !== url) tvFrame.src = url;
}

document.addEventListener("navatv:profile", (event) =>
  loadTelevision(event.detail),
);
loadTelevision(document.body.dataset.profile);

// Both the website profile selector and the launcher's own Switch view button control this TV.
window.addEventListener("message", (event) => {
  const message = event.data;
  if (
    event.origin !== location.origin ||
    event.source !== tvFrame.contentWindow ||
    message?.source !== "navatv-demo" ||
    message.type !== "profile"
  )
    return;
  if (message.profile === "full" || message.profile === "senior")
    showProfile(message.profile);
});

new ResizeObserver(() => {
  const width = viewport.clientWidth;
  const height = viewport.clientHeight;
  const scale = Math.min(width / 1280, height / 720);
  const x = (width - 1280 * scale) / 2;
  const y = (height - 720 * scale) / 2;
  tvFrame.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
}).observe(viewport);

document.querySelector("#expand-tv").addEventListener("click", async () => {
  if (viewport.requestFullscreen && document.fullscreenEnabled) {
    try {
      await viewport.requestFullscreen();
      tvFrame.contentWindow.focus();
      return;
    } catch {
      /* The browser can still present the launcher in its own tab. */
    }
  }
  window.open(tvFrame.src, "_blank", "noopener");
});
