/* Native OpenNava home: real broadcasts, live television and installed apps. */
(() => {
  'use strict';
  const cache = {items: [], live: [], loadedAt: 0, loading: false, error: ''};
  let renderedWatchlist;
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const validId = value => typeof value === 'string' && /^[A-Za-z0-9._:-]{1,180}$/.test(value);
  const text = (value, limit = 250) => typeof value === 'string' ? value.trim().slice(0, limit) : '';
  const duration = value => typeof value === 'number' && Number.isFinite(value) && value > 0
    ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2,'0')}` : text(value,24);
  function thumbnail(value) {
    if (typeof value === 'string' && /^assets\/(earth|forest|hills|mountains|ocean)\.jpg$/.test(value)) return value;
    try {
      const url = new URL(value);
      return url.origin === 'https://nava.local' && /^\/mediathek\/image\/[A-Za-z0-9._:-]+$/.test(url.pathname) && !url.search && !url.hash ? url.href : '';
    } catch { return ''; }
  }
  function entries(result) {
    if (result?.error) throw new Error(text(result.error));
    const items = Array.isArray(result?.items) ? result.items : (result?.groups || []).flatMap(group => group.items || []);
    return [...new Map(items.filter(item => item && validId(item.id) && text(item.title)).map(item => [item.id, {
      id: item.id, title: text(item.title), author: text(item.author, 100), channel: text(item.channel || item.provider, 32).toUpperCase(),
      subtitle: text(item.subtitle, 220), thumbnail: thumbnail(item.thumbnail), duration: duration(item.duration),
      publishedAt: item.publishedAt, isLive: !!item.isLive
    }])).values()].sort((a,b) => (Date.parse(b.publishedAt) || 0) - (Date.parse(a.publishedAt) || 0)).slice(0, 30);
  }
  function dateLabel(value) {
    const date = new Date(value);
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return new Intl.DateTimeFormat('en-GB',{day:'2-digit',month:'2-digit',timeZone:'UTC'}).format(date);
    return value && Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat('en-GB', {
      day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin'
    }).format(date) : '';
  }
  function metadata(item) {
    return [item.channel || item.author, dateLabel(item.publishedAt), item.isLive ? 'Live' : item.duration].filter(Boolean).join(' · ');
  }
  // Local scenery used only as soft, blurred ambience when a title has no cover of its own.
  const ambience = ['forest', 'mountains', 'ocean', 'hills', 'earth'];
  function ambientArt(seed) {
    let hash = 0;
    for (const char of String(seed)) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
    return `assets/${ambience[hash % ambience.length]}.jpg`;
  }
  // Plain broadcaster names from the live list; no invented logos or monograms.
  function liveNames() {
    return cache.live.slice(0, 3).map(item => item.title.replace(/\s+(live|HD)$/i, '')).join(' · ');
  }
  function entry(kind, iconName, title, subtitle, attributes) {
    return `<button class="nava-entry" data-entry="${kind}" ${attributes}><span class="nava-entry-icon" aria-hidden="true">${icon(iconName)}</span>
      <span class="nava-entry-copy"><strong>${esc(title)}</strong>${subtitle ? `<small>${esc(subtitle)}</small>` : ''}</span></button>`;
  }

  function watchlist() {
    try {
      const items = JSON.parse(window.NavaTV?.getLunaWatchlist?.() || '[]');
      return items.filter(item => item && validId(item.id) && text(item.title) && text(item.provider));
    } catch { return []; }
  }

  function showWatchlist(focusId, removeFocus = false) {
    const items = watchlist();
    if (!items.length) {
      if (panel.open) closePanel();
      render();
      focusHome();
      return;
    }
    const rows = items.map(item => `<div class="luna-programme">
      <button class="luna-title" data-luna-play="${esc(item.id)}"><small>${esc(item.provider)}</small><strong>${esc(item.title)}</strong></button>
      <button class="luna-remove" data-luna-remove="${esc(item.id)}" aria-label="Remove ${esc(item.title)}">Remove</button></div>`).join('');
    openPanel(`${closeButton()}<div class="panel-body"><h2 id="panel-title">Luna watchlist</h2><div class="luna-programmes">${rows}</div></div>`,
      focusId && items.some(item => item.id === focusId) ? `[data-luna-${removeFocus ? 'remove' : 'play'}="${focusId}"]` : '[data-luna-play]', 'luna-watchlist');
  }

  function focusHome() {
    (main.querySelector('.nava-home-actions .primary') || main.querySelector('button'))?.focus({preventScroll:true});
  }

  function wake() {
    if (watchlist().length) window.navaNativeHome?.();
  }

  function watchlistChanged() {
    if (state.view !== 'home') return;
    const items = watchlist();
    if (renderedWatchlist === JSON.stringify(items)) return;
    render(items);
    if (panel.open && panel.classList.contains('luna-watchlist')) {
      const button = document.activeElement;
      showWatchlist(button?.dataset.lunaPlay || button?.dataset.lunaRemove, !!button?.dataset.lunaRemove);
    }
  }

  function render(saved = watchlist()) {
    if (!window.NavaTV || state.view !== 'home') return;
    const focused = document.activeElement;
    const focusKey = focused?.dataset?.lunaPlay || focused?.dataset?.homePlay || focused?.dataset?.homeAction || focused?.dataset?.app;
    const wasInside = main.contains(focused);
    const heroFocused = wasInside && focused.matches('.nava-home-actions .primary');
    renderedWatchlist = JSON.stringify(saved);
    const latest = saved[0];
    const hero = cache.items.find(item => /tagesschau/i.test(item.title)) || cache.items[0];
    const thumb = !latest && hero?.thumbnail;
    const title = latest?.title || hero?.title || 'Media library';
    const source = latest?.provider || (hero ? metadata(hero) : '');
    const heading = latest ? 'Saved from Luna' : hero ? 'Featured programme' : 'On demand';
    const action = latest ? `data-luna-play="${esc(latest.id)}"` : hero ? `data-home-play="${esc(hero.id)}"` : 'data-home-action="mediathek"';
    const actionLabel = latest ? 'Open title' : hero ? 'Watch now' : 'Browse shows';
    const art = thumb ? `<img src="${esc(thumb)}" alt="">` : `<img class="is-ambient" src="${esc(ambientArt(title))}" alt="">`;
    const names = liveNames();
    const live = cache.live.length ? entry('live', 'live', 'Live TV', names, `data-home-play="${esc(cache.live[0].id)}"`)
      : entry('live', 'live', 'Live TV', 'Watch the broadcasters live', 'data-home-action="live"');
    main.innerHTML = `<div id="nava-home" class="nava-home ${thumb ? 'has-cover' : 'no-cover'}">
      <div class="nava-art" aria-hidden="true">${art}</div>
      <section class="nava-hero" aria-labelledby="home-title">
        <p class="nava-eyebrow">${latest ? icon('bookmark') : ''}<span>${heading}</span></p>
        <h1 id="home-title">${esc(title)}</h1>
        ${source ? `<p class="nava-hero-meta">${esc(source)}</p>` : ''}
        <div class="nava-home-actions"><button class="primary" ${action}>${icon('play')}${actionLabel}</button>
          ${latest ? `<button class="secondary" data-home-action="luna-list">Luna watchlist (${saved.length})</button>` : ''}</div>
      </section>
      <nav class="nava-entries" aria-label="Quick access">${live}${entry('youtube', 'youtube', 'YouTube', 'Search and watch', 'data-home-action="youtube"')}</nav></div>`;
    if (heroFocused) focusHome();
    else if (wasInside && focusKey) [...main.querySelectorAll('button')].find(button => (button.dataset.lunaPlay || button.dataset.homePlay || button.dataset.homeAction || button.dataset.app) === focusKey)?.focus({preventScroll:true});
    if (!cache.loading && Date.now() - cache.loadedAt > 5 * 60 * 1000) refresh();
  }
  async function refresh() {
    if (cache.loading || !window.NavaTube?.requestMediathek) return;
    cache.loading = true;
    cache.error = '';
    // Record the attempt as well: an offline device must not enter a render/retry loop.
    cache.loadedAt = Date.now();
    render();
    const results = await Promise.allSettled([
      window.NavaTube.requestMediathek({method:'home',channel:'all'}),
      window.NavaTube.requestMediathek({method:'live',channel:'all'})
    ]);
    for (let index = 0; index < results.length; index++) {
      try {
        const result = results[index];
        if (result.status === 'rejected') throw result.reason;
        const next = entries(result.value);
        if (index === 1) {
          const rank = item => { const position = [/das erste/i,/^zdf\b/i,/arte/i,/tagesschau24/i,/phoenix/i].findIndex(pattern => pattern.test(item.title)); return position < 0 ? 20 : position; };
          next.sort((a,b) => rank(a) - rank(b));
        }
        cache[index === 0 ? 'items' : 'live'] = next;
      } catch { if (index === 0) cache.error = 'Could not load recent shows.'; }
    }
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
      case 'luna-list': showWatchlist(); break;
      case 'mediathek': navigate('mediathek'); break;
      case 'live': window.NavaTube?.showLive?.(); break;
      case 'youtube': navigate('youtube'); break;
    }
  });
  window.NavaHome = Object.freeze({render, refresh, focusHome, watchlistChanged, wake});
})();
