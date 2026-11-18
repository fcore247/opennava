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

  const dockIcons = {luna: 'bookmark', live: 'live', library: 'motion', apps: 'grid', youtube: 'video'};
  function dockItem(kind, label, attributes, count = 0, description = label) {
    return `<button class="nava-dock-item" data-dock="${kind}" ${attributes} aria-label="${esc(description)}">
      <span class="nava-dock-icon" aria-hidden="true">${icon(dockIcons[kind])}${count ? `<b class="nava-dock-count">${count > 99 ? '99+' : count}</b>` : ''}</span>
      <span class="nava-dock-label">${esc(label)}</span></button>`;
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
    const rows = items.map(item => `<div class="luna-programme">
      <button class="luna-title" data-luna-play="${esc(item.id)}"><small>${esc(item.provider)}</small><strong>${esc(item.title)}</strong></button>
      <button class="luna-remove" data-luna-remove="${esc(item.id)}" aria-label="Remove ${esc(item.title)}">Remove</button></div>`).join('');
    const body = items.length ? `<div class="luna-programmes">${rows}</div>`
      : '<p class="luna-empty">Nothing saved yet. Mark a programme in Luna Browser and it appears here.</p>';
    openPanel(`${closeButton()}<div class="panel-body"><h2 id="panel-title">Luna watchlist</h2>${body}</div>`,
      selector || (focusId && items.some(item => item.id === focusId) ? `[data-luna-${removeFocus ? 'remove' : 'play'}="${focusId}"]` : items.length ? '[data-luna-play]' : '[data-action="close"]'), 'luna-watchlist');
  }

  // Start and wake: the watchlist is preselected when it holds titles, otherwise Live TV.
  function focusHome() {
    const luna = main.querySelector('[data-dock="luna"]');
    const target = watchlist().length ? luna : main.querySelector('[data-dock="live"]');
    (target || main.querySelector('button'))?.focus({preventScroll:true});
  }

  function wake() {
    if (watchlist().length) window.navaNativeHome?.();
  }

  // A delivery only updates the quiet counter; it never moves the focus.
  function watchlistChanged() {
    if (state.view !== 'home') return;
    const items = watchlist();
    if (renderedWatchlist === JSON.stringify(items)) return;
    render(items);
    if (panel.open && panel.classList.contains('luna-watchlist')) {
      const button = document.activeElement;
      const keep = button?.dataset.action === 'close' ? '[data-action="close"]' : '';
      showWatchlist(button?.dataset.lunaPlay || button?.dataset.lunaRemove, !!button?.dataset.lunaRemove, true, keep);
    }
  }

  // Update the existing dock in place: only the counter, its label and the Live TV target change,
  // so focus and the opener remembered by an open panel stay on the very same elements.
  function updateDock(saved, lunaLabel) {
    const dock = main.querySelector('.nava-dock');
    const luna = dock?.querySelector('[data-dock="luna"]');
    const live = dock?.querySelector('[data-dock="live"]');
    if (!luna || !live) return false;
    luna.setAttribute('aria-label', lunaLabel);
    const icon = luna.querySelector('.nava-dock-icon');
    let badge = icon.querySelector('.nava-dock-count');
    if (!saved.length) badge?.remove();
    else {
      if (!badge) { badge = document.createElement('b'); badge.className = 'nava-dock-count'; icon.append(badge); }
      badge.textContent = saved.length > 99 ? '99+' : String(saved.length);
    }
    const target = cache.live[0];
    if (target) { live.dataset.homePlay = target.id; delete live.dataset.homeAction; }
    else { live.dataset.homeAction = 'live'; delete live.dataset.homePlay; }
    if (!cache.loading && Date.now() - cache.loadedAt > 5 * 60 * 1000) refresh();
    return true;
  }

  function render(saved = watchlist()) {
    if (!window.NavaTV || state.view !== 'home') return;
    const focused = document.activeElement;
    const focusKey = main.contains(focused) ? focused.dataset?.dock : '';
    renderedWatchlist = JSON.stringify(saved);
    const live = cache.live.length ? `data-home-play="${esc(cache.live[0].id)}"` : 'data-home-action="live"';
    const lunaLabel = saved.length ? `Luna watchlist, ${saved.length} saved` : 'Luna watchlist, empty';
    if (updateDock(saved, lunaLabel)) return;
    main.innerHTML = `<div id="nava-home" class="nava-home nava-dock-home"><nav class="nava-dock" aria-label="Home">
      ${dockItem('luna', 'Luna watchlist', 'data-home-action="luna-list"', saved.length, lunaLabel)}
      ${dockItem('live', 'Live TV', live)}
      ${dockItem('library', 'Media library', 'data-home-action="mediathek"')}
      ${dockItem('apps', 'Apps', 'data-home-action="apps"')}
      ${dockItem('youtube', 'YouTube', 'data-home-action="youtube"')}
    </nav></div>`;
    // The opener of an open panel was just replaced: point its focus return at the new twin.
    if (typeof returnFocus !== 'undefined' && returnFocus && !returnFocus.isConnected && returnFocus.dataset?.dock)
      returnFocus = main.querySelector(`[data-dock="${returnFocus.dataset.dock}"]`);
    if (focusKey) main.querySelector(`[data-dock="${focusKey}"]`)?.focus({preventScroll:true});
    if (!cache.loading && Date.now() - cache.loadedAt > 5 * 60 * 1000) refresh();
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
    const button = event.target.closest('#nava-home button');
    if (!button) return;
    if (button.dataset.homePlay) {
      event.preventDefault();
      window.NavaTube?.playMediathek(button.dataset.homePlay);
      return;
    }
    switch (button.dataset.homeAction) {
      case 'luna-list': showWatchlist(undefined, false, true); break;
      case 'mediathek': navigate('mediathek'); break;
      case 'live': window.NavaTube?.showLive?.(); break;
      case 'apps': navigate('apps'); break;
      case 'youtube': navigate('youtube'); break;
    }
  });
  window.NavaHome = Object.freeze({render, refresh, focusHome, watchlistChanged, wake});
})();
