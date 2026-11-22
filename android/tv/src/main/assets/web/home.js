/* Native OpenNava home: real broadcasts, live television and installed apps. */
(() => {
  'use strict';
  const cache = {items: [], live: [], loadedAt: 0, loading: false, error: ''};
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
  function liveCard(item) {
    const mark = /das erste/i.test(item.title) ? '①' : /tagesschau24/i.test(item.title) ? 'ts24' : /^arte/i.test(item.title) ? 'arte' : item.channel || 'ARD';
    return `<button class="nava-live-card" data-home-play="${esc(item.id)}" aria-label="${esc(item.title)} live">
      <span class="nava-live-monogram">${esc(mark)}</span><span class="nava-live-copy"><strong>${esc(item.title)}</strong><small>Watch live TV</small></span>
      <span class="nava-live-dot" aria-hidden="true"></span></button>`;
  }
  function videoCard(item) {
    return `<button class="nava-broadcast-card" data-home-play="${esc(item.id)}" aria-label="Play ${esc(item.title)}">
      <span class="nava-broadcast-art">${item.thumbnail ? `<img src="${esc(item.thumbnail)}" alt="" loading="lazy">` : icon('monitor')}<span class="nava-broadcast-source">${esc(item.channel || item.author || 'Media library')}</span><span class="nava-broadcast-play">${icon('play')}</span></span>
      <strong>${esc(item.title)}</strong><small>${esc([dateLabel(item.publishedAt), item.duration].filter(Boolean).join(' · '))}</small></button>`;
  }
  let layout;
  let folderOpen = null;
  function readLayout() {
    if (typeof window.NavaTV?.homeLayout !== 'function') return null;
    try { return JSON.parse(window.NavaTV.homeLayout()); } catch { return null; }
  }
  function updateLayout() {
    const next = readLayout();
    if (!next || layout?.revision === next.revision) return;
    layout = next;
    render();
    if (folderOpen && panel.open) {
      const folder = layout.items.find(item => item.type === 'folder' && item.id === folderOpen);
      if (folder) showFolder(folder);
      else closePanel();
    }
  }
  function appTile(app) {
    return `<button class="app-card" data-app="${esc(app.id)}" aria-label="Open ${esc(app.name)}">${app.logo}</button>`;
  }
  function folderTile(folder) {
    const icons = folder.apps.map(id => apps.find(app => app.id === id)).filter(Boolean).slice(0, 4)
      .map(app => `<span>${app.logo}</span>`).join('');
    return `<button class="app-card home-folder" data-home-folder="${esc(folder.id)}">
      <span class="home-folder-icons" aria-hidden="true">${icons}</span><span>${esc(folder.name)}</span></button>`;
  }
  function homeApps() {
    if (!layout) layout = readLayout();
    if (!layout?.revision) return `<div class="app-grid">${apps.slice(0, 6).map(appTile).join('')}</div>`;
    const tiles = layout.items.map(item => {
      if (item.type === 'folder') return folderTile(item);
      const app = apps.find(app => app.id === item.package);
      return app ? appTile(app) : '';
    }).join('');
    return `<div class="app-grid home-layout-${layout.layout === 'grid' ? 'grid' : 'row'}">${tiles}</div>`;
  }
  function showFolder(folder) {
    const tiles = folder.apps.map(id => apps.find(app => app.id === id)).filter(Boolean).map(appTile).join('');
    folderOpen = folder.id;
    openPanel(`${closeButton()}<div class="panel-body"><h2 id="panel-title">${esc(folder.name)}</h2>
      <div class="home-folder-contents">${tiles || '<p>No apps in this folder.</p>'}</div></div>`, tiles ? '[data-app]' : 'button');
  }
  panel.addEventListener('close', () => { folderOpen = null; });
  function render() {
    if (!window.NavaTV || state.view !== 'home') return;
    const focused = document.activeElement;
    const focusKey = focused?.dataset?.homePlay || focused?.dataset?.homeAction || focused?.dataset?.homeFolder || focused?.dataset?.app;
    const wasInside = main.contains(focused);
    const phoneLink = typeof window.NavaTV.showPhoneLink === 'function' && window.NavaTV.hasPersonalView()
      ? `<button class="secondary" data-home-action="phone"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/></svg>Pair phone</button>` : '';
    const hero = cache.items.find(item => /tagesschau/i.test(item.title)) || cache.items[0];
    const backdrop = document.getElementById('backdrop');
    if (backdrop) backdrop.style.backgroundImage = hero?.thumbnail
      ? `linear-gradient(90deg,#101312 0%,#101312e0 28%,#10131230 76%),linear-gradient(0deg,#101312,transparent 70%),url("${hero.thumbnail}")`
      : 'radial-gradient(ellipse at 78% 25%,#45635466,transparent 60%),linear-gradient(120deg,#101312,#17251c)';
    main.innerHTML = `<div id="nava-home" class="nava-home">
      <section class="nava-home-hero" aria-label="${hero ? 'Current show' : 'Welcome to OpenNava'}">
        <div class="nava-home-eyebrow"><span></span>${hero ? esc(hero.channel || 'MEDIA LIBRARY') + ' · CATCH UP' : 'YOUR TELEVISION'}</div>
        <h1>${hero ? esc(hero.title) : 'What would you like to watch?'}</h1>
        <p>${hero ? esc(metadata(hero)) : 'Media libraries, YouTube and live TV.'}</p>
        <div class="nava-home-actions">${hero ? `<button class="primary" data-home-play="${esc(hero.id)}">${icon('play')}Watch now</button>` : `<button class="primary" data-home-action="mediathek">${icon('search')}Open media library</button>`}<button class="secondary" data-home-action="search">${icon('search')}Search</button>${phoneLink}</div>
      </section>
      <div class="nava-home-shelves">
        <section class="nava-home-live"><div class="section-heading"><h2>Live now</h2><button class="nava-text-link" data-home-action="live">All channels ${icon('chevron')}</button></div>
          ${cache.live.length ? `<div class="nava-live-grid">${cache.live.slice(0,4).map(liveCard).join('')}</div>` : `<div class="nava-home-note">${cache.loading ? 'Loading channels…' : 'Find live TV in the media library.'}</div>`}</section>
        <section class="app-section"><div class="section-heading"><h2>Your apps</h2><button class="nava-text-link" data-view="apps">All apps ${icon('chevron')}</button></div>${homeApps()}</section>
        <section class="nava-home-latest"><div class="section-heading"><h2>Latest from the libraries</h2><span class="subtle">ARD &amp; ZDF</span></div>
          ${cache.items.length ? `<div class="nava-broadcast-grid">${cache.items.slice(0,8).map(videoCard).join('')}</div>` : `<div class="nava-home-note" role="status">${cache.loading ? 'Loading recent shows…' : esc(cache.error || 'Search for shows in the media library.')} ${!cache.loading ? '<button data-home-action="refresh">Reload</button>' : ''}</div>`}</section>
      </div></div>`;
    if (wasInside && focusKey) [...main.querySelectorAll('button')].find(button => (button.dataset.homePlay || button.dataset.homeAction || button.dataset.homeFolder || button.dataset.app) === focusKey)?.focus({preventScroll:true});
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
          const rank = item => { const position = [/das erste/i,/tagesschau24/i,/^zdf\b/i,/arte/i,/phoenix/i].findIndex(pattern => pattern.test(item.title)); return position < 0 ? 20 : position; };
          next.sort((a,b) => rank(a) - rank(b));
        }
        cache[index === 0 ? 'items' : 'live'] = next;
      } catch { if (index === 0) cache.error = 'Could not load recent shows.'; }
    }
    cache.loading = false;
    render();
  }
  document.addEventListener('click', event => {
    const button = event.target.closest('#nava-home button');
    if (!button) return;
    if (button.dataset.homeFolder) {
      const folder = layout?.items.find(item => item.type === 'folder' && item.id === button.dataset.homeFolder);
      if (folder) showFolder(folder);
      return;
    }
    if (button.dataset.homePlay) {
      event.preventDefault();
      window.NavaTube?.playMediathek(button.dataset.homePlay);
      return;
    }
    switch (button.dataset.homeAction) {
      case 'phone': window.NavaTV.showPhoneLink(); break;
      case 'search': window.navaOpenGlobalSearch?.('mediathek'); break;
      case 'mediathek': navigate('mediathek'); break;
      case 'live': window.NavaTube?.showLive?.(); break;
      case 'refresh': refresh(); break;
    }
  });
  window.NavaHome = Object.freeze({render, refresh, updateLayout});
})();
