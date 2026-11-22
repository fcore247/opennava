import { lunaWordmark } from './vendor/branding.js';
import {
  applyColorTheme,
  colorTheme,
  colorThemesHTML,
  DEEP_THEME,
  themeIconURL,
} from './vendor/color-themes.js';
import { clockHTML, isTimeQuery, mountClock } from './vendor/flipclock.js';
import { startBackgroundHTML, startWaves } from './vendor/start-background.js';
import { label, languageSite, locale, paletteName } from './adapters/i18n.js';

const $ = (selector) => document.querySelector(selector);
const tabsHost = $('#demo-tabs');
const view = $('#demo-view');
const background = $('#demo-background');
const address = $('#demo-address');
const panel = $('#demo-panel');
const panelBody = $('#demo-panel-body');
const keyPrefix = 'lunaWebsiteDemo.';
const systemDark = matchMedia('(prefers-color-scheme: dark)');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const notes = new Map();
const tabs = [];
let nextID = 1;
let active;
let stopView;
let stopWaves;
let palette = colorTheme();
let mode = readLocal('mode');
if (!['system', 'light', 'dark'].includes(mode)) mode = 'system';

function readLocal(name) {
  try {
    return localStorage.getItem(keyPrefix + name);
  } catch {
    return null;
  }
}
function writeLocal(name, value) {
  try {
    localStorage.setItem(keyPrefix + name, value);
    return true;
  } catch {
    return false;
  }
}
function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
const glyphs = {
  home: '<path d="m3 11 9-8 9 8v9H15v-6H9v6H3z"/>',
  theme:
    '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5a8.5 8.5 0 0 1 0 17z" fill="currentColor" stroke="none"/>',
  gesture: '<path d="M4 17c6-1 12-9 10-13-1.5-2.5-7 3-6 9 .7 5 4 6 7 1s6-3 5 2"/>',
  note: '<path d="M4 4h16v10l-6 6H4zM14 20v-6h6M8 8h8M8 11h4"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.5 2"/>',
  address: '<circle cx="10.5" cy="10.5" r="6"/><path d="m15 15 5 5"/>',
};
function icon(name) {
  return '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">' + glyphs[name] + '</svg>';
}
for (const id of ['home', 'theme', 'gesture', 'note']) $('#demo-' + id).innerHTML = icon(id);
$('.demo-address-icon').innerHTML = icon('address');
$('#demo-tab-new').textContent = '+';
$('#demo-panel-close').textContent = '×';
$('#demo-panel-close').className = 'demo-panel-close';

