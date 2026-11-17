const guide = "https://github.com/fcore247/opennava/blob/main/AGENTS.md";
const profiles = {
  full: {
    name: "Full launcher",
    section: "full-launcher",
    preview:
      "Illustrative preview of the full launcher with an alpine film and media apps",
  },
  senior: {
    name: "Senior view",
    section: "senior-view",
    preview:
      "Illustrative senior home screen with large buttons for ARD, ZDF, ARTE, live TV and recordings",
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
  document.querySelector("#profile-name").textContent = profile.name;
  document.dispatchEvent(
    new CustomEvent("navatv:profile", { detail: selected }),
  );
  const link = guide + "#" + profile.section;
  document.querySelector("#agent-guide").href = link;
  promptText.textContent =
    "Help me install OpenNava on my TV using the " +
    profile.name +
    " profile (" +
    selected +
    "). Follow " +
    link +
    ". Guide me through connecting the device, " +
    "then use the project’s setup tool with --profile " +
    selected +
    ". If I also want the Android companion, help me install and pair it.";
  clearTimeout(resetTimer);
  copyButton.querySelector("span").textContent = "Copy setup prompt";
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
    field.setAttribute("aria-label", "Setup prompt");
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
    copyButton.querySelector("span").textContent = "Prompt copied";
    copyStatus.textContent = "Ready to paste into your agent.";
    clearTimeout(resetTimer);
    resetTimer = setTimeout(() => {
      copyButton.querySelector("span").textContent = "Copy setup prompt";
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
    copyStatus.textContent = "Select Copy to copy the highlighted prompt.";
  }
});
