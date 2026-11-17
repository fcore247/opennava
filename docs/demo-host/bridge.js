// Browser implementation of the TV WebView's native device API. All media is local demo content.
(() => {
  'use strict';
  const profile = location.pathname.includes('/senior/') ? 'senior' : 'full';
  const channels = [
    ['daserste', 'Das Erste', 'ard'], ['zdf', 'ZDF', 'zdf'], ['arte', 'ARTE', 'arte'],
    ['ndr', 'NDR Niedersachsen', 'ard'], ['zdfneo', 'ZDFneo', 'zdf'],
    ['zdfinfo', 'ZDFinfo', 'zdf'], ['3sat', '3sat', 'zdf'], ['phoenix', 'phoenix', 'ard'],
    ['kika', 'KiKA', 'ard'], ['tagesschau24', 'tagesschau24', 'ard']
  ].map(([id, name, provider], i) => ({id, name, provider, number:i + 1}));
  const programmes = [
    ['alpine', 'Above the clouds', 'ard', 'mountains', 'ard-cover', 'A journey through the Alps.'],
    ['forest', 'Inside the forest', 'zdf', 'forest', 'zdf-cover', 'Light and life beneath the canopy.'],
    ['ocean', 'The blue hour', 'arte', 'ocean', 'arte-cover', 'An evening on the Atlantic.'],
    ['earth', 'Earth from orbit', 'zdf', 'earth', 'suche-heute', 'A new perspective on our planet.'],
    ['hills', 'First light', 'ard', 'hills', 'suche-tagesschau', 'The hills before sunrise.'],
    ['wild', 'Wild horizons', 'arte', 'mountains', 'suche-stadt-land-kunst', 'Exploring the mountain wilderness.'],
    ['OpenNava001', 'The changing tide', 'youtube', 'ocean', 'arte-cover', 'A coastal short film.'],
    ['OpenNava002', 'A walk in the woods', 'youtube', 'forest', 'ard-cover', 'A short nature study.']
  ].map(([id,title,provider,art,seniorArt,subtitle]) => ({id,title,provider,art,subtitle,
    author:provider === 'youtube' ? 'OpenNava' : provider.toUpperCase(), channel:provider.toUpperCase(),
    duration:12, thumbnail:'assets/' + (profile === 'senior' ? seniorArt : art) + '.jpg'}));
  const records = new Set();
  try { for (const id of JSON.parse(sessionStorage.getItem('openNavaDemoSaved') || '[]')) if (programmes.some(p => p.id === id)) records.add(id); } catch {}
  let station = channels[0], active = null, layer = null, previousFocus = null, lastState = '', seniorProvider = '', fullFilter = '';
  const q = sel => document.querySelector(sel);
  const find = id => programmes.find(p => p.id === id);
  const media = () => layer?.querySelector('video');
  const channelItems = provider => programmes.filter(p => p.provider === provider);
  const playingNow = () => channelItems(station.provider)[(station.number - 1) % channelItems(station.provider).length];
  const post = (type, data) => { if (parent !== window) parent.postMessage({source:'navatv-demo',type,...data}, location.origin); };
  const changeProfile = profile => { post('profile', {profile}); return true; };
  const btn = (title, action) => {const b = document.createElement('button'); b.type='button'; b.textContent = title; b.onclick = action; return b;};
  function snapshot() {
    let screen = 'home', provider = '', title = '', items = [];
    if (active) {
      ({screen, provider = '', title = '', items = []} = active);
    } else if (profile === 'senior' && q('#station') && !q('#station').hidden) {
      screen = 'catalog'; provider = seniorProvider; title = q('#station-title').textContent; items = channelItems(provider);
    } else if (q('#nava-tube')) {
      const context = window.NavaTube?.getSearchContext() || {};
      screen = 'catalog';
      provider = context.provider === 'youtube' ? 'youtube' : fullFilter || context.channel || 'all';
      title = provider === 'all' ? 'Media library' : provider.toUpperCase();
      items = [...document.querySelectorAll('[data-yt-play]')].map(b=>find(b.dataset.ytPlay)).filter(Boolean);
    }
    return {screen,provider,title,profile,items:items.map(({id,title})=>({id,title})),channels,channel:screen === 'live' || screen === 'guide' ? station.id : '',paused:Boolean(media()?.paused)};
  }
  function sendState(force=false) {
    const data = snapshot(), key = JSON.stringify(data);
    if (force || key !== lastState) {lastState=key; post('state', data);}
  }
  function catalogue(id, raw, youtube) {
    let query;
    try {query=JSON.parse(raw);} catch {return false;}
    let provider = youtube ? 'youtube' : query.channel;
    if (!youtube && fullFilter === 'arte' && provider === 'all' && q('#nava-tube')) provider='arte';
    let items = provider && provider !== 'all' ? channelItems(provider) : programmes.filter(p => p.provider !== 'youtube');
    if (query.method === 'live') {
      const live = provider && provider !== 'all' ? channels.filter(c=>c.provider === provider).slice(0,1) : channels;
      items = live.map(c=>({id:'live:'+c.id,title:c.name,author:c.name,provider:c.provider,channel:c.provider.toUpperCase(),isLive:true}));
    } else if (query.method === 'search' && query.query) {
      const term = String(query.query).trim().toLowerCase();
      items = items.filter(p => (p.title+' '+p.subtitle+' '+p.author).toLowerCase().includes(term));
    }
    setTimeout(() => {
      window[youtube?'navaYouTubeResult':'navaMediathekResult']?.(id, JSON.stringify({items}));
      sendState();
    }, 0);
    return true;
  }
  function closeLayer(resume=true) {
    if (!layer) return;
    media()?.pause(); layer.remove(); layer=null; active=null;
    if (resume) {
      window.navaNativeResume?.();
      if (previousFocus?.isConnected) previousFocus.focus({preventScroll:true});
    }
    previousFocus=null;
  }
  function makeLayer(screen,title,provider,items=[]) {
    const focus = previousFocus || document.activeElement;
    closeLayer(false); previousFocus=focus;
    active={screen,title,provider,items}; layer=document.createElement('dialog');
    layer.className='demo-native'; layer.setAttribute('aria-label',title);
    layer.addEventListener('cancel',e=>{e.preventDefault();back();});
    document.body.append(layer); layer.showModal();
    return layer;
  }
  function heading(el, eyebrow, title) {
    const section=document.createElement('header'), label=document.createElement('small'), h=document.createElement('h1');
    label.textContent=eyebrow; h.textContent=title;section.append(label,h);el.append(section);
  }
  function back() {closeLayer(); sendState();}
  function home() {
    closeLayer(false); fullFilter=''; seniorProvider=''; window.dismissSeniorStartup?.();
    window.navaNativeHome?.(); sendState(true);
  }
  function saveButton(p) {
    const b=btn(records.has(p.id)?'Remove from recordings':'Save to recordings',()=>{
      if(records.has(p.id)) records.delete(p.id);else records.add(p.id);
      try { sessionStorage.setItem('openNavaDemoSaved',JSON.stringify([...records])); } catch {}
      b.textContent=records.has(p.id)?'Remove from recordings':'Save to recordings';sendState(true);
    });
    return b;
  }
  function play(p,live=false) {
    if (!p) return false;
    const el=makeLayer(live?'live':'player',p.title,live?station.provider:p.provider);
    const video=document.createElement('video'), bar=document.createElement('div');
    video.muted=true;video.playsInline=true;video.loop=true;video.autoplay=true;
    video.poster='../full/assets/'+p.art+'.jpg';video.src='../full/assets/'+p.art+'.mp4';
    bar.className='demo-controls';el.append(video);
    heading(el,live?'CHANNEL '+String(station.number).padStart(2,'0')+'  ·  '+station.name.toUpperCase():'MEDIA LIBRARY  ·  '+p.author,p.title);
    const toggle=btn('Pause',()=>togglePlay());
    const indicate=()=>{toggle.textContent=video.paused?'Play':'Pause';sendState();};
    video.addEventListener('play',indicate);video.addEventListener('pause',indicate);
    video.addEventListener('error',()=>{toggle.textContent='Retry preview';toggle.onclick=()=>{video.load();video.play().catch(indicate);};});
    bar.append(toggle,saveButton(p));
    if(live) bar.append(btn('TV guide',guide),btn('Media library',()=>library(station.provider)));
    bar.append(btn('Back',back));el.append(bar);toggle.focus();
    video.play().catch(indicate);sendState(true);return true;
  }
  function togglePlay() {
    const v=media(); if(!v)return;
    if(v.paused)v.play().catch(()=>sendState(true)); else v.pause();
  }
  function tune(id) { const next=channels.find(c=>c.id===id);if(next){station=next;play(playingNow(),true);} }
  function programmeList(screen,title,entries) {
    const el=makeLayer(screen,title,'',entries);
    el.classList.add('demo-list');heading(el, screen==='guide'?station.name.toUpperCase()+'  ·  SAMPLE SCHEDULE':'YOUR LIBRARY', title);
    const list=document.createElement('div');list.className='demo-entries';el.append(list);
    for(const [i,p] of entries.entries()){
      const row=btn((screen==='guide'?['Now   ','Next   ','Later   '][i] || '':'')+p.title,()=>play(p));
      list.append(row);
    }
    if(!entries.length) {const hint=document.createElement('p');hint.textContent='Save a show in the player to find it here.';list.append(hint);}
    list.append(btn('Back',back));list.querySelector('button')?.focus();sendState(true);
  }
  function guide(){ const first=playingNow();programmeList('guide','TV guide',[first,...channelItems(station.provider).filter(p=>p!==first)]); }
  function recordings(){programmeList('recordings','Recordings',[...records].map(find).filter(Boolean));}
  function library(provider) {
    if(!['ard','zdf','arte'].includes(provider))return;
    closeLayer(false);window.dismissSeniorStartup?.();
    if(profile==='senior'){if(q('#home')?.hidden)window.navaNativeHome?.();q('[data-open="'+provider+'"]')?.click();}
    else {
      fullFilter=provider==='arte'?'arte':'';
      if(provider==='arte'){
        q('.header [data-view="home"]')?.click(); q('.header [data-view="mediathek"]')?.click();
        q('[data-yt-broadcaster="all"]')?.click();
      } else window.NavaTube?.showBroadcaster(provider);
    }
    sendState();
  }
  function playId(id) {
    if(typeof id!=='string')return false;
    if(id.startsWith('live:')){tune(id.slice(5));return true;}
    return play(find(id));
  }
  function note(title,text) {
    const el=makeLayer('home',title,'');el.classList.add('demo-list');heading(el,'DEVICE PREVIEW',title);
    const box=document.createElement('div'),p=document.createElement('p');box.className='demo-entries';p.textContent=text;
    box.append(p,btn('Back',back));el.append(box);box.querySelector('button').focus();sendState(true);return true;
  }
  function key(value){
    if(!layer){window.navaRemote?.(value);return;}
    if(value==='Escape'){back();return;}
    if(value==='Enter'){document.activeElement?.click();return;}
    if(active.screen==='live' && ['ArrowUp','ArrowDown'].includes(value)){
      const i=channels.indexOf(station)+(value==='ArrowUp'?1:-1);
      tune(channels[(i+channels.length)%channels.length].id);return;
    }
    const buttons=[...layer.querySelectorAll('button')],i=buttons.indexOf(document.activeElement);
    const step=['ArrowDown','ArrowRight'].includes(value)?1:-1;
    buttons[Math.max(0,Math.min(buttons.length-1,i+step))]?.focus();
  }
  const keys=['ArrowUp','ArrowDown','ArrowLeft','ArrowRight','Enter','Escape'];
  window.NavaTV=Object.freeze({
    getApps:()=>JSON.stringify([
      {packageName:'de.swr.avp.ard.tv',name:'ARD'},{packageName:'com.zdf.android.mediathek',name:'ZDF'},
      {packageName:'tv.arte.plus7',name:'ARTE'},{packageName:'com.google.android.youtube.tv',name:'YouTube'}
    ]),
    mediathekRequest:(id,s)=>catalogue(id,s,false), youtubeRequest:(id,s)=>catalogue(id,s,true),
    reportCatalogue:provider=>{seniorProvider=provider;queueMicrotask(sendState);},
    playMediathek:playId,playYouTube:playId,playYouTubeCard:playId,
    openTelevision:value=>{if(value==='programme')guide();else if(value==='recordings')recordings();else tune(station.id);return true;},
    launchApp:pkg=>{if(pkg==='tv.arte.plus7'){library('arte');return true;}return false;},
    openOfficialMediathek:name=>note(name.toUpperCase(),'On your TV this opens the broadcaster’s installed app.'),
    openUpdates:()=>note('Software updates','Your installed app checks the OpenNava release channel for signed updates.'),
    openSettings:()=>note('Device settings','On your TV this opens Android settings.'),
    showPhoneLink:()=>note('Pair a phone','The virtual phone beside this TV is already connected.'),
    hasPersonalView:()=>true, isLauncherEnabled:()=>true, setHomeShortcuts:()=>{}, setHelpOpen:()=>{}, setGuidePractice:()=>{},
    openVisitorMode:()=>changeProfile('full'),returnToSenior:()=>changeProfile('senior'),exitApp:home
  });
  document.addEventListener('keydown',e=>{if(layer&&keys.includes(e.key)){e.preventDefault();e.stopImmediatePropagation();key(e.key);}},true);
  document.addEventListener('click',e=>{
    if(e.target.closest('[data-yt-broadcaster],[data-view="home"],[data-view="youtube"]') && e.isTrusted) fullFilter='';
    requestAnimationFrame(()=>sendState());
  },true);
  window.addEventListener('message',e=>{
    const m=e.data;
    if(parent===window||e.source!==parent||e.origin!==location.origin||m?.source!=='navatv-site'||m.type!=='action')return;
    switch(m.action){
      case 'key': if(keys.includes(m.value))key(m.value);break;
      case 'open': if(m.value==='live')tune(station.id);else library(m.value);break;
      case 'home':home();break;
      case 'guide':guide();break;
      case 'recordings':recordings();break;
      case 'playPause':togglePlay();break;
      case 'tune':tune(m.value);break;
      case 'play':playId(m.value);break;
      case 'refresh':sendState(true);break;
    }
    requestAnimationFrame(()=>sendState());
  });
  const link=document.createElement('link');link.rel='stylesheet';link.href='../../demo-host/device.css';document.head.append(link);
  document.addEventListener('DOMContentLoaded',()=>{
    window.dismissSeniorStartup?.();
    new MutationObserver(()=>sendState()).observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['hidden','aria-current']});
    sendState(true);
  });
})();
