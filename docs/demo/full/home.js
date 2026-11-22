/* Native OpenNava home: real broadcasts, live television and installed apps. */
(() => {
  'use strict';
  const cache = {live: [], loadedAt: 0, loading: false};
  let renderedWatchlist;
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const validId = value => typeof value === 'string' && /^[A-Za-z0-9._:-]{1,180}$/.test(value);
  const text = (value, limit = 250) => typeof value === 'string' ? value.trim().slice(0, limit) : '';
  function entries(result) {
    if (result?.error) throw new Error(text(result.error));
    const items = Array.isArray(result?.items) ? result.items : (result?.groups || []).flatMap(group => group.items || []);
    return [...new Map(items.filter(item => item && validId(item.id) && text(item.title)).map(item => [item.id, {
      id: item.id, title: text(item.title), channel: text(item.channel || item.provider, 32).toUpperCase()
    }])).values()].slice(0, 30);
  }

  const defaults = {tiles:['luna','live','library'], libraries:['ard','zdf','arte'], language:'auto', appearance:'graphite', showClock:true, showWatchlistCount:true, defaultSearchService:'auto'};
  const labels = {luna:['watchlist','Luna watchlist'],live:['live','Live TV'],library:['library','Media library'],apps:['apps','Apps'],youtube:['','YouTube'],netflix:['','Netflix'],search:['search','Search'],settings:['settings','Settings'],ard:['','ARD'],zdf:['','ZDF'],arte:['','ARTE'],home:['home','Home']};
  const dockIcons = {luna:'bookmark', live:'live', library:'motion', apps:'grid', youtube:'video', netflix:'monitor', search:'search', settings:'settings', ard:'monitor', zdf:'monitor', arte:'monitor', home:'home'};
  const t = (key,fallback,args) => window.NavaI18n.t(key,fallback,args);
  const label = id => labels[id][0] ? t(...labels[id]) : labels[id][1];
  let config = defaults, settingsKey = '';
  function settingsChanged() {
    let settings;
    try { settings = {...defaults, ...JSON.parse(window.NavaTV?.getLunaSettings?.() || '{}')}; } catch { settings = defaults; }
    const key = JSON.stringify(settings);
    if (key === settingsKey) return;
    settingsKey = key;
    config = settings;
    window.NavaI18n.setLanguage(settings.language);
    document.body.dataset.tvAppearance = settings.appearance;
    document.getElementById('clock').hidden = !settings.showClock;
    updateClock();
    updateNavigation();
    if (state.view === 'home') render();
    else if (state.view === 'search') window.FullSearch.render();
    else if (state.view === 'apps') window.renderNativeApps?.();
    if (panel.open && panel.classList.contains('tv-settings-dialog')) openSettingsMenu();
  }
  function dockItem(kind) {
    const action = kind === 'luna' ? 'luna-list' : kind;
    return `<button class="nava-dock-item" data-dock="${kind}" data-home-action="${action}">
      <span class="nava-card-art" aria-hidden="true"></span><span class="nava-dock-icon" aria-hidden="true">${icon(dockIcons[kind])}</span>
      <span class="nava-dock-label">${esc(label(kind))}</span></button>`;
  }
  function appsWatchlistShortcut() {
    return config.tiles.includes('luna') ? '' : `<button class="app-card app-watchlist" data-home-action="luna-list">${icon('bookmark')}${esc(label('luna'))}</button>`;
  }
  function updateNavigation() {
    const nav = document.getElementById('bottom-navigation');
    const active = panel.open && panel.classList.contains('tv-settings-dialog') ? 'settings' : state.view;
    const buttons = [...nav.querySelectorAll('[data-bottom]')];
    if (!buttons.length) {
      nav.innerHTML = ['home','search','apps','settings'].map(id => `<button data-bottom="${id}">${icon(dockIcons[id])}<span></span></button>`).join('');
    }
    nav.querySelectorAll('[data-bottom]').forEach(btn => {
      const id = btn.dataset.bottom, caption = btn.querySelector('span');
      if (caption.textContent !== label(id)) caption.textContent = label(id);
      if (id === active) btn.setAttribute('aria-current','page'); else btn.removeAttribute('aria-current');
    });
  }
  function openSettingsMenu() {
    openPanel(`<div class="panel-body tv-settings-body"><h2 id="panel-title">${esc(label('settings'))}</h2>
      <p>${esc(t('pcNotice','Manage settings on your computer or use Quick settings.'))}</p>
      <div class="tv-settings-actions"><button data-tv-settings="quick">${icon('settings')}${esc(t('quick','Quick settings'))}</button>
      <button data-tv-settings="luna">${icon('external')}${esc(t('openLuna','Open settings in Luna Browser'))}</button></div>
      <p class="tv-settings-status" role="status"></p></div>`, '[data-tv-settings="quick"]', 'tv-settings-dialog');
    updateNavigation();
  }

  function watchlist() {
    try {
      const items = JSON.parse(window.NavaTV?.getLunaWatchlist?.() || '[]');
      return items.filter(item => item && validId(item.id) && text(item.title) && text(item.provider));
    } catch { return []; }
  }

  function showWatchlist(focusId, removeFocus = false, allowEmpty = false, selector = '') {
    const items = watchlist();
    if (!items.length && !allowEmpty) {
      if (panel.open) closePanel();
      render();
      focusHome();
      return;
    }
    const remove = t('remove','Remove');
    const rows = items.map(item => `<div class="luna-programme">
      <button class="luna-title" data-luna-play="${esc(item.id)}"><small translate="no">${esc(item.provider)}</small><strong translate="no">${esc(item.title)}</strong></button>
      <button class="luna-remove" data-luna-remove="${esc(item.id)}" aria-label="${esc(remove+' '+item.title)}">${esc(remove)}</button></div>`).join('');
    const body = items.length ? `<div class="luna-programmes">${rows}</div>` : `<p class="luna-empty">${esc(t('empty','Nothing saved yet. Mark a programme in Luna Browser and it appears here.'))}</p>`;
    openPanel(`<button class="icon-button dialog-close" data-action="close" aria-label="${esc(t('close','Close'))}">${icon('close')}</button><div class="panel-body"><h2 id="panel-title">${esc(label('luna'))}</h2>${body}</div>`,
      selector || (focusId && items.some(item => item.id === focusId) ? `[data-luna-${removeFocus ? 'remove' : 'play'}="${focusId}"]` : items.length ? '[data-luna-play]' : '[data-action="close"]'), 'luna-watchlist');
  }

  function focusHome(onWake = false) {
    const luna = main.querySelector('[data-dock="luna"]'), hasTitles = watchlist().length > 0;
    const target = hasTitles ? luna : main.querySelector('[data-dock="live"]');
    (target || main.querySelector('button'))?.focus({preventScroll:true});
    if (hasTitles && !luna && onWake && !panel.open) showWatchlist();
  }

  function wake() {
    if (watchlist().length) window.navaNativeHome?.();
  }

  // A delivery only updates the quiet counter; it never moves the focus.
  function watchlistChanged() {
    const items = watchlist();
    if (renderedWatchlist === JSON.stringify(items)) return;
    if (state.view === 'home') render(items);
    else renderedWatchlist = JSON.stringify(items);
    if (panel.open && panel.classList.contains('luna-watchlist')) {
      const button = document.activeElement;
      const keep = button?.dataset.action === 'close' ? '[data-action="close"]' : '';
      showWatchlist(button?.dataset.lunaPlay || button?.dataset.lunaRemove, !!button?.dataset.lunaRemove, true, keep);
    }
  }

  function render(saved = watchlist()) {
    if (!window.NavaTV || state.view !== 'home') return;
    const layout = JSON.stringify([config.tiles, window.NavaI18n.language]);
    const current = main.querySelector('.nava-dock');
    renderedWatchlist = JSON.stringify(saved);
    if (!current || current.dataset.layout !== layout) {
      const inMain = main.contains(document.activeElement);
      const previous = inMain && document.activeElement.dataset.dock;
      main.innerHTML = `<section id="nava-home" class="nava-home" aria-label="${esc(label('home'))}">
        <div class="nava-dock">${config.tiles.map(dockItem).join('')}</div>
        <button class="nava-home-search" data-home-action="search">${icon('search')}<span>${esc(t('searchPrompt','What would you like to watch?'))}</span>${icon('chevron')}</button></section>`;
      main.querySelector('.nava-dock').dataset.layout = layout;
      if (returnFocus && !returnFocus.isConnected && returnFocus.dataset.dock)
        returnFocus = main.querySelector(`[data-dock="${returnFocus.dataset.dock}"]`) || main.querySelector('button');
      if (inMain) (previous && main.querySelector(`[data-dock="${previous}"]`) || main.querySelector('button')).focus({preventScroll:true});
    }
    const luna = main.querySelector('[data-dock="luna"]');
    if (luna) {
      const count = config.showWatchlistCount ? saved.length : 0;
      const caption = label('luna') + (count ? ', '+t('savedCount','{count} saved',{count}) : '');
      luna.setAttribute('aria-label',caption);
      let badge = luna.querySelector('.nava-dock-count');
      if (!count) badge?.remove();
      else {
        if (!badge) { badge = document.createElement('b'); badge.className='nava-dock-count'; badge.setAttribute('aria-hidden','true'); luna.append(badge); }
        badge.textContent=count>99?'99+':String(count);
      }
    }
    const live = main.querySelector('[data-dock="live"]');
    if (live && cache.live[0]) live.dataset.homePlay = cache.live[0].id;
    if (live && !cache.loading && Date.now()-cache.loadedAt>300000) refresh();
  }
  async function refresh() {
    if (cache.loading || !window.NavaTube?.requestMediathek) return;
    cache.loading = true;
    // Record the attempt as well: an offline device must not enter a render/retry loop.
    cache.loadedAt = Date.now();
    try {
      const next = entries(await window.NavaTube.requestMediathek({method:'live',channel:'all'}));
      const rank = item => { const position = [/das erste/i,/^zdf\b/i,/arte/i,/tagesschau24/i,/phoenix/i].findIndex(pattern => pattern.test(item.title)); return position < 0 ? 20 : position; };
      cache.live = next.sort((a,b) => rank(a) - rank(b));
    } catch {}
    cache.loading = false;
    render();
  }
  document.addEventListener('click', event => {
    const action = event.target.closest('#nava-home [data-luna-play], .luna-watchlist [data-luna-play], .luna-watchlist [data-luna-remove]');
    if (action) {
      if (action.dataset.lunaPlay) window.NavaTV.openLunaProgramme(action.dataset.lunaPlay);
      else if (window.NavaTV.removeLunaProgramme(action.dataset.lunaRemove)) watchlistChanged();
      else toast('Could not remove this title.');
      return;
    }
    const button = event.target.closest('[data-home-action],[data-bottom],[data-tv-settings]');
    if (!button) return;
    if (button.dataset.tvSettings === 'quick') { openSettings(); updateNavigation(); return; }
    if (button.dataset.tvSettings === 'luna') {
      const success = window.NavaTV.requestLunaSettings?.();
      panel.querySelector('.tv-settings-status').textContent = success ? t('openRequested','Request sent to Luna Browser on your computer.') : t('pairHint','Connect Luna Browser to this TV first.');
      return;
    }
    if (button.dataset.homePlay) { window.NavaTube?.playMediathek(button.dataset.homePlay); return; }
    const target = button.dataset.homeAction || button.dataset.bottom;
    switch (target) {
      case 'home': navigate('home'); focusHome(); break;
      case 'luna-list': showWatchlist(undefined,false,true); break;
      case 'live': window.NavaTube?.showLive?.(); break;
      case 'apps': navigate('apps'); main.querySelector('button')?.focus(); break;
      case 'settings': openSettingsMenu(); break;
      case 'search': window.FullSearch.open(); break;
      case 'netflix': if (!window.NavaTV.launchApp('com.netflix.ninja')) toast(t('unavailable','This app is unavailable')); break;
      default: window.FullSearch.open(target);
    }
  });
  panel.addEventListener('close', updateNavigation);
  window.NavaHome = Object.freeze({render,refresh,focusHome,watchlistChanged,wake,preferences:()=>config,settingsChanged,openSettingsMenu,updateNavigation,appsWatchlistShortcut});
  if (window.NavaTV) settingsChanged();
})();