const pageNames = {
  home: 'Home',
  clock: 'Clock',
  gestures: 'Gestures',
  external: 'Page',
  unknown: 'Page',
};
function renderChrome() {
  const labels = {
    '#demo-home': 'OpenHome',
    '#demo-theme': 'Colors',
    '#demo-gesture': 'Gestures',
    '#demo-note': 'OpenNote',
    '#demo-tab-new': 'NewTab',
    '#demo-panel-close': 'Close',
    '#demo-tabs': 'Tabs',
  };
  for (const [selector, key] of Object.entries(labels)) {
    const control = $(selector);
    control.setAttribute('aria-label', label(key));
    if (control.tagName === 'BUTTON') control.title = label(key);
  }
  address.placeholder = label('Hint');
  address.setAttribute('aria-label', label('Address'));
  renderTabs();
}
function renderTabs() {
  tabsHost.replaceChildren();
  for (const tab of tabs) {
    const wrap = element('div', 'demo-tab-wrap');
    const select = element('button', 'demo-tab');
    const title =
      tab.page === 'external' ? new URL(tab.command).hostname : label(pageNames[tab.page]);
    if (tab.page === 'external') select.title = tab.command;
    select.type = 'button';
    select.id = 'demo-tab-' + tab.id;
    select.dataset.id = tab.id;
    select.setAttribute('role', 'tab');
    select.setAttribute('aria-selected', String(tab === active));
    select.setAttribute('aria-controls', 'demo-view');
    select.tabIndex = tab === active ? 0 : -1;
    select.append(element('span', '', title));
    select.onclick = () => {
      activate(tab);
      focusTab();
    };
    const close = element('button', 'demo-tab-close', '×');
    close.type = 'button';
    close.title = label('CloseTab') + ' · ' + title;
    close.setAttribute('aria-label', close.title);
    close.onclick = () => closeTab(tab);
    wrap.append(select, close);
    tabsHost.append(wrap);
  }
  // Reveal only the tab strip, so opening a tab leaves the enclosing page and input still.
  const selected = tabsHost
    .querySelector('[aria-selected="true"]')
    .parentElement.getBoundingClientRect();
  const strip = tabsHost.getBoundingClientRect();
  if (selected.right > strip.right) tabsHost.scrollLeft += selected.right - strip.right;
  else if (selected.left < strip.left) tabsHost.scrollLeft -= strip.left - selected.left;
  view.setAttribute('aria-labelledby', 'demo-tab-' + active.id);
}
function focusTab() {
  const selected = tabsHost.querySelector('[aria-selected="true"]');
  selected?.focus({ preventScroll: true });
}
function newTab(focus = true) {
  const tab = { id: nextID++, page: 'home', notePage: 'home', command: '', lastLetter: '' };
  tabs.push(tab);
  activate(tab);
  if (focus) address.focus();
}
function closeTab(tab) {
  const index = tabs.indexOf(tab);
  tabs.splice(index, 1);
  if (!tabs.length) return newTab();
  if (tab === active) activate(tabs[Math.min(index, tabs.length - 1)]);
  else renderTabs();
  focusTab();
}
function activate(tab) {
  active = tab;
  address.value = active.command;
  renderTabs();
  renderView();
}
tabsHost.addEventListener('keydown', (event) => {
  if (event.target.getAttribute('role') !== 'tab') return;
  const index = tabs.indexOf(active);
  const offsets = {
    ArrowLeft: (index + tabs.length - 1) % tabs.length,
    ArrowRight: (index + 1) % tabs.length,
    Home: 0,
    End: tabs.length - 1,
  };
  if (Object.hasOwn(offsets, event.key)) {
    event.preventDefault();
    activate(tabs[offsets[event.key]]);
    focusTab();
  } else if (event.key === 'Delete') {
    event.preventDefault();
    closeTab(active);
  }
});
function navigate(page, command = '') {
  active.page = page;
  active.command = command;
  if (page !== 'gestures') active.notePage = page === 'external' ? command.split('#')[0] : page;
  active.lastLetter = '';
  activate(active);
  view.focus({ preventScroll: true });
}
function action(key, run, glyph) {
  const button = element('button', 'demo-action');
  button.type = 'button';
  if (glyph) button.innerHTML = icon(glyph);
  button.append(element('span', '', label(key)));
  button.onclick = run;
  return button;
}
function startBackdrop() {
  stopWaves?.();
  background.innerHTML = startBackgroundHTML;
  const host = background.firstElementChild;
  stopWaves = startWaves(host.querySelector('canvas'), host, reducedMotion.matches);
}
function applyAppearance(persistPalette = false) {
  document.documentElement.dataset.theme =
    mode === 'system' ? (systemDark.matches ? 'dark' : 'light') : mode;
  applyColorTheme(palette, persistPalette);
  if (active?.page === 'home') startBackdrop();
}
systemDark.addEventListener('change', () => {
  if (mode === 'system') applyAppearance();
});
reducedMotion.addEventListener('change', () => {
  if (active?.page === 'home') startBackdrop();
});

