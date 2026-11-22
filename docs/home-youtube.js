/* The local TV gateway keeps Google credentials out of this browser page. */
(() => {
  'use strict';
  const root = document.querySelector('#hd-youtube-root');
  if (!root) return;
  const base = new URL('./api/youtube/', document.currentScript.src);
  const site = window.OpenNavaSite;
  const scene = document.querySelector('#home-demo');
  let status = {state: 'loading'}, csrf = '', groups = [], timer, revision = 0;
  let visible = false, busy = false, feedLoaded = false, copied = false;
  const t = key => site.t(key);
  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  };
  function button(textKey, action, secondary = false) {
    const node = el('button', secondary ? 'yt-button yt-secondary' : 'yt-button', t(textKey));
    node.type = 'button';
    node.dataset.ytAction = action;
    node.disabled = busy;
    return node;
  }
  function external(label, href, className = 'yt-text-link') {
    const link = el('a', className, t(label));
    link.href = href;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    return link;
  }
  function verificationURL(value) {
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && ['google.com','www.google.com','accounts.google.com','youtube.com','www.youtube.com'].includes(url.hostname) ? url.href : null;
    } catch { return null; }
  }
  function imageURL(value) {
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && (url.hostname === 'i.ytimg.com' || url.hostname === 'i9.ytimg.com') ? url.href : '';
    } catch { return ''; }
  }
  function googleQR(href) {
    if (typeof window.qrcode !== 'function') return null;
    try {
      const qr = window.qrcode(0,'M');
      qr.addData(href,'Byte');
      qr.make();
      const count = qr.getModuleCount(), quiet = 4, size = count + quiet * 2;
      const svgNS = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(svgNS,'svg');
      svg.setAttribute('viewBox',`0 0 ${size} ${size}`);
      svg.setAttribute('width','196'); svg.setAttribute('height','196');
      svg.setAttribute('shape-rendering','crispEdges');
      svg.setAttribute('aria-hidden','true');
      const paper = document.createElementNS(svgNS,'rect');
      paper.setAttribute('width',size); paper.setAttribute('height',size); paper.setAttribute('fill','#fff');
      const ink = document.createElementNS(svgNS,'path');
      const modules = [];
      for (let row=0;row<count;row++) for (let col=0;col<count;col++) {
        if (qr.isDark(row,col)) modules.push(`M${col+quiet} ${row+quiet}h1v1h-1z`);
      }
      ink.setAttribute('d',modules.join('')); ink.setAttribute('fill','#111');
      svg.append(paper,ink);
      const link = external('ytQrLabel',href,'yt-qr-link');
      link.setAttribute('aria-label',t('ytQrLabel'));
      link.replaceChildren(svg);
      return link;
    } catch { return null; } // The regular Google link and code remain available.
  }
  async function request(path, method = 'GET') {
    const response = await fetch(new URL(path, base), {
      method, credentials: 'same-origin', cache: 'no-store',
      headers: method === 'POST' ? {'X-CSRF-Token': csrf, 'Content-Type': 'application/json'} : {},
      ...(method === 'POST' ? {body: '{}'} : {}),
      signal: AbortSignal.timeout(25000)
    });
    if (response.status === 404 || !response.headers.get('content-type')?.includes('application/json')) throw Error('local-only');
    const result = await response.json();
    if (result.csrfToken) csrf = result.csrfToken;
    if (!response.ok) {
      const error = Error(result.error === 'expired' ? 'expired' : 'unavailable');
      error.state = result.state;
      throw error;
    }
    return result;
  }
  function render() {
    const awaitingFocus = document.activeElement === root;
    const active = root.contains(document.activeElement) ? document.activeElement.dataset.ytAction : null;
    const activeVideo = root.contains(document.activeElement) ? document.activeElement.dataset.ytVideo : null;
    root.replaceChildren();
    root.dataset.state = status.state;
    if (status.state === 'signed-in') {
      const toolbar = el('div','yt-toolbar');
      toolbar.append(el('span','yt-connected', t('ytSignedIn')));
      const actions = el('div','yt-toolbar-actions');
      actions.append(button('ytRefresh','refresh',true), button('ytSignOut','logout',true));
      toolbar.append(actions);
      root.append(toolbar);
      if (!feedLoaded) {
        root.append(el('p','yt-status', t('ytLoading')));
      } else if (status.feedError) {
        root.append(el('p','yt-status', t('ytUnavailable')));
        root.append(button('ytRetry','refresh'));
      } else if (!groups.length) {
        root.append(el('p','yt-status', t('ytEmpty')));
      } else {
        for (const group of groups) {
          const shelf = el('section','yt-shelf');
          shelf.append(el('h3','', group.title || t('ytForYou')));
          const cards = el('div','yt-video-grid');
          for (const item of group.items) {
            if (!/^[\w-]{11}$/.test(item.id)) continue;
            const card = el('a','yt-video');
            card.dataset.ytVideo = item.id;
            card.href = 'https://www.youtube.com/watch?v=' + encodeURIComponent(item.id);
            card.target = '_blank'; card.rel = 'noopener noreferrer';
            const art = el('div','yt-video-art');
            const url = imageURL(item.thumbnail);
            if (url) {
              const img = el('img'); img.src = url; img.alt = ''; img.loading = 'lazy'; img.referrerPolicy = 'no-referrer';
              art.append(img);
            }
            if (item.duration) art.append(el('span','yt-duration', item.duration));
            card.append(art, el('strong','',item.title), el('span','yt-author',item.author || 'YouTube'));
            cards.append(card);
          }
          shelf.append(cards); root.append(shelf);
        }
      }
    } else {
      const card = el('div','yt-connect-card');
      const mark = el('div','yt-mark');
      mark.setAttribute('aria-hidden','true');
      mark.innerHTML = '<svg viewBox="0 0 64 46"><rect width="64" height="46" rx="14" fill="#ff3e4b"/><path d="m26 13 18 10-18 10Z" fill="white"/></svg>';
      card.append(mark);
      const title = el('h3','yt-title',t('ytWelcome'));
      card.append(title);
      if (status.state === 'pending') {
        const href = verificationURL(status.verificationUrl);
        const qr = href ? googleQR(href) : null;
        const pairing = el('div',qr ? 'yt-pairing' : 'yt-pairing yt-pairing-manual');
        const instructions = el('div','yt-pairing-details');
        if (qr) {
          card.classList.add('yt-connect-card--pairing');
          pairing.append(qr);
          instructions.append(el('h4','',t('ytScan')), el('p','',t('ytScanHelp')));
        } else instructions.append(el('p','',t('ytDeviceHint')));
        const code = el('div','yt-code',status.userCode || '…');
        code.setAttribute('aria-label',t('ytCode'));
        instructions.append(code);
        pairing.append(instructions); card.append(pairing);
        const actions = el('div','yt-connect-actions');
        if (href) actions.append(external('ytOpenGoogle',href,'yt-button'));
        actions.append(button(copied ? 'ytCopied' : 'ytCopyCode','copy',true));
        card.append(actions,el('p','yt-waiting',t('ytWaiting')),button('ytCancel','cancel',true));
      } else if (status.state === 'loading') {
        card.append(el('p','yt-status',t('ytWaiting')));
      } else if (status.state === 'local-only') {
        card.append(el('p','yt-intro',t('ytLocalOnly')), external('ytOpenYouTube','https://www.youtube.com/','yt-button'));
      } else if (status.state === 'error') {
        card.append(el('p','yt-intro',t(status.error === 'expired' ? 'ytExpired' : 'ytUnavailable')));
        card.append(button('ytRetry','start'), external('ytOpenYouTube','https://www.youtube.com/'));
      } else {
        card.append(el('p','yt-intro',t('ytIntro')));
        card.append(button('ytConnect','start'));
        card.append(el('p','yt-privacy-note',t('ytGoogleNote')));
      }
      root.append(card);
    }
    if (active || activeVideo) {
      const target = [...root.querySelectorAll('[data-yt-action], [data-yt-video]')].find(node=>!node.disabled && (active ? node.dataset.ytAction===active : node.dataset.ytVideo===activeVideo));
      (target || root).focus({preventScroll:true});
    }
    else if (awaitingFocus) root.querySelector('button:not(:disabled), a[href]')?.focus({preventScroll:true});
  }
  function schedule() {
    clearTimeout(timer);
    if (visible && !document.hidden && status.state === 'pending') timer = setTimeout(()=>sync(),3000);
  }
  async function loadFeed() {
    const id = ++revision;
    feedLoaded = false; status.feedError = false; render();
    try {
      const response = await request('home');
      if (id !== revision || status.state !== 'signed-in') return;
      groups = (Array.isArray(response.groups) ? response.groups : []).filter(g=>g && Array.isArray(g.items) && g.items.length);
    } catch {
      if (id !== revision) return;
      groups = []; status.feedError = true;
    }
    if (id === revision) {feedLoaded = true; render();}
  }
  async function sync() {
    const id = ++revision;
    try {
      const next = await request('status');
      if (id !== revision) return;
      const newlyConnected = next.state === 'signed-in' && status.state !== 'signed-in';
      if (next.state === 'signed-in' && !newlyConnected) next.feedError = status.feedError;
      status = next; render();
      if (newlyConnected || (status.state === 'signed-in' && !feedLoaded)) await loadFeed();
    } catch (error) {
      if (id !== revision) return;
      status = {state:error.message==='local-only'?'local-only':'error'}; render();
    }
    schedule();
  }
  async function mutate(action) {
    if (busy) return;
    clearTimeout(timer);
    busy = true; copied = false; render();
    if (action === 'logout') {groups=[];feedLoaded=false;}
    const id = ++revision;
    try {
      if (!csrf) {
        const current = await request('status');
        if (id !== revision) return;
        status = current;
      }
      const result = await request(action === 'start' ? 'auth/start' : action === 'cancel' ? 'auth/cancel' : 'logout','POST');
      if (id !== revision) return;
      status = result;
      if (action === 'logout' || action === 'cancel') {groups=[];feedLoaded=false;}
    } catch (error) {
      if (id !== revision) return;
      status = action === 'logout' && error.state === 'signed-out'
        ? {state:'signed-out'}
        : {state:error.message==='local-only'?'local-only':'error',error:error.message};
    } finally {
      busy = false; render(); schedule();
    }
    if (status.state === 'signed-in') await loadFeed();
  }
  root.addEventListener('click',async event=>{
    const action = event.target.closest('[data-yt-action]')?.dataset.ytAction;
    if (['start','cancel','logout'].includes(action)) await mutate(action);
    else if (action === 'refresh') await loadFeed();
    else if (action === 'copy' && status.userCode) {
      try {await navigator.clipboard.writeText(status.userCode);copied=true;render();} catch { /* The selectable code remains visible. */ }
    }
  });
  document.addEventListener('home-demo:view',event=>{
    visible = event.detail.view === 'youtube';
    clearTimeout(timer);
    if (visible) {render(); if (!busy) sync();}
  });
  document.addEventListener('opennava:site-language',render);
  document.addEventListener('visibilitychange',()=>{
    clearTimeout(timer);
    if (!document.hidden && visible && !busy) sync();
  });
  window.addEventListener('pagehide',()=>clearTimeout(timer));
  site.ready.then(()=>{render(); if(scene.dataset.view==='youtube'){visible=true;sync();}});
})();
