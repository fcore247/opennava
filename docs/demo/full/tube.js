/* OpenNava's shared video library. Catalogue data and playback come from native bridges. */
(() => {
  'use strict';

  const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
  const CHANNEL_ID = /^[A-Za-z0-9_-]{8,80}$/;
  const IMAGE_HOSTS = new Set(['i.ytimg.com', 'yt3.googleusercontent.com', 'yt3.ggpht.com']);
  const topics = [
    {label: 'Discover', method: 'home'},
    {label: 'Music', query: 'Musik'},
    {label: 'Technology', query: 'Technik'},
    {label: 'Science', query: 'Wissen Dokumentation'},
    {label: 'Gaming', query: 'Gaming'},
    {label: 'Travel', query: 'Reisedokumentation'}
  ];
  const mediathekTopics = [
    {label: 'Latest', method: 'home'}, {label: 'Live', method: 'live'},
    {label: 'News', query: 'Nachrichten'}, {label: 'Documentaries', query: 'Dokumentation'},
    {label: 'Culture', query: 'Kultur'}
  ];
  const paths = {
    play: '<path d="m9 5 11 7-11 7Z" fill="currentColor" stroke="none"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',
    back: '<path d="m14 5-7 7 7 7"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6.1 6.1A8 8 0 0 1 20 12M4 12a8 8 0 0 0 13.9 5.9"/>',
    keyboard: '<rect x="2" y="5" width="20" height="14" rx="3"/><path d="M6 9h.01M10 9h.01M14 9h.01M18 9h.01M6 12h.01M10 12h.01M14 12h.01M18 12h.01M7 15h10"/>',
    delete: '<path d="M9 5h12v14H9l-7-7Z"/><path d="m12 9 6 6M18 9l-6 6"/>',
    connection: '<path d="M3 8a15 15 0 0 1 18 0M6 12a10 10 0 0 1 12 0M9 16a5 5 0 0 1 6 0"/><circle cx="12" cy="20" r=".7" fill="currentColor"/>',
    arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>'
  };
  paths.tv = '<rect x="2" y="5" width="20" height="14" rx="3"/><path d="m8 1 4 4 4-4M8 23h8"/>';
  const svg = name => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.play}</svg>`;
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
  const clean = (value, length = 300) => typeof value === 'string' ? value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').trim().slice(0, length) : '';
  const view = {provider: 'youtube', method: 'home', query: '', channelId: '', channelName: '', broadcaster: 'all', items: [], groups: [], status: 'idle', message: '', activeRequest: null, activeTopic: 'Discover'};
  const pending = new Map();
  let sequence = 0;
  let keyboard = null;
  let keyboardVisible = false;
  let filtersOpen = false;
  let keyboardReturn = null;
  let noticeTimer = null;
  let active = false;
  let generation = 0;
  let keyboardValue = '';
  let nextOpenMethod = '';
  let nextBroadcaster = '';

  function isOpen() { return active && !!document.getElementById('nava-tube'); }
  function providerLabel(provider = view.provider) { return provider === 'mediathek' ? 'Media library' : 'YouTube'; }
  function providerTopics() { return view.provider === 'mediathek' ? mediathekTopics : topics; }
  function defaultTopic() { return view.provider === 'mediathek' ? 'Latest' : 'Discover'; }
  function validMediaId(value, provider = view.provider) { return typeof value === 'string' && (provider === 'youtube' ? VIDEO_ID.test(value) : value.length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value)); }
  function nativeBridge(provider = view.provider) {
    try { return window.NavaTV && typeof window.NavaTV[provider === 'mediathek' ? 'mediathekRequest' : 'youtubeRequest'] === 'function' ? window.NavaTV : null; }
    catch { return null; }
  }
  function sendNative(bridge, provider, id, payload) { return bridge[provider === 'mediathek' ? 'mediathekRequest' : 'youtubeRequest'](id, JSON.stringify(payload)); }
  function safeThumbnail(value) {
    if (typeof value === 'string' && /^assets\/(earth|forest|hills|mountains|ocean)\.jpg$/.test(value)) return value;
    if (typeof value !== 'string' || value.length > 2048) return '';
    try {
      const url = new URL(value, 'https://nava.local/');
      const allowedHost = IMAGE_HOSTS.has(url.hostname) || (url.hostname === 'nava.local' && url.pathname.startsWith('/mediathek/image/'));
      return url.protocol === 'https:' && allowedHost && !url.username && !url.password
        && (!url.port || url.port === '443') ? url.href : '';
    } catch { return ''; }
  }
  function durationLabel(value) {
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
      const seconds = Math.floor(value);
      const parts = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60];
      return parts[0] ? `${parts[0]}:${String(parts[1]).padStart(2, '0')}:${String(parts[2]).padStart(2, '0')}`
        : `${parts[1]}:${String(parts[2]).padStart(2, '0')}`;
    }
    return clean(value, 24);
  }
  function viewsLabel(value) {
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
      return `${new Intl.NumberFormat('en-GB', {notation: 'compact', maximumFractionDigits: 1}).format(value)} ${value === 1 ? 'view' : 'views'}`;
    }
    return clean(value, 70);
  }
  function dateLabel(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}(?:T|$)/.test(value) || !Number.isFinite(Date.parse(value))) return '';
    return new Intl.DateTimeFormat('en-GB', {day: 'numeric', month: 'short', ...(value.includes('T') ? {hour: '2-digit', minute: '2-digit'} : {}), timeZone: 'Europe/Berlin'}).format(new Date(value));
  }
  function normalizeItems(items, provider = view.provider) {
    const seen = new Set();
    return items.slice(0, 120).flatMap(item => {
      if (!item || typeof item !== 'object' || !validMediaId(item.id, provider) || seen.has(item.id)) return [];
      const title = clean(item.title, 400);
      if (!title) return [];
      seen.add(item.id);
      const channel = ['ARD','ZDF'].includes(clean(item.channel, 20).toUpperCase()) ? clean(item.channel, 20).toUpperCase()
        : ['ard','zdf'].includes(item.provider) ? item.provider.toUpperCase() : '';
      return [{id: item.id, title, author: clean(item.author, 140), subtitle: clean(item.subtitle, 500), thumbnail: safeThumbnail(item.thumbnail), provider: provider === 'mediathek' ? (channel.toLowerCase() || 'mediathek') : 'youtube', channel,
        duration: durationLabel(item.duration), views: viewsLabel(item.views),
        publishedAt: dateLabel(item.publishedAt) ? item.publishedAt : '', dateLabel: dateLabel(item.publishedAt), isLive: item.isLive === true,
        channelId: typeof item.channelId === 'string' && CHANNEL_ID.test(item.channelId) ? item.channelId : ''}];
    });
  }
  function pageToken(value) { return typeof value === 'string' && value.length <= 65536 ? value : ''; }
  function collectItems() { view.items = [...new Map(view.groups.flatMap(group => group.items).map(item => [item.id, item])).values()]; }
  function redundantSearchTitle(title) {
    if (view.method !== 'search' || !view.query) return false;
    const normalize = value => value.normalize('NFKC').toLocaleLowerCase('de').replace(/["'“”„«»]/g, '').replace(/\s+/g, ' ').trim();
    const normalized = normalize(title);
    const match = normalized.match(/^(?:search results for|suchergebnisse für|ergebnisse für)\s*:?\s+(.+)$/);
    return !!match && match[1] === normalize(view.query);
  }
  function catalog() {
    return view.groups.map(group => `<section class="tube-shelf" aria-label="${esc(redundantSearchTitle(group.title) ? heading() : group.title || heading())}">
      ${view.groups.length > 1 && group.title && !redundantSearchTitle(group.title) ? `<h3 class="tube-shelf-title">${esc(group.title)}</h3>` : ''}
      <div class="tube-grid">${group.items.map(card).join('')}</div>
      ${group.pageToken ? `<div class="tube-more-row"><button class="tube-secondary tube-more" data-yt-more="${group.id}" ${group.loading ? 'disabled' : ''}>${group.loading ? '<span class="tube-spinner"></span> Loading…' : `${svg('refresh')}${group.error ? 'Try again' : `Load more ${view.provider === 'mediathek' ? 'shows' : 'videos'}`}`}</button>${group.error ? `<p role="status">${esc(group.error)}</p>` : ''}</div>` : ''}
    </section>`).join('');
  }
  function currentPayload() {
    if (view.provider === 'mediathek') return {method: view.method === 'live' ? 'live' : view.method === 'search' ? 'search' : 'home', ...(view.method === 'search' ? {query: view.query} : {}), channel: view.broadcaster};
    if (view.method === 'search') return {method: 'search', query: view.query};
    if (view.method === 'channel') return {method: 'channel', channelId: view.channelId};
    return {method: 'home'};
  }
  function heading() {
    if (view.method === 'search') return `Results for “${view.query}”`;
    if (view.method === 'channel') return view.channelName ? `Videos by ${view.channelName}` : 'Channel videos';
    if (view.provider === 'mediathek') return view.method === 'live' ? 'Live now' : 'Latest in the library';
    return 'Up next';
  }

  function card(item, index) {
    return `<article class="tube-video">
      <button class="tube-video-open" data-yt-play="${esc(item.id)}" aria-label="Play ${esc(item.title)}">
        <span class="tube-thumbnail">
          <span class="tube-thumbnail-fallback" aria-hidden="true">${svg('play')}</span>
          ${item.thumbnail ? `<img src="${esc(item.thumbnail)}" alt="" loading="${index < 6 ? 'eager' : 'lazy'}" decoding="async" referrerpolicy="no-referrer">` : ''}
          <span class="tube-play-overlay" aria-hidden="true">${svg('play')}</span>
          ${item.isLive ? '<span class="tube-live-badge"><i></i>LIVE</span>' : item.duration ? `<span class="tube-duration">${esc(item.duration)}</span>` : ''}
        </span>
        <span class="tube-video-title">${esc(item.title)}</span>
      </button>
      <div class="tube-video-meta">${view.provider === 'mediathek' ? `<div class="tube-broadcast-meta">${item.channel ? `<span class="tube-sender tube-sender-${item.channel.toLowerCase()}">${item.channel}</span>` : ''}${item.isLive ? '<span>Live stream</span>' : item.dateLabel ? `<span>${esc(item.dateLabel)}</span>` : ''}</div>` : ''}${item.author && item.author !== item.channel ? `<span class="tube-author">${esc(item.author)}</span>` : ''}${view.provider === 'youtube' && item.views ? `<span class="tube-views">${esc(item.views)}</span>` : ''}</div>
    </article>`;
  }
  function skeletons() {
    return `<div class="tube-loading-note" role="status"><span class="tube-spinner"></span>Loading ${view.provider === 'mediathek' ? 'shows' : 'videos'}…</div>
      <div class="tube-grid tube-skeleton-grid" aria-hidden="true">${Array.from({length: 6}, () => '<div class="tube-skeleton"><div></div><span></span><small></small></div>').join('')}</div>`;
  }
  function statusView() {
    const unavailable = view.status === 'unavailable';
    const empty = view.status === 'empty';
    const title = unavailable ? `${providerLabel()} is not connected yet.` : empty ? 'No results found.' : 'Could not load the results.';
    const copy = unavailable ? `This device could not connect to ${view.provider === 'mediathek' ? 'the media library' : 'YouTube'}.`
      : empty ? (view.method === 'search' ? 'Try a different search term or browse another topic.' : 'Try again in a moment.')
        : view.message || 'Check your connection and try again.';
    return `<div class="tube-state"><span class="tube-state-icon">${svg(empty ? 'search' : 'connection')}</span><h3>${title}</h3><p>${esc(copy)}</p>
      <div class="tube-state-actions"><button class="tube-primary" data-yt-action="${empty && view.method === 'search' ? 'keyboard' : 'retry'}">${svg(empty && view.method === 'search' ? 'search' : 'refresh')}${empty && view.method === 'search' ? 'Try a different search' : 'Try again'}</button><button class="tube-secondary" data-yt-action="back">Back to OpenNava</button></div></div>`;
  }
  function render() {
    const host = document.getElementById('main');
    if (!host) return;
    const focused = host.querySelector(':focus');
    let focusSelector = null;
    if (validMediaId(focused?.dataset?.ytPlay || '')) focusSelector = `[data-yt-play="${CSS.escape(focused.dataset.ytPlay)}"]`;
    else if (['youtube','mediathek'].includes(focused?.dataset?.ytProvider)) focusSelector = `[data-yt-provider="${focused.dataset.ytProvider}"]`;
    else if (['all','ard','zdf'].includes(focused?.dataset?.ytBroadcaster)) focusSelector = `[data-yt-broadcaster="${focused.dataset.ytBroadcaster}"]`;
    else if (/^\d+$/.test(focused?.dataset?.ytTopic || '')) focusSelector = `[data-yt-topic="${focused.dataset.ytTopic}"]`;
    else if (/^g\d+$/.test(focused?.dataset?.ytMore || '')) focusSelector = `[data-yt-more="${focused.dataset.ytMore}"]`;
    else if (/^[A-ZÄÖÜß0-9_.-]$/.test(focused?.dataset?.ytLetter || '')) focusSelector = `[data-yt-letter="${focused.dataset.ytLetter}"]`;
    else if (['filters','keyboard','retry','back','space','delete','submit','clear-channel'].includes(focused?.dataset?.ytAction)) focusSelector = `[data-yt-action="${focused.dataset.ytAction}"]`;
    host.innerHTML = `<section id="nava-tube" class="tube-page ${keyboardVisible ? 'tube-editing' : ''} tube-provider-${view.provider}" aria-label="OpenNava ${providerLabel()}">
      <div class="tube-top">
        <div class="tube-intro"><h1>${providerLabel()}</h1></div>
        <div class="tube-search-region"><div class="tube-providers" aria-label="Search area"><button data-yt-provider="mediathek" aria-pressed="${view.provider === 'mediathek'}">${svg('tv')}Media library</button><button data-yt-provider="youtube" aria-pressed="${view.provider === 'youtube'}">${svg('play')}YouTube</button></div>
        <button class="tube-search" data-yt-action="keyboard" aria-label="Search ${providerLabel()}" aria-expanded="${keyboardVisible}" aria-controls="tube-keyboard-slot">${svg('search')}<span class="tube-search-copy"><span id="tube-query" class="${keyboardValue ? '' : 'tube-placeholder'}">${esc(keyboardValue || 'What would you like to watch?')}</span><span class="tube-query-cursor" aria-hidden="true"></span></span><span class="tube-search-key">${svg('keyboard')}</span></button></div>
      </div>
      <div id="tube-keyboard-slot">${keyboardVisible ? keyboardMarkup() : ''}</div>
      <button class="tube-filter-toggle" data-yt-action="filters" aria-expanded="${filtersOpen}" aria-controls="tube-filter-options">Filters</button>
      <div class="tube-toolbar" id="tube-filter-options" ${filtersOpen ? '' : 'hidden'}><div class="tube-topics" aria-label="${providerLabel()} topics">${providerTopics().map((topic, index) => `<button class="tube-topic" data-yt-topic="${index}" aria-pressed="${view.activeTopic === topic.label}">${topic.label}</button>`).join('')}</div>${view.provider === 'mediathek' ? `<div class="tube-broadcasters" aria-label="Channel filter">${[['all','All'],['ard','ARD'],['zdf','ZDF']].map(([id,label]) => `<button data-yt-broadcaster="${id}" aria-pressed="${view.broadcaster === id}">${label}</button>`).join('')}</div>` : ''}<button class="tube-refresh" data-yt-action="retry" aria-label="Refresh videos">${svg('refresh')}</button></div>
      ${view.channelId ? `<div class="tube-context"><span>CHANNEL</span><button data-yt-action="clear-channel" aria-label="Clear channel filter">${esc(view.channelName || 'Selected channel')}${svg('close')}</button></div>` : ''}
      <div class="tube-results-heading"><h2>${esc(heading())}</h2><span>${view.status === 'ready' ? `${view.items.length} ${view.provider === 'mediathek' ? (view.items.length === 1 ? 'show' : 'shows') : (view.items.length === 1 ? 'video' : 'videos')}` : providerLabel().toUpperCase()}</span></div>
      <div id="tube-results" aria-busy="${view.status === 'loading'}">${view.status === 'loading' || view.status === 'idle' ? skeletons() : view.status === 'ready' ? catalog() : statusView()}</div>
      <div class="tube-notice" id="tube-notice" role="status" aria-live="polite"></div>
    </section>`;
    keyboard = keyboardVisible ? document.getElementById('tube-keyboard') : null;
    if (focusSelector) (host.querySelector(focusSelector) || host.querySelector('.tube-search'))?.focus({preventScroll: true});
  }
  function request(payload, topic = '', options = {}) {
    generation++;
    view.method = payload.method;
    view.query = payload.method === 'search' ? payload.query : '';
    if (!options.keepDraft) keyboardValue = view.query;
    if (view.provider === 'mediathek') payload = {...payload, channel: view.broadcaster};
    view.channelId = payload.method === 'channel' ? payload.channelId : '';
    if (payload.method !== 'channel') view.channelName = '';
    view.activeTopic = topic;
    view.items = [];
    view.groups = [];
    view.message = '';
    view.status = 'loading';
    const bridge = nativeBridge();
    if (!bridge) { view.status = 'unavailable'; view.activeRequest = null; if (isOpen()) render(); return; }
    const requestId = `nava-tube-${Date.now()}-${++sequence}`;
    view.activeRequest = requestId;
    if (isOpen()) render();
    const timeout = setTimeout(() => finish(requestId, {error: 'The request timed out. Please try again.'}), 30000);
    pending.set(requestId, {timeout, generation, kind: 'replace', provider: view.provider});
    try {
      const accepted = sendNative(bridge, view.provider, requestId, payload);
      if (accepted === false) finish(requestId, {error: `Could not connect to ${providerLabel()}.`});
    } catch { finish(requestId, {error: `Connection to ${providerLabel()} is unavailable right now.`}); }
  }
  function continueGroup(groupId) {
    const group = view.groups.find(item => item.id === groupId);
    const bridge = nativeBridge();
    if (!group || !group.pageToken || group.loading) return;
    if (!bridge) { group.error = 'The connection is unavailable.'; render(); return; }
    group.loading = true;
    group.error = '';
    const requestId = `nava-tube-${Date.now()}-${++sequence}`;
    const timeout = setTimeout(() => finish(requestId, {error: 'The request timed out. Please try again.'}), 30000);
    pending.set(requestId, {timeout, generation, kind: 'continue', groupId, pageToken: group.pageToken, provider: view.provider});
    render();
    try {
      if (sendNative(bridge, view.provider, requestId, {method: 'continue', pageToken: group.pageToken}) === false)
        finish(requestId, {error: 'Could not load more videos.'});
    } catch { finish(requestId, {error: 'Could not load more videos.'}); }
  }
  function finish(requestId, response) {
    const entry = pending.get(requestId);
    if (!entry) return;
    clearTimeout(entry.timeout);
    pending.delete(requestId);
    if (entry.kind === 'external') {
      if (!response || typeof response !== 'object' || response.error || (!Array.isArray(response.items) && !Array.isArray(response.groups))) {
        entry.reject(new Error(clean(typeof response?.error === 'string' ? response.error : response?.error?.message, 240) || 'Could not load the media library.'));
        return;
      }
      const groups = Array.isArray(response.groups) ? response.groups.slice(0, 12).flatMap(group => group && Array.isArray(group.items) ? [{title: clean(group.title, 180), items: normalizeItems(group.items, 'mediathek'), pageToken: pageToken(group.pageToken)}] : []) : [];
      const items = Array.isArray(response.items) ? normalizeItems(response.items, 'mediathek') : [...new Map(groups.flatMap(group => group.items).map(item => [item.id, item])).values()];
      entry.resolve({items, groups, pageToken: pageToken(response.pageToken)});
      return;
    }
    if (entry.generation !== generation) return;
    if (entry.kind === 'continue') {
      const group = view.groups.find(item => item.id === entry.groupId);
      if (!group) return;
      group.loading = false;
      const result = Array.isArray(response?.items) ? response : Array.isArray(response?.groups) ? response.groups[0] : null;
      if (response?.error || !result || !Array.isArray(result.items)) {
        group.error = clean(typeof response?.error === 'string' ? response.error : response?.error?.message, 180) || 'Could not load more videos.';
      } else {
        const before = group.items.length;
        group.items = [...new Map(group.items.concat(normalizeItems(result.items)).map(item => [item.id, item])).values()];
        group.pageToken = pageToken(result.pageToken);
        if (group.items.length === before && group.pageToken === entry.pageToken) group.pageToken = '';
        collectItems();
      }
      if (isOpen()) render();
      return;
    }
    if (requestId !== view.activeRequest) return;
    view.activeRequest = null;
    if (!response || typeof response !== 'object' || response.error) {
      view.status = 'error';
      view.message = clean(typeof response?.error === 'string' ? response.error : response?.error?.message, 240) || 'Check your connection and try again.';
    } else if (!Array.isArray(response.items) && !Array.isArray(response.groups)) {
      view.status = 'error';
      view.message = `The response from ${providerLabel()} search response could not be read. Try again.`;
    } else {
      const groups = Array.isArray(response.groups) && response.groups.length ? response.groups.slice(0, 12) : [{items: response.items, pageToken: response.pageToken}];
      view.groups = groups.flatMap((group, index) => group && Array.isArray(group.items) ? [{id: `g${index}`, title: clean(group.title, 180), items: normalizeItems(group.items), pageToken: pageToken(group.pageToken), loading: false, error: ''}] : []);
      collectItems();
      view.status = view.items.length ? 'ready' : 'empty';
    }
    if (isOpen()) render();
  }
  function receive(provider, requestId, jsonText) {
    if (typeof requestId !== 'string' || pending.get(requestId)?.provider !== provider) return;
    try {
      if (typeof jsonText !== 'string' || jsonText.length > 2000000) throw new Error('Invalid response');
      finish(requestId, JSON.parse(jsonText));
    } catch { finish(requestId, {error: `The response from ${providerLabel(provider)} search response could not be read.`}); }
  }
  window.navaYouTubeResult = (requestId, jsonText) => receive('youtube', requestId, jsonText);
  window.navaMediathekResult = (requestId, jsonText) => receive('mediathek', requestId, jsonText);
  function requestMediathek(payload = {}) {
    return new Promise((resolve, reject) => {
      const bridge = nativeBridge('mediathek');
      if (!bridge) { reject(new Error('The media library is not connected on this device.')); return; }
      if (!payload || !['home','search','live','continue'].includes(payload.method)) { reject(new Error('Unknown library request.')); return; }
      const requestPayload = {method: payload.method};
      if (payload.method === 'continue') {
        requestPayload.pageToken = pageToken(payload.pageToken);
        if (!requestPayload.pageToken) { reject(new Error('The next page is unavailable.')); return; }
      } else {
        requestPayload.channel = ['ard','zdf'].includes(payload.channel) ? payload.channel : 'all';
        if (payload.method === 'search') {
          requestPayload.query = clean(payload.query, 160);
          if (!requestPayload.query) { reject(new Error('Enter a search term.')); return; }
        }
      }
      const requestId = `nava-tube-${Date.now()}-${++sequence}`;
      const timeout = setTimeout(() => finish(requestId, {error: 'The request timed out. Please try again.'}), 30000);
      pending.set(requestId, {kind: 'external', provider: 'mediathek', timeout, resolve, reject});
      try {
        if (sendNative(bridge, 'mediathek', requestId, requestPayload) === false) finish(requestId, {error: 'Could not connect to the media library.'});
      } catch { finish(requestId, {error: 'The media library is currently unavailable.'}); }
    });
  }
  function announce(message) {
    const notice = document.getElementById('tube-notice');
    if (!notice) return;
    notice.textContent = message;
    notice.classList.add('visible');
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => notice.classList.remove('visible'), 4500);
  }
  function play(id) {
    const item = view.items.find(entry => entry.id === id);
    if (!item || !validMediaId(id)) return;
    if (view.provider === 'mediathek') { playMediathek(id); return; }
    try {
      if (!window.NavaTV || typeof window.NavaTV.playYouTube !== 'function') {
        announce('YouTube playback is unavailable on this device.');
        return;
      }
      const bridge = window.NavaTV;
      const opened = typeof bridge.playYouTubeCard === 'function'
        ? bridge.playYouTubeCard(id, item.title, item.author)
        : bridge.playYouTube(id);
      if (opened === false) { announce('Could not open this video.'); return; }
      announce('Opening video…');
    } catch { announce('Could not open this video. Please try again.'); }
  }
  function playMediathek(id) {
    if (!validMediaId(id, 'mediathek')) return false;
    try {
      if (!window.NavaTV || typeof window.NavaTV.playMediathek !== 'function') { announce('Media library playback is unavailable on this device.'); return false; }
      if (window.NavaTV.playMediathek(id) === false) { announce('Could not open this show.'); return false; }
      announce('Opening show…');
      return true;
    } catch { announce('Could not open this show. Please try again.'); return false; }
  }
  function search(query, topic = '') {
    query = clean(query, 160);
    if (!query) return false;
    closeKeyboard(false);
    request({method: 'search', query}, topic);
    document.querySelector('.tube-search')?.focus({preventScroll: true});
    return true;
  }
  function openChannel(channelId, channelName = '') {
    if (typeof channelId !== 'string' || !CHANNEL_ID.test(channelId)) return false;
    if (view.provider !== 'youtube') selectProvider('youtube');
    view.channelName = clean(channelName, 140);
    request({method: 'channel', channelId});
    return true;
  }
  function open(options = {}) {
    filtersOpen = false;
    const provider = ['youtube','mediathek'].includes(options.provider) ? options.provider : view.provider;
    const changedProvider = provider !== view.provider;
    const previousQuery = view.query;
    const live = provider === 'mediathek' && (options.method === 'live' || nextOpenMethod === 'live');
    const broadcaster = provider === 'mediathek' ? nextBroadcaster : '';
    nextOpenMethod = ''; nextBroadcaster = '';
    const providerHasFocus = !!document.activeElement?.dataset?.ytProvider;
    if (isOpen() && !changedProvider && !live && !broadcaster) return;
    if (changedProvider) {
      view.provider = provider;
      view.broadcaster = 'all';
      view.channelId = '';
      view.channelName = '';
      view.method = previousQuery ? 'search' : 'home';
      view.activeTopic = previousQuery ? '' : defaultTopic();
    }
    if (broadcaster) view.broadcaster = broadcaster;
    active = true;
    render();
    if (broadcaster) { closeKeyboard(false); request({method:'home'}, 'Latest'); }
    else if (live) { closeKeyboard(false); request({method: 'live'}, 'Live'); }
    else if (changedProvider || view.status === 'idle' || view.status === 'unavailable') request(currentPayload(), view.activeTopic, {keepDraft: changedProvider});
    if (!keyboardVisible && !providerHasFocus) requestAnimationFrame(() => document.querySelector('.tube-search')?.focus({preventScroll: true}));
  }
  function selectProvider(provider) {
    if (!['youtube','mediathek'].includes(provider) || provider === view.provider) return;
    if (typeof navigate === 'function') navigate(provider);
    else open({provider});
    document.querySelector(`[data-yt-provider="${provider}"]`)?.focus({preventScroll: true});
  }
  function showBroadcaster(channel) {
    if (!['ard','zdf'].includes(channel)) return;
    nextBroadcaster = channel;
    navigate('mediathek');
  }
  function showLive() {
    view.broadcaster = 'all';
    nextOpenMethod = 'live';
    if (typeof navigate === 'function') navigate('mediathek');
    else open({provider: 'mediathek', method: 'live'});
  }
  function close() {
    active = false;
    closeKeyboard(false);
    clearTimeout(noticeTimer);
  }
  function focusSearch(options = {}) {
    open(options);
    openKeyboard();
    keyboard?.querySelector('[data-yt-letter]')?.focus();
  }
  function exit() {
    close();
    const event = new CustomEvent('nava-tube-exit', {cancelable: true});
    if (window.dispatchEvent(event) && typeof navigate === 'function') navigate('home');
  }

  function keyboardMarkup() {
    const rows = ['QWERTYUIOP', 'ASDFGHJKL', 'ZXCVBNMÄÖÜß-_.'];
    return `<section id="tube-keyboard" class="tube-keyboard" aria-label="On-screen keyboard"><div class="tube-keyboard-letters">
      <div class="tube-keys">${rows.map(row => Array.from(row).map(letter => `<button type="button" data-yt-letter="${esc(letter)}">${esc(letter)}</button>`).join('')).join('')}</div>
      <div class="tube-number-keys">${'1234567890'.split('').map(number => `<button type="button" data-yt-letter="${number}">${number}</button>`).join('')}</div></div>
      <div class="tube-keyboard-actions"><button type="button" data-yt-action="space">Space</button><button type="button" data-yt-action="delete" aria-label="Delete last character">${svg('delete')}Delete</button><button type="button" class="tube-keyboard-submit" data-yt-action="submit">Search ${svg('arrow')}</button></div></section>`;
  }
  function openKeyboard() {
    if (keyboardVisible) return;
    const slot = document.getElementById('tube-keyboard-slot');
    if (!slot) return;
    keyboardReturn = document.activeElement;
    keyboardVisible = true;
    document.getElementById('nava-tube').classList.add('tube-editing');
    slot.innerHTML = keyboardMarkup();
    keyboard = document.getElementById('tube-keyboard');
    syncSearchBar();
    requestAnimationFrame(() => keyboard?.querySelector('[data-yt-letter]')?.focus());
  }
  function closeKeyboard(restore = true) {
    if (!keyboardVisible) return;
    keyboardVisible = false;
    keyboard?.remove();
    keyboard = null;
    document.getElementById('nava-tube')?.classList.remove('tube-editing');
    syncSearchBar();
    if (restore) (keyboardReturn?.isConnected ? keyboardReturn : document.querySelector('.tube-search'))?.focus({preventScroll: true});
  }
  function syncSearchBar() {
    const field = document.getElementById('tube-query');
    if (field) { field.textContent = keyboardValue || 'What would you like to watch?'; field.classList.toggle('tube-placeholder', !keyboardValue); }
    document.querySelector('.tube-search')?.setAttribute('aria-expanded', String(keyboardVisible));
  }
  function typeCharacter(character) {
    if (!keyboardVisible) return;
    keyboardValue = (keyboardValue + character).slice(0, 160);
    syncSearchBar();
  }
  function deleteCharacter() { keyboardValue = Array.from(keyboardValue).slice(0, -1).join(''); syncSearchBar(); }
  function moveKeyboardFocus(key) {
    if (typeof moveFocus === 'function') { moveFocus(key); return; }
    const candidates = [...document.querySelectorAll('button')].filter(button => !button.disabled && button.getClientRects().length);
    const current = document.activeElement;
    if (!candidates.includes(current)) { candidates[0]?.focus(); return; }
    const box = current.getBoundingClientRect();
    const horizontal = key === 'ArrowLeft' || key === 'ArrowRight';
    const direction = key === 'ArrowRight' || key === 'ArrowDown' ? 1 : -1;
    const cx = box.left + box.width / 2, cy = box.top + box.height / 2;
    let winner = null, score = Infinity;
    for (const candidate of candidates) {
      if (candidate === current) continue;
      const b = candidate.getBoundingClientRect();
      const primary = (horizontal ? b.left + b.width / 2 - cx : b.top + b.height / 2 - cy) * direction;
      if (primary < 3) continue;
      const cross = Math.abs(horizontal ? b.top + b.height / 2 - cy : b.left + b.width / 2 - cx);
      const aligned = horizontal ? b.top < box.bottom && b.bottom > box.top : b.left < box.right && b.right > box.left;
      const next = primary + cross * (aligned ? .25 : 3) + (aligned ? 0 : 1000);
      if (next < score) { score = next; winner = candidate; }
    }
    winner?.focus();
  }
  function handleKey(key) {
    if (!isOpen() || document.querySelector('#panel[open],#player[open]')) return false;
    if (keyboardVisible) {
      if (key === 'Escape') { closeKeyboard(); return true; }
      const focused = document.activeElement;
      const inKeyboard = focused?.closest('#tube-keyboard') || focused?.matches('.tube-search');
      if (!inKeyboard) return false;
      if (key === 'Backspace' || key === 'Delete') { deleteCharacter(); return true; }
      if (key === 'Enter') {
        if (focused?.matches('button')) focused.click();
        else keyboard.querySelector('[data-yt-letter]')?.focus();
        return true;
      }
      if (key === 'ArrowDown' && focused?.dataset?.ytAction === 'submit') {
        const firstResult = document.querySelector('#tube-results button:not([disabled])');
        if (firstResult) { firstResult.focus({preventScroll: true}); firstResult.scrollIntoView({block: 'nearest'}); return true; }
      }
      if (key.startsWith('Arrow')) { moveKeyboardFocus(key); return true; }
      return false;
    }
    if (key === 'Escape' && view.method !== 'home') { request({method: 'home'}, defaultTopic()); document.querySelector('.tube-search')?.focus(); return true; }
    return false;
  }

  document.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button || (!button.closest('#nava-tube') && !button.closest('#tube-keyboard'))) return;
    if (button.dataset.ytProvider) { event.preventDefault(); selectProvider(button.dataset.ytProvider); return; }
    if (button.dataset.ytBroadcaster && view.provider === 'mediathek') {
      const channel = button.dataset.ytBroadcaster;
      if (!['all','ard','zdf'].includes(channel) || channel === view.broadcaster) return;
      view.broadcaster = channel;
      request(currentPayload(), view.activeTopic, {keepDraft: true});
      document.querySelector(`[data-yt-broadcaster="${channel}"]`)?.focus({preventScroll: true});
      return;
    }
    if (button.dataset.ytPlay) { event.preventDefault(); play(button.dataset.ytPlay); return; }
    if (button.dataset.ytMore) { event.preventDefault(); continueGroup(button.dataset.ytMore); return; }
    if (button.dataset.ytTopic !== undefined) {
      const topic = providerTopics()[Number(button.dataset.ytTopic)];
      if (!topic) return;
      if (topic.method) { closeKeyboard(false); request({method: topic.method}, topic.label); }
      else search(topic.query, topic.label);
      document.querySelector(`[data-yt-topic="${Number(button.dataset.ytTopic)}"]`)?.focus({preventScroll: true});
      return;
    }
    if (button.dataset.ytLetter !== undefined) { typeCharacter(button.dataset.ytLetter); return; }
    const actions = {
      filters: () => { filtersOpen = !filtersOpen; render(); },
      keyboard: () => keyboardVisible ? closeKeyboard() : openKeyboard(),
      'keyboard-close': closeKeyboard,
      retry: () => request(currentPayload(), view.activeTopic, {keepDraft: keyboardVisible}),
      'clear-channel': () => request({method: 'home'}, defaultTopic()),
      back: exit,
      space: () => typeCharacter(' '),
      delete: deleteCharacter,
      submit: () => { if (!search(keyboardValue)) keyboard?.querySelector('[data-yt-letter]')?.focus(); }
    };
    const action = actions[button.dataset.ytAction];
    if (action) { event.preventDefault(); action(); }
  });
  // Inline keyboard: the rest of OpenNava stays reachable with DPAD; no system IME is needed.
  document.addEventListener('keydown', event => {
    if (!keyboardVisible || !isOpen() || document.querySelector('#panel[open],#player[open]') || event.ctrlKey || event.metaKey || event.altKey) return;
    const typing = event.target.closest('#tube-keyboard') || event.target.matches('.tube-search');
    if (typing && event.key.length === 1) { event.preventDefault(); event.stopImmediatePropagation(); typeCharacter(event.key); return; }
    if (typing && (event.key === 'Backspace' || event.key === 'Delete')) { event.preventDefault(); event.stopImmediatePropagation(); deleteCharacter(); return; }
    if (handleKey(event.key)) { event.preventDefault(); event.stopImmediatePropagation(); }
  }, true);
  document.addEventListener('error', event => {
    if (event.target instanceof HTMLImageElement && event.target.closest('#nava-tube')) event.target.remove();
  }, true);

  function getSearchContext() { return Object.freeze({provider: view.provider, channel: view.broadcaster, channelId: view.channelId, channelName: view.provider === 'mediathek' ? (view.broadcaster === 'all' ? '' : view.broadcaster.toUpperCase()) : view.channelName, query: view.query}); }
  window.NavaTube = Object.freeze({open, close, isOpen, handleKey, search, openChannel, focusSearch, getSearchContext, requestMediathek, playMediathek, showLive, showBroadcaster});
})();