function renderHome() {
  startBackdrop();
  const start = element('section', 'demo-start');
  start.innerHTML = lunaWordmark;
  const logo = element('img', 'st-logo');
  logo.alt = '';
  logo.src = themeIconURL();
  start.prepend(logo);
  const time = element('time', 'demo-time');
  const greeting = element('span', 'demo-greeting');
  const date = element('span', 'demo-date');
  const actions = element('div', 'demo-actions');
  actions.append(
    action('Colors', () => openPanel('theme'), 'theme'),
    action('OpenClock', () => navigate('clock', 'time'), 'clock'),
    action('Gestures', () => navigate('gestures', 'gestures'), 'gesture'),
  );
  start.append(time, greeting, date, actions);
  view.append(start);
  function tick() {
    const now = new Date();
    const hour = now.getHours();
    time.dateTime = now.toISOString();
    time.textContent = now.toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' });
    greeting.textContent = label(
      hour < 5 ? 'Night' : hour < 11 ? 'Morning' : hour < 18 ? 'Afternoon' : 'Evening',
    );
    date.textContent = now.toLocaleDateString(locale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
    });
  }
  tick();
  const timer = setInterval(tick, 1000 * 10);
  return () => clearInterval(timer);
}
function renderGestures() {
  const pad = element('section', 'demo-gesture-pad');
  const result = element('output', 'demo-gesture-result');
  if (active.lastLetter) result.textContent = label('Result', { letter: active.lastLetter });
  const guide = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  guide.setAttribute('viewBox', '0 0 56 56');
  guide.setAttribute('width', '52');
  guide.setAttribute('height', '52');
  guide.setAttribute('aria-hidden', 'true');
  const points = window.__lunaLetterPaths.O.map(([x, y]) =>
    [6 + 44 * x, 6 + 44 * y].join(','),
  ).join(' ');
  guide.innerHTML =
    '<polyline points="' +
    points +
    '" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" fill="none" opacity=".65"/><circle cx="28" cy="6" r="4" fill="currentColor"/>';
  pad.append(
    guide,
    element('h2', '', label('Draw')),
    element('p', '', label('DrawHint')),
    result,
    action('OpenNote', () => openPanel('note')),
  );
  view.append(pad);
  return window.__fmCircle({
    target: pad,
    enabled: () => !panel.open,
    onLetter(letter) {
      active.lastLetter = letter;
      result.textContent = label('Result', { letter });
      if (letter === 'O') openPanel('note');
    },
  });
}
function renderView() {
  stopView?.();
  stopWaves?.();
  stopWaves = stopView = null;
  view.replaceChildren();
  background.replaceChildren();
  if (active.page === 'home') stopView = renderHome();
  else if (active.page === 'clock') {
    view.innerHTML = clockHTML();
    stopView = mountClock(view.firstElementChild);
  } else if (active.page === 'gestures') stopView = renderGestures();
  else {
    const content = element('section', 'demo-external');
    if (active.page === 'external') {
      const url = new URL(active.command);
      content.append(element('h2', '', url.hostname), element('p', '', label('External')));
      const open = element('a', 'demo-action', label('OpenLink'));
      open.href = url.href;
      open.target = '_blank';
      open.rel = 'noopener noreferrer';
      content.append(open);
    } else content.append(element('p', '', label('Unknown')));
    content.append(action('OpenHome', () => navigate('home')));
    view.append(content);
  }
}

function updatePalettePanel() {
  for (const button of panelBody.querySelectorAll('[data-mode]')) {
    button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
  }
  for (const button of panelBody.querySelectorAll('[data-color]')) {
    button.setAttribute('aria-pressed', String(button.dataset.color === palette));
  }
}
function renderPalettePanel() {
  const modes = element('div', 'demo-modes');
  for (const id of ['system', 'light', 'dark']) {
    const key = id[0].toUpperCase() + id.slice(1);
    const modeButton = action(key, () => {
      mode = id;
      writeLocal('mode', mode);
      applyAppearance();
      updatePalettePanel();
    });
    modeButton.dataset.mode = id;
    modes.append(modeButton);
  }
  const template = document.createElement('template');
  template.innerHTML = colorThemesHTML();
  const chooser = template.content.querySelector('.color-themes');
  chooser.setAttribute('aria-label', label('Palette'));
  for (const button of chooser.querySelectorAll('[data-color]')) {
    const id = button.dataset.color;
    button.querySelector('.palette-name').textContent = paletteName(id);
    button.onclick = () => {
      palette = id;
      if (id === DEEP_THEME) {
        mode = 'dark';
        writeLocal('mode', mode);
      }
      applyAppearance(true);
      updatePalettePanel();
    };
  }
  panelBody.replaceChildren(modes, chooser);
  updatePalettePanel();
}
function renderNotePanel() {
  const key = 'note.' + active.notePage;
  const editor = element('textarea', 'demo-note-text');
  editor.placeholder = label('NoteHint');
  editor.setAttribute('aria-label', label('Note'));
  const stored = readLocal(key);
  editor.value = notes.get(key) ?? stored ?? '';
  const status = element(
    'p',
    'demo-note-status',
    stored && stored === editor.value ? label('Saved') : '',
  );
  status.setAttribute('role', 'status');
  editor.oninput = () => {
    notes.set(key, editor.value);
    status.textContent = writeLocal(key, editor.value) ? label('Saved') : '';
  };
  panelBody.replaceChildren(editor, status);
}
function renderPanel(kind) {
  $('#demo-panel-title').textContent = label(kind === 'theme' ? 'Colors' : 'Note');
  if (kind === 'theme') renderPalettePanel();
  else renderNotePanel();
}
function openPanel(kind) {
  panel.dataset.kind = kind;
  renderPanel(kind);
  if (!panel.open) panel.showModal();
  if (kind === 'note') panelBody.querySelector('textarea').focus();
  else panelBody.querySelector('[data-color][aria-pressed="true"]').focus({ preventScroll: true });
}
$('#demo-panel-close').onclick = () => panel.close();
panel.addEventListener('click', (event) => {
  if (event.target !== panel) return;
  const box = panel.getBoundingClientRect();
  if (
    event.clientX < box.left ||
    event.clientX > box.right ||
    event.clientY < box.top ||
    event.clientY > box.bottom
  )
    panel.close();
});
$('#demo-tab-new').onclick = () => newTab();
$('#demo-home').onclick = () => navigate('home');
$('#demo-theme').onclick = () => openPanel('theme');
$('#demo-gesture').onclick = () => navigate('gestures', 'gestures');
$('#demo-note').onclick = () => openPanel('note');

