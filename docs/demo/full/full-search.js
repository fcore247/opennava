/* Search only the sources enabled on this television. Results stay tied to their native provider. */
(() => {
  'use strict';
  const t = (key, fallback) => window.NavaI18n.t(key, fallback);
  const names = {ard:'ARD', zdf:'ZDF', arte:'ARTE', youtube:'YouTube', netflix:'Netflix'};
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let source = 'all', query = '', browsing = false, generation = 0, sequence = 0;
  let editing = false, draft = '', keyboardMode = 'abc';
  const pendingArte = new Map();
  let displayed = [];
  const enabled = () => window.NavaHome.preferences().libraries;
  const sources = () => ['all', ...enabled(), ...(browsing ? [] : ['youtube','netflix'])];
  const sourceName = id => id === 'all' ? t('all','All') : names[id];

  function arte(term) {
    return new Promise((resolve, reject) => {
      const id = 'nava-arte-' + (++sequence);
      const timer = setTimeout(() => { pendingArte.delete(id); reject(new Error('Timeout')); }, 30000);
      pendingArte.set(id, {resolve,reject,timer});
      try {
        if (!window.NavaTV.arteSearch || window.NavaTV.arteSearch(id,term) === false) throw new Error('Unavailable');
      } catch (error) { clearTimeout(timer); pendingArte.delete(id); reject(error); }
    });
  }
  window.navaArteResult = (id, json) => {
    const pending = pendingArte.get(id);
    if (!pending) return;
    pendingArte.delete(id); clearTimeout(pending.timer);
    try { const result = JSON.parse(json); if (result.error) throw new Error('Search failed'); pending.resolve(result); }
    catch (error) { pending.reject(error); }
  };

  function open(requested) {
    const config = window.NavaHome.preferences();
    browsing = requested === 'library' || ['ard','zdf','arte'].includes(requested) && config.libraries.includes(requested);
    const preferred = requested === 'library' ? 'all' : requested || config.defaultSearchService;
    source = sources().includes(preferred) ? preferred : 'all';
    query = draft = '';
    editing = !browsing; keyboardMode = 'abc';
    navigate('search');
    main.querySelector(browsing ? '[data-service][aria-selected="true"]' : '#tv-keyboard [data-letter]')?.focus();
  }
  function render() {
    const services = sources();
    if (!services.includes(source)) source = 'all';
    const title = browsing ? t('library','Media library') : t('search','Search');
    main.innerHTML = `<section class="full-search" aria-labelledby="full-search-title"><h1 id="full-search-title">${esc(title)}</h1>
      <div class="full-service-tabs" role="tablist" aria-label="${esc(t('services','Services'))}" data-liquid-nav>${services.map(id =>
        `<button role="tab" data-service="${id}" aria-selected="${id===source}">${esc(sourceName(id))}</button>`).join('')}</div>
      <button id="full-search-query" data-command="keyboard" aria-expanded="${editing}" aria-controls="tv-keyboard">${icon('search')}<span id="full-search-text"></span></button>
      <div id="tv-keyboard" role="group" aria-label="On-screen keyboard" hidden></div>
      <div id="full-results" role="region" aria-live="polite"></div></section>`;
    updateField();
    if (!editing) load();
  }

  // Same three rows, symbol layer and editing actions as the senior search keyboard.
  function keyboardMarkup() {
    const rows = keyboardMode === 'abc'
      ? ['QWERTYUIOP', 'ASDFGHJKL', 'ZXCVBNMÄÖÜß']
      : ['1234567890', '.,:;!?-_/', '+&()@#%'];
    const letters = rows.map(row => {
      const keys = Array.from(row, letter => `<button data-letter="${esc(letter)}">${esc(letter)}</button>`).join('');
      return `<div class="key-row">${keys}</div>`;
    }).join('');
    return `${letters}<div class="keyboard-actions">
      <button data-command="mode" aria-label="${keyboardMode === 'abc' ? 'Numbers and symbols' : 'Letters'}">${keyboardMode === 'abc' ? '123' : 'ABC'}</button>
      <button data-command="space"><span class="space-symbol" aria-hidden="true"></span>Space</button>
      <button data-command="delete"><span aria-hidden="true">⌫</span>Delete</button>
      <button data-command="submit">Search<span aria-hidden="true">→</span></button>
    </div>`;
  }
  function updateField() {
    const field = document.getElementById('full-search-query');
    if (!field) return;
    const display = document.getElementById('full-search-text');
    if (draft) display.setAttribute('translate','no'); else display.removeAttribute('translate');
    display.textContent = draft || t('searchPrompt','What would you like to watch?');
    field.setAttribute('aria-expanded',String(editing));
    field.setAttribute('aria-label',draft || t('searchShows','Search shows'));
    const keys = document.getElementById('tv-keyboard');
    if (editing && keys.dataset.mode !== keyboardMode) {
      keys.dataset.mode = keyboardMode;
      keys.innerHTML = keyboardMarkup();
    }
    keys.hidden = !editing;
    document.getElementById('full-results').hidden = editing;
    main.querySelector('.full-search').classList.toggle('typing',editing);
  }
  function setKeyboard(open, commit = false) {
    if (open) { draft=query; keyboardMode='abc'; }
    else if (commit) query=draft.trim();
    else draft=query;
    editing=open;
    updateField();
    if (open) {
      window.scrollTo(0,0);
      main.querySelector('#tv-keyboard [data-letter]')?.focus({preventScroll:true});
    } else {
      document.getElementById('full-search-query').focus({preventScroll:true});
      load();
    }
  }
  function append(character) {
    draft=Array.from(draft+character).slice(0,160).join('');
    updateField();
  }
  function erase() {
    draft=Array.from(draft).slice(0,-1).join('');
    updateField();
  }
  function handleKey(key) {
    if (state.view!=='search' || !editing || panel.open || player.open) return false;
    if (key==='Escape' || key==='Back') { setKeyboard(false); return true; }
    if (key==='Backspace' || key==='Delete') { erase(); return true; }
    if (Array.from(key).length===1) { append(key); return true; }
    return false;
  }
  function resultImage(url) {
    if (typeof url !== 'string') return '';
    if (/^assets\/(earth|forest|hills|mountains|ocean)\.jpg$/.test(url)) return url;
    if (/^https:\/\/(?:i\.ytimg\.com\/vi(?:_webp)?\/|nava\.local\/mediathek\/image\/)[A-Za-z0-9_./-]+$/.test(url)) return url;
    return '';
  }
  function row(item, index) {
    const image = resultImage(item.thumbnail);
    return `<button class="full-result" data-tv-result="${index}" aria-label="${esc(item.title)}">
      ${image ? `<img alt="" src="${esc(image)}" loading="lazy">` : `<span class="result-mark">${icon(item.source==='youtube'?'video':'play')}</span>`}
      <span><small>${esc(sourceName(item.source))}</small><strong translate="no">${esc(item.title)}</strong></span></button>`;
  }
  function message(box, content) { box.innerHTML = `<p class="search-message">${esc(content)}</p>`; }
  async function load() {
    const current = ++generation;
    const box = document.getElementById('full-results');
    if (!box) return;
    const term = query.trim();
    displayed = [];
    if (!term && !browsing && source !== 'youtube') { box.replaceChildren(); return; }
    if (source === 'netflix') {
      box.innerHTML = `<button data-netflix-search>${icon('external')} Netflix · ${esc(term)}</button>`;
      return;
    }
    const providers = source === 'all' ? [...enabled(), ...(browsing ? []:['youtube'])] : [source];
    if (!providers.length) { message(box,t('noResults','No results')); return; }
    message(box,t('loading','Loading…'));
    // Update each source independently. A slow provider must not block working ones.
    const groups = new Map();
    let finished = 0, failed = 0;
    await Promise.all(providers.map(async id => {
      try {
        const result = id==='arte' ? await arte(term) : id==='youtube' ? await window.NavaTube.requestYouTube(term)
          : await window.NavaTube.requestMediathek({channel:id,method:term?'search':'home',query:term});
        const seen = new Set();
        const items = (result.items || []).filter(item => item && typeof item.id==='string' && typeof item.title==='string'
          && !seen.has(item.id) && seen.add(item.id)).slice(0,24);
        groups.set(id,items.map(item=>({...item, source:id})));
      } catch { failed++; }
      finished++;
      if (current !== generation || !box.isConnected || state.view !== 'search') return;
      const focused = document.activeElement?.dataset.tvResult;
      const old = focused === undefined ? null : displayed[Number(focused)];
      displayed = providers.flatMap(key => groups.get(key)||[]);
      if (displayed.length) {
        box.innerHTML = `<div class="full-results-grid">${displayed.map(row).join('')}</div>`;
        if (old) {
          const index = displayed.findIndex(item=>item.source===old.source && item.id===old.id);
          box.querySelector(`[data-tv-result="${index}"]`)?.focus({preventScroll:true});
        }
      } else if (finished===providers.length) message(box,failed===finished?t('searchFailed','Search unavailable'):t('noResults','No results'));
    }));
  }
  function play(index) {
    const item = displayed[index];
    if (!item) return;
    const bridge=window.NavaTV;
    let success;
    if (item.source==='arte') success=bridge.playArte(item.id);
    else if (item.source==='youtube') success=bridge.playYouTubeCard ? bridge.playYouTubeCard(item.id,item.title,item.author||'') : bridge.playYouTube(item.id);
    else success=bridge.playMediathek(item.id);
    if (success===false) toast(t('unavailable','This app is unavailable'));
  }
  document.addEventListener('keydown',event=>{
    if (event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return;
    if (handleKey(event.key)) { event.preventDefault(); event.stopImmediatePropagation(); }
  },true);
  document.addEventListener('click',event=>{
    const btn=event.target.closest('.full-search button');
    if (!btn) return;
    if (btn.dataset.letter) { append(btn.dataset.letter); return; }
    switch (btn.dataset.command) {
      case 'keyboard': setKeyboard(!editing); return;
      case 'mode':
        keyboardMode=keyboardMode==='abc'?'123':'abc';
        updateField(); main.querySelector('[data-command="mode"]').focus(); return;
      case 'space': append(' '); return;
      case 'delete': erase(); return;
      case 'submit': setKeyboard(false,true); return;
    }
    if (btn.dataset.service) {
      source=btn.dataset.service;
      main.querySelectorAll('[data-service]').forEach(tab=>tab.setAttribute('aria-selected',String(tab===btn)));
      if (editing) { query=draft.trim(); editing=false; updateField(); }
      load();
    } else if (btn.dataset.tvResult!==undefined) play(Number(btn.dataset.tvResult));
    else if (btn.hasAttribute('data-netflix-search') && !window.NavaTV.searchNetflix?.(query.trim())) toast(t('unavailable','This app is unavailable'));
  });
  window.FullSearch=Object.freeze({open,render,handleKey});
})();
