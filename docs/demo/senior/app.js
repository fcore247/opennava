(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const timeZone = 'Europe/Berlin';
  const dateFormat = new Intl.DateTimeFormat('en-GB',{timeZone,weekday:'long',day:'numeric',month:'long'});
  const clockFormat = new Intl.DateTimeFormat('en-GB',{timeZone,hourCycle:'h23',hour:'2-digit',minute:'2-digit'});
  function refreshClock() {
    const now = new Date();
    const time = clockFormat.format(now);
    const hour = Number(time.slice(0,2));
    let greeting = 'Good evening';
    if (hour >= 5 && hour < 11) greeting = 'Good morning';
    if (hour >= 11 && hour < 15) greeting = 'Good afternoon';
    if (hour >= 15 && hour < 18) greeting = 'Good afternoon';
    byId('greeting').textContent = greeting;
    byId('today').textContent = dateFormat.format(now);
    byId('clock-time').textContent = time;
    byId('clock-time').dateTime = now.toISOString();
  }
  function clockTick() {
    refreshClock();
    setTimeout(clockTick, 60000 - (Date.now() % 60000) + 10);
  }
  clockTick();
  document.addEventListener('update-greeting',refreshClock);
  document.addEventListener('visibilitychange',refreshClock);
  document.querySelector('[data-open="ard"]').focus({preventScroll:true});
})();
(() => {
  'use strict';
  const el = id => document.getElementById(id);
  const current = {provider:'',page:'home',topic:'',query:'',draft:'',keyboard:false,groups:[],status:'idle',message:'',warning:'',generation:0};
  const topics = {
    ard: [['News','Tagesschau'],['Documentaries','Dokumentation'],['Crime','Krimi'],['Nature','Natur']],
    zdf: [['News','heute'],['Documentaries','Terra X'],['Crime','Krimi'],['Nature','Natur']],
    arte: [['Culture','Stadt Land Kunst'],['History','Geschichte'],['Nature','Natur'],['Music','Konzert']]
  };
  const suggestions = {
    ard: [['Tagesschau','suche-tagesschau.jpg','News'], ['Tatort','suche-tatort.jpg','Crime']],
    zdf: [['heute','suche-heute.jpg','News'], ['Bares für Rares','suche-bares.jpg','Antiques']],
    arte: [['Stadt Land Kunst','suche-stadt-land-kunst.jpg','Culture'], ['Zu Tisch','suche-zu-tisch.jpg','Food']]
  };
  const preview = {key:'', revision:0, timer:null, active:0, queued:null, items:[]};
  let keyboardMode = 'abc';
  const pending = new Map();
  const cache = new Map();
  let nextRequest = 0;
  let stationFocus = 'ard';
  let openedFromLive = false;
  let returningFromVisitor = false;
  let lastPlayback = null;
  let liveAttempt = null;
  let toastTimer = null;
  const text = (value,limit=300) => typeof value === 'string' ? value.trim().slice(0,limit) : '';
  const escape = value => String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const idPattern = /^[a-zA-Z0-9._:-]{1,180}$/;
  const dateOnly = new Intl.DateTimeFormat('en-GB',{day:'numeric',month:'short',year:'numeric',timeZone:'UTC'});
  const dateTime = new Intl.DateTimeFormat('en-GB',{day:'numeric',month:'short',hour:'2-digit',minute:'2-digit',timeZone:'Europe/Berlin'});
  function imageURL(value) {
    if (typeof value === 'string' && /^assets\/(ard-cover|zdf-cover|arte-cover|suche-[a-z-]+)\.jpg$/.test(value)) return value;
    if (!value || typeof value !== 'string') return '';
    try {
      const url = new URL(value,'https://nava.local');
      return url.origin === 'https://nava.local' && !url.username && !url.password && !url.search && !url.hash && /^\/mediathek\/image\/[a-zA-Z0-9._:-]+$/.test(url.pathname) ? url.href : '';
    } catch { return ''; }
  }
  function dateLabel(value) {
    if (!value || !/^\d{4}-\d{2}-\d{2}(T|$)/.test(value)) return '';
    const date = new Date(value);
    if (!Number.isFinite(date.getTime())) return '';
    return value.includes('T') ? dateTime.format(date) : dateOnly.format(date);
  }
  function items(raw,provider) {
    if (!Array.isArray(raw)) return [];
    const unique = new Map();
    for (const item of raw.slice(0,120)) {
      if (!item || typeof item.id !== 'string' || !idPattern.test(item.id) || !text(item.title) || unique.has(item.id)) continue;
      const source = text(item.provider || item.channel).toLowerCase();
      if (source && source !== provider) continue;
      const seconds = Number(item.duration);
      unique.set(item.id,{id:item.id,title:text(item.title),author:text(item.author,100),subtitle:text(item.subtitle,500),photo:imageURL(item.thumbnail),live:item.isLive === true,date:dateLabel(item.publishedAt),duration:Number.isFinite(seconds) && seconds > 0 ? Math.max(1,Math.round(seconds/60))+' Min.' : ''});
    }
    return [...unique.values()];
  }
  function groups(raw,provider) {
    const list = Array.isArray(raw.groups) && raw.groups.length ? raw.groups : [raw];
    const result = list.slice(0,16).map(group=>({title:text(group?.title),items:items(group?.items,provider),token:text(group?.pageToken,4096),loading:false,error:''})).filter(group=>group.items.length);
    if (result.length === 1 && !result[0].token) result[0].token = text(raw.pageToken,4096);
    return result;
  }
  function request(payload) {
    return new Promise((resolve,reject)=>{
      if (typeof window.NavaTV?.mediathekRequest !== 'function') { reject(new Error('The media library is unavailable on this device.')); return; }
      const id = `med-${Date.now()}-${++nextRequest}`;
      const timer = setTimeout(()=>{pending.delete(id);reject(new Error('The media library is not responding.'));},40000);
      pending.set(id,{resolve,reject,timer});
      try {
        if (window.NavaTV.mediathekRequest(id,JSON.stringify(payload)) === false) throw new Error();
      } catch { clearTimeout(timer);pending.delete(id);reject(new Error('Could not start the request.')); }
    });
  }
  window.navaMediathekResult = (id,json) => {
    const task = pending.get(id);
    if (!task) return;
    pending.delete(id);
    clearTimeout(task.timer);
    try {
      if (typeof json !== 'string' || json.length > 2000000) throw new Error('Could not read the library response.');
      const data = JSON.parse(json);
      if (data?.error) throw new Error(text(data.error?.message || data.error,200) || 'Could not load the media library.');
      if (!Array.isArray(data?.items) && !Array.isArray(data?.groups)) throw new Error('Could not read the library response.');
      task.resolve(data);
    } catch(error) {task.reject(error instanceof SyntaxError ? new Error('Could not read the library response.') : error);}
  };
  function cacheKey(){return [current.provider,current.page,current.query].join('|');}
  function snapshotFocus() {
    const button = document.activeElement;
    if (!(button instanceof HTMLButtonElement)) return '';
    for (const attr of ['data-open','data-page','data-command','data-topic','data-play','data-preview-play','data-suggestion','data-more','data-letter']) {
      if (button.hasAttribute(attr)) return `[${attr}="${CSS.escape(button.getAttribute(attr))}"]`;
    }
    return '';
  }
  function focus(selector,scroll=false) {
    const button = selector && document.querySelector(selector);
    if (!button || button.disabled || !button.getClientRects().length) return false;
    button.focus({preventScroll:true});
    if (scroll) button.scrollIntoView({block:'nearest',inline:'nearest'});
    return true;
  }
  function toast(message) {
    clearTimeout(toastTimer);
    el('toast').textContent = message;
    toastTimer = setTimeout(()=>{el('toast').textContent = '';},4500);
  }
  function showHome() {
    if (window.SeniorHelp?.isOpen()) window.SeniorHelp.close();
    clearLive();
    closeChoice(false);
    returningFromVisitor = false;
    if (current.provider) stationFocus = current.provider;
    current.generation++;
    current.provider = '';
    window.NavaTV?.reportCatalogue?.('', 'home', '');
    current.keyboard = false;
    updateField();
    lastPlayback = null;
    el('station').hidden = true;
    el('home').hidden = false;
    window.NavaTV?.setHomeShortcuts?.(true);
    document.body.classList.remove('at-station');
    el('toast').textContent = '';
    document.dispatchEvent(new Event('update-greeting'));
    window.scrollTo(0,0);
    focus(`[data-open="${stationFocus}"]`);
  }
  function openStation(provider, page = 'home', query = '') {
    if (!Object.prototype.hasOwnProperty.call(topics,provider)) return;
    current.provider = provider;
    stationFocus = provider;
    el('station').dataset.provider = provider;
    el('station-title').textContent = `${provider.toUpperCase()} library`;
    el('official-label').textContent = `Open ${provider.toUpperCase()} app`;
    el('home').hidden = true;
    window.NavaTV?.setHomeShortcuts?.(false);
    el('station').hidden = false;
    document.body.classList.add('at-station');
    goPage(page, '', query);
  }
  function goPage(page,topic='',query='') {
    clearLive();
    current.generation++;
    Object.assign(current,{page,topic,query,draft:query,keyboard:page==='search'&&!topic&&!query,groups:[],status:'idle',warning:'',message:''});
    window.NavaTV?.reportCatalogue?.(current.provider, page, query);
    keyboardMode = 'abc';
    lastPlayback = null;
    for(const button of document.querySelectorAll('[data-page]')){
      const selected = button.dataset.page === (topic ? 'home' : page);
      if (selected) button.setAttribute('aria-current','page'); else button.removeAttribute('aria-current');
    }
    el('search-area').hidden = page !== 'search' || !!topic;
    el('topics').hidden = page !== 'home' && !topic;
    el('topics').innerHTML = '<span>Topics</span>'+topics[current.provider].map(([label],index)=>`<button data-topic="${index}" aria-pressed="${topic===label}">${label}</button>`).join('');
    updateField();
    window.scrollTo(0,0);
    if (current.keyboard) {
      renderResults();
      focus('[data-letter="Q"]');
    } else {
      const selected = topic ? topics[current.provider].findIndex(([name])=>name===topic) : -1;
      focus(selected >= 0 ? `[data-topic="${selected}"]` : page === 'search' ? '#search-field' : `[data-page="${page}"]`);
      load();
    }
  }
  function keyboardMarkup() {
    const rows = keyboardMode === 'abc'
      ? ['QWERTYUIOP', 'ASDFGHJKL', 'ZXCVBNMÄÖÜß']
      : ['1234567890', '.,:;!?-_/', '+&()@#%'];
    const letters = rows.map(row => {
      const keys = Array.from(row, letter => `<button data-letter="${escape(letter)}">${escape(letter)}</button>`).join('');
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
    const searching = Boolean(current.provider && current.page === 'search' && !current.topic);
    el('station').classList.toggle('searching',searching);
    const head = el('search-head');
    if (head) head.hidden = !searching;
    const logo = el('search-logo');
    if (searching && logo) {
      logo.setAttribute('src',`assets/${current.provider}-logo.svg`);
      logo.alt = current.provider.toUpperCase();
    }
    const field = el('search-text');
    field.textContent = current.draft || 'Search shows';
    field.classList.toggle('placeholder',!current.draft);
    field.scrollLeft = current.keyboard ? field.scrollWidth : 0;
    el('search-field').setAttribute('aria-label',current.draft ? `Edit search: ${current.draft}` : 'Search shows');
    el('search-field').setAttribute('aria-expanded',String(current.keyboard));
    el('tv-keyboard').hidden = !current.keyboard;
    el('station').classList.toggle('typing',current.keyboard);
    el('results').hidden = searching && current.keyboard;
    const keyboard = el('tv-keyboard');
    if (current.keyboard && keyboard.dataset.mode !== keyboardMode) {
      keyboard.dataset.mode = keyboardMode;
      keyboard.innerHTML = keyboardMarkup();
    }
    updatePreview();
  }

  function previewCard(title, image, action, value, detail) {
    const picture = image ? `<img class="photo" src="${escape(image)}" alt="" decoding="async">` : '';
    const caption = detail ? `<small>${escape(detail)}</small>` : '';
    const indicator = action === 'data-preview-play' ? '▷' : '↗';
    return `<button class="preview-card" ${action}="${escape(value)}"><span class="preview-image">${picture}</span>
      <span class="preview-copy"><span class="preview-label"><strong>${escape(title)}</strong>${caption}</span>
      <span class="preview-arrow" aria-hidden="true">${indicator}</span></span></button>`;
  }
  function paintPreview(title, markup) {
    const list = el('preview-list');
    const heading = el('preview-title');
    if (!list || !heading) return;
    const restore = list.contains(document.activeElement) ? snapshotFocus() : '';
    heading.textContent = title;
    list.innerHTML = markup;
    if (restore && !focus(restore)) focus('#search-field');
  }
  function paintMatches(matches) {
    preview.items = [...new Map(matches.map(item => [item.id,item])).values()].slice(0,2);
    paintPreview(preview.items.length ? 'Matching shows' : 'No matching shows',
      preview.items.map(item => previewCard(item.title,item.photo,'data-preview-play',item.id,[item.live ? 'Live' : item.duration, item.date].filter(Boolean).join(' · '))).join(''));
  }
  function updatePreview() {
    const container = el('search-preview');
    if (!container) return;
    const editing = Boolean(current.provider && current.keyboard && current.page === 'search' && !current.topic);
    container.hidden = !editing;
    const query = current.draft.trim();
    const key = editing ? `${current.provider}|${query.length < 2 ? '' : query}` : '';
    if (preview.key === key) return;
    preview.key = key;
    const revision = ++preview.revision;
    clearTimeout(preview.timer);
    preview.queued = null;
    preview.items = [];
    if (!editing) { paintPreview('',''); return; }
    if (query.length < 2) {
      paintPreview('Quick picks',suggestions[current.provider].map(([term,photo,genre]) =>
        previewCard(term,`assets/${photo}`,'data-suggestion',term,genre)).join(''));
      return;
    }
    const provider = current.provider;
    const cached = cache.get([provider,'search',query].join('|'));
    if (cached && Date.now()-cached.saved < 300000) { paintMatches(cached.groups.flatMap(group=>group.items)); return; }
    paintPreview('Searching for shows…','');
    preview.timer = setTimeout(() => {
      preview.queued = {query,provider,revision};
      runPreview();
    },450);
  }
  async function runPreview() {
    if (!preview.queued || preview.active >= 2) return;
    const {query,provider,revision} = preview.queued;
    preview.queued = null;
    preview.active++;
    try {
      const raw = await request({method:'search',channel:provider,query});
      if (revision !== preview.revision) return;
      const matched = groups(raw,provider);
      cache.set([provider,'search',query].join('|'),{groups:matched,warning:'',saved:Date.now()});
      if (cache.size > 16) cache.delete(cache.keys().next().value);
      paintMatches(matched.flatMap(group=>group.items));
    } catch {
      if (revision === preview.revision) paintPreview('Preview unavailable','');
    } finally {
      preview.active--;
      runPreview();
    }
  }
  function setKeyboard(open) {
    if (!open) current.draft = current.query;
    current.keyboard = open;
    updateField();
    if (open) window.scrollTo(0,0);
    focus(open ? '#tv-keyboard [data-letter]' : '#search-field',true);
  }
  function submitQuery() {
    const query = current.draft.trim();
    if (!query) {toast('Enter a search term.');focus('#search-field');return;}
    goPage('search','',query);
    focus('#search-field');
  }
  async function load(force=false) {
    const key = cacheKey();
    const generation = ++current.generation;
    const recent = !force && cache.get(key);
    if (recent && Date.now()-recent.saved < 300000) {
      Object.assign(current,{groups:structuredClone(recent.groups),warning:recent.warning,status:recent.groups.length?'ready':'empty'});
      renderResults();return;
    }
    current.status = 'loading'; current.groups = [];current.warning='';
    renderResults();
    const payload = {method:current.page,channel:current.provider};
    if (current.page === 'search') payload.query = current.query;
    try {
      const data = await request(payload);
      if (generation !== current.generation) return;
      current.groups = groups(data,current.provider);
      current.warning = text(data.warning,220);
      current.status = current.groups.length ? 'ready' : 'empty';
      cache.set(key,{groups:structuredClone(current.groups),warning:current.warning,saved:Date.now()});
      if (cache.size > 16) cache.delete(cache.keys().next().value);
    } catch(error) {
      if (generation !== current.generation) return;
      current.status='error';current.message=text(error.message,220);
    }
    renderResults();
  }
  function groupTitle(group,index) {
    if (current.topic && current.groups.length === 1) return current.topic;
    if (current.page === 'search' && current.groups.length === 1) return `Results for “${current.query}”`;
    return group.title || (index === 0 ? 'Recent shows' : 'More shows');
  }
  function card(item,featured=false) {
    const meta = [item.author,item.live?'Live stream':item.date].filter(Boolean).join(' · ');
    return `<button class="program" data-play="${escape(item.id)}" aria-label="Watch ${escape(item.title)}"><span class="thumb"><img class="ident" src="assets/${current.provider}-logo.svg" alt="">${item.photo?`<img class="photo" src="${escape(item.photo)}" alt="" loading="lazy" decoding="async">`:''}${item.live?'<span class="time-badge live-badge">Live</span>':item.duration?`<span class="time-badge">${escape(item.duration)}</span>`:''}</span><span class="program-copy"><strong class="program-title">${escape(item.title)}</strong>${meta?`<span class="program-meta">${escape(meta)}</span>`:''}${featured && item.subtitle ? `<span class="program-subtitle">${escape(item.subtitle)}</span>`:''}</span></button>`;
  }
  function renderResults() {
    const host=el('results');
    const restore = host.contains(document.activeElement) ? snapshotFocus() : '';
    host.setAttribute('aria-busy',String(current.status==='loading'));
    if (current.status==='loading') host.innerHTML='<div class="busy" role="status"><span class="spinner"></span> Loading shows…</div>';
    else if (current.status==='idle') host.replaceChildren();
    else if (current.status==='empty' || current.status==='error') {
      const empty=current.status==='empty';
      const search = current.page==='search' && !current.topic;
      host.innerHTML=`<section class="status-box" role="status"><h2>${empty?'No shows found':'Could not load shows'}</h2><p>${escape(empty ? (search?'Try a different search term.':'No shows are available right now.') : current.message || 'Check your internet connection.')}</p><button data-command="${empty && search?'edit':'retry'}">${empty && search?'Change search':'Try again'}</button></section>`;
    } else {
      host.innerHTML = (current.warning?`<p class="warning" role="status">${escape(current.warning)}</p>`:'') + current.groups.map((group,index)=>{
        const featured=group.items.length===1;
        return `<section class="result-group${featured?' featured':''}"><h2>${escape(groupTitle(group,index))}</h2><div class="grid">${group.items.map(item=>card(item,featured)).join('')}</div>${group.token?`<button class="more" data-more="${index}" aria-busy="${group.loading}">${group.loading?'Loading…':group.error?'Try again':'Load more shows'}</button>`:''}${group.error?`<p class="warning" role="status">${escape(group.error)}</p>`:''}</section>`;
      }).join('');
    }
    if (restore) focus(restore);
  }
  async function loadMore(index) {
    const group=current.groups[index];
    if (!group || group.loading || !group.token) return;
    const generation=current.generation;
    const oldCount=group.items.length;
    group.loading=true;group.error='';renderResults();
    try {
      const data=await request({method:'continue',channel:current.provider,pageToken:group.token});
      if (current.generation!==generation) return;
      const result=groups(data,current.provider)[0];
      const next=result?.items || [];
      const seen=new Set(group.items.map(item=>item.id));
      group.items.push(...next.filter(item=>!seen.has(item.id)));
      const token=result?.token || '';
      group.token=(oldCount===group.items.length && token===group.token) ? '' : token;
    } catch(error) {
      if (current.generation!==generation) return;
      group.error=text(error.message,200);
    }
    const onMore=document.activeElement?.dataset.more===String(index);
    group.loading=false;renderResults();
    if (onMore && group.items[oldCount]) focus(`[data-play="${CSS.escape(group.items[oldCount].id)}"]`,true);
    else if (onMore && !group.token) focus(`[data-play="${CSS.escape(group.items[group.items.length-1].id)}"]`,true);
  }
  function play(id) {
    if (!current.groups.some(group=>group.items.some(item=>item.id===id))) return;
    clearLive();
    try {
      startPlayer(id, `[data-play="${CSS.escape(id)}"]`);
    } catch {toast('Could not open this show.');}
  }
  function startPlayer(id, selector) {
    lastPlayback = {selector,scroll:window.scrollY,generation:current.generation};
    try {
      if (typeof window.NavaTV?.playMediathek !== 'function' || window.NavaTV.playMediathek(id) === false) throw new Error();
    } catch {
      lastPlayback = null;
      throw new Error('The player could not be opened.');
    }
  }
  function clearLive() {
    liveAttempt = null;
    el('watch-live').removeAttribute('aria-busy');
    el('live-feedback').hidden = true;
    el('live-feedback').textContent = '';
  }
  async function watchLive() {
    const provider = current.provider;
    if (!provider || liveAttempt || lastPlayback) return;
    const attempt = {provider};
    liveAttempt = attempt;
    const channel = provider === 'ard' ? 'Das Erste' : provider.toUpperCase();
    const feedback = el('live-feedback');
    feedback.textContent = `${channel} opening…`;
    feedback.hidden = false;
    el('watch-live').setAttribute('aria-busy','true');
    try {
      const data = await request({method:'live',channel:provider});
      if (liveAttempt !== attempt) return;
      const streams = groups(data,provider).flatMap(group=>group.items).filter(item=>item.live);
      if (streams.length !== 1) throw new Error('The live stream is unavailable.');
      startPlayer(streams[0].id,'#watch-live');
      clearLive();
    } catch (error) {
      if (liveAttempt !== attempt) return;
      clearLive();
      feedback.textContent = `${channel}: ${text(error.message,180)} Select “Watch live” to try again.`;
      feedback.hidden = false;
    }
  }
  function openChoice() {
    if (current.provider || el('view-choice').open) return;
    el('visitor-message').textContent = '';
    const choice = el('view-choice').querySelector('[data-command="visitor"]');
    if (choice && typeof window.NavaTV?.openLauncherChoice === 'function') {
      choice.dataset.command = 'launcher-choice';
      choice.textContent = navigator.language.startsWith('de') ? 'Startansicht wählen' : 'Choose home screen';
    }
    el('change-view').setAttribute('aria-expanded','true');
    el('view-choice').showModal();
    window.NavaTV?.setHomeShortcuts?.(false);
    focus('[data-command="stay"]');
  }
  function closeChoice(restoreFocus = true) {
    if (!el('view-choice').open) return;
    el('view-choice').close();
    window.NavaTV?.setHomeShortcuts?.(!current.provider);
    el('change-view').setAttribute('aria-expanded','false');
    if (restoreFocus) focus('#change-view');
  }
  function openVisitor() {
    let opened = false;
    try { opened = window.NavaTV?.openVisitorMode?.() === true; } catch { /* Display the native launch failure below. */ }
    if (opened) {
      returningFromVisitor = true;
      closeChoice();
      return;
    }
    el('visitor-message').textContent = 'Could not open the full launcher.';
    focus('[data-command="stay"]');
  }
  function leaveStation() {
    if (openedFromLive) window.NavaTV.exitApp();
    else showHome();
  }
  function back() {
    if (el('view-choice').open) {closeChoice();return;}
    if (!current.provider) {if(typeof window.NavaTV?.exitApp==='function') window.NavaTV.exitApp();return;}
    if (current.keyboard) {setKeyboard(false);return;}
    if (current.page!=='home') {goPage('home');return;}
    leaveStation();
  }
  function move(key) {
    const here=document.activeElement;
    const choice=el('view-choice');
    if (!choice.open && !current.provider) {
      if (key==='ArrowDown' && here?.dataset.open) {
        stationFocus=here.dataset.open;
        const tiles = [...el('home').querySelectorAll('[data-open]')];
        if (!tiles.some(b => b.offsetTop > here.offsetTop)) {focus('#change-view',true);return;}
      }
      if (key==='ArrowUp' && here?.closest('.home-footer')) {focus(`[data-open="${stationFocus}"]`,true);return;}
    }
    const scope=choice.open ? choice : document;
    const buttons=[...scope.querySelectorAll('button:not([disabled])')].filter(button=>button.getClientRects().length);
    if (!buttons.includes(here)) {buttons[0]?.focus();return;}
    const from=here.getBoundingClientRect();
    const horizontal=key==='ArrowLeft' || key==='ArrowRight';
    const sign=key==='ArrowRight' || key==='ArrowDown' ? 1 : -1;
    const middle=(start,end)=>(start+end)/2;
    let winner=null,best=Infinity;
    for(const button of buttons){
      if(button===here)continue;
      const box=button.getBoundingClientRect();
      const dx=middle(box.left,box.right)-middle(from.left,from.right);
      const dy=middle(box.top,box.bottom)-middle(from.top,from.bottom);
      const primary=(horizontal?dx:dy)*sign;
      if(primary<3)continue;
      const overlap=horizontal?Math.min(from.bottom,box.bottom)-Math.max(from.top,box.top):Math.min(from.right,box.right)-Math.max(from.left,box.left);
      const orthogonal=Math.abs(horizontal?dy:dx);
      if(horizontal && overlap<=0)continue;
      const edge=sign>0 ? (horizontal?box.left-from.right:box.top-from.bottom) : (horizontal?from.left-box.right:from.top-box.bottom);
      const score=Math.max(0,edge)*2+primary*.08+orthogonal*.35+(overlap<=0?Math.abs(overlap)*3+30:0);
      if(score<best){best=score;winner=button;}
    }
    if(winner){winner.focus({preventScroll:true});winner.scrollIntoView({block:'nearest',inline:'nearest'});}
  }
  function erase(){current.draft=Array.from(current.draft).slice(0,-1).join('');updateField();}
  function remote(key) {
    if (window.SeniorHelp?.handleKey(key)) return true;
    if (window.dismissSeniorStartup?.()) return true;
    if(key==='Escape' || key==='Back'){back();return true;}
    if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(key)){move(key);return true;}
    if(key==='Enter'){if(document.activeElement instanceof HTMLButtonElement)document.activeElement.click();return true;}
    if(current.keyboard && ['Backspace','Delete'].includes(key)){erase();return true;}
    return false;
  }
  window.navaOpenLiveLibrary = provider => {
    if (!Object.prototype.hasOwnProperty.call(topics,provider)) return;
    openedFromLive = true;
    el('back-destination').textContent = 'Live TV';
    el('back-destination').parentElement.setAttribute('aria-label','Back to live TV');
    openStation(provider);
  };
  window.navaCompanion = ({provider,operation,value}) => {
    if (!Object.prototype.hasOwnProperty.call(topics,provider)) return;
    window.dismissSeniorStartup?.();
    closeChoice(false);
    openStation(provider, operation === 'live' ? 'home' : 'search', operation === 'search' ? text(value,160) : '');
    if (operation === 'live') watchLive();
  };
  window.navaRemote=remote;
  window.navaNativeHome=showHome;
  window.navaNativeResume=()=>{
    if (window.SeniorHelp?.isOpen()) return;
    document.dispatchEvent(new Event('update-greeting'));
    if (el('view-choice').open) {if (!el('view-choice').contains(document.activeElement)) focus('[data-command="stay"]');return;}
    if (returningFromVisitor) {returningFromVisitor=false;focus('#change-view');return;}
    if (!current.provider) {focus(`[data-open="${stationFocus}"]`);return;}
    if(lastPlayback && lastPlayback.generation===current.generation){window.scrollTo(0,lastPlayback.scroll);focus(lastPlayback.selector);lastPlayback=null;}
  };
  document.addEventListener('click',event=>{
    const button=event.target.closest('button');
    if(!button)return;
    const data=button.dataset;
    if (data.command === 'instructions') {window.SeniorHelp.open(button);return;}
    if (data.command === 'programme') {
      if (!window.NavaTV?.openTelevision?.('programme')) toast('The guide opens on your TV.');
      return;
    }
    if (data.open === 'live-tv' || data.open === 'recordings') {
      stationFocus = data.open;
      if (!window.NavaTV?.openTelevision?.(data.open))
        toast('This feature is available in the TV app.');
      return;
    }
    if(data.open){openStation(data.open);return;}
    if(data.page){goPage(data.page);return;}
    if(data.topic!==undefined){const topic=topics[current.provider]?.[Number(data.topic)];if(topic)goPage('search',topic[0],topic[1]);return;}
    if(data.letter){current.draft=(current.draft+data.letter).slice(0,160);updateField();return;}
    if(data.suggestion && current.keyboard && suggestions[current.provider]?.some(([term])=> term === data.suggestion)) {
      goPage('search','',data.suggestion); return;
    }
    if(data.previewPlay && current.keyboard && preview.items.some(item=>item.id === data.previewPlay)) {
      try {startPlayer(data.previewPlay,`[data-preview-play="${CSS.escape(data.previewPlay)}"]`);}
      catch {toast('Could not open this show.');}
      return;
    }
    if(data.play){play(data.play);return;}
    if(data.more!==undefined){loadMore(Number(data.more));return;}
    switch(data.command){
      case 'watch-live':watchLive();break;
      case 'official-app':
        clearLive();
        if (!window.NavaTV?.openOfficialMediathek?.(current.provider)) {
          toast(`Could not open the ${current.provider.toUpperCase()} app.`);
        }
        break;
      case 'software-updates':
        if (window.NavaTV?.openUpdates) window.NavaTV.openUpdates();
        else toast('Software updates are available on the TV.');
        break;
      case 'change-view':openChoice();break;
      case 'stay':closeChoice();break;
      case 'visitor':openVisitor();break;
      case 'launcher-choice':
        closeChoice();
        window.NavaTV.openLauncherChoice();
        break;
      case 'back':leaveStation();break;
      case 'station-home':if (current.provider) goPage('home');break;
      case 'keyboard':setKeyboard(!current.keyboard);break;
      case 'edit':setKeyboard(true);break;
      case 'mode':keyboardMode=keyboardMode === 'abc' ? '123' : 'abc';updateField();focus('[data-command="mode"]');break;
      case 'space':current.draft=(current.draft+' ').slice(0,160);updateField();break;
      case 'delete':erase();break;
      case 'submit':submitQuery();break;
      case 'retry':load(true);break;
    }
  });
  el('view-choice').addEventListener('cancel',event=>{event.preventDefault();closeChoice();});
  document.addEventListener('keydown',event=>{
    if(event.altKey||event.metaKey||event.ctrlKey)return;
    if(current.keyboard && event.key.length===1){event.preventDefault();current.draft=(current.draft+event.key).slice(0,160);updateField();return;}
    if(remote(event.key)){event.preventDefault();event.stopPropagation();}
  });
  document.addEventListener('error',event=>{if(event.target instanceof HTMLImageElement && event.target.classList.contains('photo'))event.target.remove();},true);
})();
