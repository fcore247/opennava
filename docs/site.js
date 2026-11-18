const guide = "https://github.com/fcore247/opennava/blob/main/AGENTS.md";
const profiles = {
  full: {
    name: "Vollmodus",
    section: "full-launcher",
  },
  senior: {
    name: "Seniorenmodus",
    section: "senior-view",
  },
};

const profileRadios = document.querySelectorAll('input[type="radio"]');
const promptText = document.querySelector("#prompt-text");
const copyStatus = document.querySelector("#copy-status");
const copyButton = document.querySelector("#copy-prompt");
let resetTimer;

function showProfile(id, changeURL = true) {
  const profile = Object.hasOwn(profiles, id) ? profiles[id] : profiles.full;
  const selected = profile === profiles.senior ? "senior" : "full";
  document.body.dataset.profile = selected;
  for (const radio of profileRadios) radio.checked = radio.value === selected;
  document.dispatchEvent(
    new CustomEvent("navatv:profile", { detail: selected }),
  );
  const link = guide + "#" + profile.section;
  document.querySelector("#agent-guide").href = link;
  promptText.textContent =
    "Hilf mir, OpenNava auf meinem Fernseher im " +
    profile.name +
    " einzurichten. Folge der Anleitung unter " +
    link +
    " und verwende das Setup-Werkzeug mit --profile " +
    selected +
    ". Hilf mir, das Gerät zu verbinden. Falls ich die Android-Begleitapp möchte, richte auch diese ein.";
  clearTimeout(resetTimer);
  copyButton.querySelector("span").textContent = "Einrichtungshilfe kopieren";
  copyStatus.textContent = "";
  if (changeURL) {
    const url = new URL(location.href);
    url.searchParams.set("profile", selected);
    history.replaceState(null, "", url);
  }
}

for (const radio of profileRadios)
  radio.addEventListener("change", () => showProfile(radio.value));
function readURL() {
  showProfile(new URL(location.href).searchParams.get("profile"), false);
}
window.addEventListener("popstate", readURL);
readURL();

copyButton.addEventListener("click", async () => {
  const text = promptText.textContent;
  let copied = false;
  try {
    await navigator.clipboard.writeText(text);
    copied = true;
  } catch {
    const field = document.createElement("textarea");
    field.value = text;
    field.setAttribute("aria-label", "Einrichtungshilfe");
    field.className = "clipboard-field";
    document.body.append(field);
    field.select();
    try {
      copied = document.execCommand("copy");
    } catch {
      /* Display the prompt for manual copying. */
    }
    field.remove();
    copyButton.focus();
  }
  if (copied) {
    copyButton.querySelector("span").textContent = "Kopiert";
    copyStatus.textContent =
      "Du kannst die Einrichtungshilfe jetzt in deinen KI-Agenten einfügen.";
    clearTimeout(resetTimer);
    resetTimer = setTimeout(() => {
      copyButton.querySelector("span").textContent =
        "Einrichtungshilfe kopieren";
      copyStatus.textContent = "";
    }, 3200);
  } else {
    promptText.closest("details").open = true;
    promptText.focus();
    const range = document.createRange();
    range.selectNodeContents(promptText);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    copyStatus.textContent =
      "Kopiere den markierten Text mit der Kopierfunktion deines Browsers.";
  }
});