function submitAddress() {
  const raw = address.value.trim();
  const command = raw.toLowerCase();
  if (['theme', 'themes', 'farben', 'farbwelten'].includes(command)) {
    address.value = active.command;
    openPanel('theme');
  } else if (['notes', 'note', 'notiz', 'notizen'].includes(command)) {
    address.value = active.command;
    openPanel('note');
  } else if (['home', 'start', 'startseite', ''].includes(command)) navigate('home');
  else if (['gestures', 'gesten'].includes(command)) navigate('gestures', command);
  else if (isTimeQuery(command)) navigate('clock', command);
  else {
    let url;
    try {
      // Accept an entered web address. Other text stays in the local demo.
      const host = /^[\w-]+(?:\.[\w-]+)+(?::\d+)?(?:[/?#]|$)/i.test(raw);
      const candidate = new URL(host ? 'https://' + raw : raw);
      if (
        ['https:', 'http:'].includes(candidate.protocol) &&
        !candidate.username &&
        !candidate.password
      )
        url = candidate;
    } catch {}
    navigate(url ? 'external' : 'unknown', url ? url.href : raw);
  }
}
// A sandboxed embed does not need the browser's form-navigation permission.
let paletteTimer;
address.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.isComposing) {
    event.preventDefault();
    clearTimeout(paletteTimer);
    submitAddress();
  }
});
address.addEventListener('input', (event) => {
  clearTimeout(paletteTimer);
  if (!event.isComposing && /^(themes?|farben|farbwelten)$/i.test(address.value.trim())) {
    paletteTimer = setTimeout(submitAddress, 275);
  }
});
address.addEventListener('blur', () => clearTimeout(paletteTimer));
$('#demo-url-form').onsubmit = (event) => {
  event.preventDefault();
  submitAddress();
};
address.addEventListener('focus', () => address.select());

function localize() {
  if (!active) return;
  document.documentElement.lang = locale();
  const focused = document.activeElement;
  const restoreTab = focused?.getAttribute('role') === 'tab';
  const actionIndex = Array.from(view.querySelectorAll('button,a')).indexOf(focused);
  const modeID = focused?.dataset.mode;
  const colorID = focused?.dataset.color;
  const noteSelection = focused?.matches('.demo-note-text')
    ? [focused.selectionStart, focused.selectionEnd]
    : null;
  renderChrome();
  renderView();
  if (panel.open) {
    renderPanel(panel.dataset.kind);
    if (noteSelection) {
      const editor = panelBody.querySelector('textarea');
      editor.focus({ preventScroll: true });
      editor.setSelectionRange(...noteSelection);
    } else if (modeID || colorID) {
      Array.from(panelBody.querySelectorAll('button'))
        .find((button) =>
          modeID ? button.dataset.mode === modeID : button.dataset.color === colorID,
        )
        ?.focus({ preventScroll: true });
    }
  } else if (restoreTab) focusTab();
  else if (actionIndex >= 0)
    view.querySelectorAll('button,a')[actionIndex]?.focus({ preventScroll: true });
}

await languageSite()?.ready;
document.documentElement.lang = locale();
applyAppearance();
newTab(false);
renderChrome();
document.addEventListener('opennava:site-language', localize);
window.addEventListener('message', (event) => {
  if (
    window.parent === window ||
    event.source !== window.parent ||
    event.origin !== location.origin
  )
    return;
  if (event.data?.type === 'luna-demo:language' && event.data.language === languageSite()?.language)
    localize();
});
if (window.parent !== window)
  window.parent.postMessage({ type: 'luna-demo:ready' }, location.origin);
window.addEventListener('pagehide', () => {
  stopView?.();
  stopWaves?.();
  stopView = stopWaves = null;
});
window.addEventListener('pageshow', (event) => {
  if (event.persisted) localize();
});
