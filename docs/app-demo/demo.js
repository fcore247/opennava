// A slimmed-down OpenNava: one shared state, drawn as a television and as a phone.
(() => {
  'use strict';
  const config = window.OPENNAVA;
  const base = new URL('.', document.currentScript.src);
  const host = document.getElementById('app-demo');
  // A shadow root keeps the demo's styles and the website's styles apart.
  const shadow = host.attachShadow({ mode: 'open' });
  shadow.innerHTML = `<link rel="stylesheet" href="${new URL('demo.css', base)}"><link rel="stylesheet" href="${new URL('charge.css', base)}">
    <div class="stage">
      <section class="tv-shell" aria-label="OpenNava TV"><div class="tv" id="tv"></div><div class="finale" id="finale" hidden></div></section>
      <div class="app-cell"><section class="window" aria-label="OpenNava App">
        <div class="screen" id="phone"></div>
      </section></div>
    </div>
    <div class="under"><span id="note"></span><span class="links"><button type="button" id="region-button"></button><a id="agent-link"></a></span></div>
    <dialog id="region-dialog" aria-labelledby="region-title"></dialog>`;
  const $ = id => shadow.getElementById(id);
  const tvRoot = $('tv'), phoneRoot = $('phone'), finale = $('finale'), dialog = $('region-dialog');

  // ---------- texts ----------
  const TEXT = {
    en: {
      headline: 'Your TV. Your moment.', subline: 'Tap the TV or the phone. They react to each other, just like the real thing.',
      note: 'A shortened demo with sample content. The real apps do much more.', download: 'Download OpenNava', agentLink: 'For AI agents',
      region: 'Language and country', regionHelp: 'It decides which sample channels and libraries you see. We guessed it from your browser’s time zone and language; nothing is sent anywhere.',
      language: 'LANGUAGE', country: 'COUNTRY', done: 'Start', de: 'Germany', at: 'Austria', ch: 'Switzerland', us: 'United States',
      live: 'Live TV', libraries: 'Libraries', home: 'Home', watchlist: 'Watchlist', apps: 'Apps', settings: 'Settings', ask: 'What would you like to watch?',
      chooseLibrary: 'Choose a library', latest: 'Latest videos', empty: 'Videos you save appear here.', appsSub: 'Already on your TV',
      appearance: 'Appearance', appearanceSub: 'Home screen and look', backgrounds: 'Background images', backgroundsSub: 'Your own photos',
      phoneAccounts: 'Phone & accounts', phoneAccountsSub: 'Connect and sign in', system: 'System', systemSub: 'Updates and exit',
      personal: 'Personalisation', personalSub: 'Apps and folders on the home screen', dim: 'Dimming: light', dimSub: 'The background stays clearly visible',
      onAir: 'LIVE', paused: 'PAUSED', starting: 'Starting', greeting: ['Good morning', 'Good afternoon', 'Good evening'], watch: 'What would you like to watch?',
      editHint: 'Hold and drag to arrange. Tap − to remove.', doneEdit: 'Done', edit: 'Edit start page', addWidget: 'Add widget', addWidgets: 'Add widgets',
      ownTiles: 'OPENNAVA', tvApps: 'APPS ON YOUR TV', appOnTv: 'App on your TV', openOnTv: 'Open on your TV', yourVideos: 'Your videos',
      tubeSearch: 'Search NavaTube', tubeSubmit: 'Search', tubeClear: 'Clear search', tubeVideos: 'Videos', tubeResults: 'Results for “$1”',
      tubeEmpty: 'No videos found.', tubeShow: 'Show NavaTube on TV',
      tubeNote: 'These results are demo videos from our YouTube channel.', watchOnYouTube: 'Watch on YouTube',
      tabHome: 'Home', tabLive: 'Live', tabLibrary: 'Library', tabRemote: 'Remote', liveOnTv: 'LIVE ON YOUR TV', liveTv: 'LIVE TV',
      allChannels: 'All channels and the TV guide', preview: 'PREVIEW', onTv: 'LIVE ON TV', stream: 'Live stream', liveOn: 'Live', playOnTv: 'Play on TV',
      remove: 'Remove', play: 'Play', pause: 'Pause', stop: 'Stop on TV', back: 'Back', close: 'Close', ok: 'OK',
      back10: 'Back 10 sec', ahead10: 'Ahead 10 sec', localTime: 'local time', channelWord: 'CHANNEL', remaining: 'Remaining', upNext: 'UP NEXT', resume: 'Resume', record: 'Record', stopRecord: 'Stop recording', libraryWord: 'Library', chargeLabel: 'Charging the phone', charging: 'Charging …', charged: 'Fully charged', countryLabel: 'Country', country2: 'Your country', phoneBattery: 'Your phone’s battery', lowTitle: '1% battery', lowText: 'Please charge your phone.', dead: 'The phone battery is empty',
      finaleEyebrow: 'Looks like your phone just died.', finaleTitle: 'Download OpenNava today', finaleOr: '…or give this to your AI agent:',
      copy: 'Copy', copied: 'Copied ✓', replay: 'Charge the phone and try again', prompt: 'Set up OpenNava on my TV. Follow the instructions at https://'
    },
    de: {
      headline: 'Dein Fernseher. Dein Moment.', subline: 'Tippe auf den Fernseher oder das Handy. Sie reagieren aufeinander, wie in echt.',
      note: 'Eine verkürzte Demo mit Beispielinhalten. Die echten Apps können viel mehr.', download: 'OpenNava herunterladen', agentLink: 'Für KI-Agenten',
      region: 'Sprache und Land', regionHelp: 'Es bestimmt, welche Beispielsender und Mediatheken du siehst. Geschätzt aus Zeitzone und Sprache deines Browsers, ohne etwas zu verschicken.',
      language: 'SPRACHE', country: 'LAND', done: 'Los geht’s', de: 'Deutschland', at: 'Österreich', ch: 'Schweiz', us: 'USA',
      live: 'Live-TV', libraries: 'Mediatheken', home: 'Start', watchlist: 'Merkliste', apps: 'Apps', settings: 'Einstellungen', ask: 'Was möchtest du ansehen?',
      chooseLibrary: 'Mediathek auswählen', latest: 'Aktuelle Videos', empty: 'Hier erscheinen deine vorgemerkten Sendungen.', appsSub: 'Bereits auf deinem Fernseher',
      appearance: 'Darstellung', appearanceSub: 'Startseite und Aussehen', backgrounds: 'Hintergrundbilder', backgroundsSub: 'Deine eigenen Fotos',
      phoneAccounts: 'Handy & Konten', phoneAccountsSub: 'Verbinden und anmelden', system: 'System', systemSub: 'Updates und Beenden',
      personal: 'Personalisierung', personalSub: 'Apps und Ordner auf der Startseite', dim: 'Abdunkeln: leicht', dimSub: 'Der Hintergrund bleibt gut sichtbar',
      onAir: 'LIVE', paused: 'PAUSE', starting: 'Wird gestartet', greeting: ['Guten Morgen', 'Hallo', 'Guten Abend'], watch: 'Was möchtest du sehen?',
      editHint: 'Halten und ziehen zum Sortieren. − zum Entfernen.', doneEdit: 'Fertig', edit: 'Startseite bearbeiten', addWidget: 'Widget hinzufügen', addWidgets: 'Widgets hinzufügen',
      ownTiles: 'OPENNAVA', tvApps: 'APPS AUF DEINEM TV', appOnTv: 'App auf deinem TV', openOnTv: 'Am Fernseher öffnen', yourVideos: 'Deine Videos',
      tubeSearch: 'NavaTube durchsuchen', tubeSubmit: 'Suchen', tubeClear: 'Suche löschen', tubeVideos: 'Videos', tubeResults: 'Ergebnisse für „$1“',
      tubeEmpty: 'Keine Videos gefunden.', tubeShow: 'NavaTube auf dem TV anzeigen',
      tubeNote: 'Die Ergebnisse sind Demo-Videos von unserem YouTube-Kanal.', watchOnYouTube: 'Auf YouTube ansehen',
      tabHome: 'Start', tabLive: 'Live', tabLibrary: 'Mediathek', tabRemote: 'Fernbed.', liveOnTv: 'LIVE AUF DEINEM TV', liveTv: 'LIVE-TV',
      allChannels: 'Alle Sender und das Programm', preview: 'VORSCHAU', onTv: 'LIVE AM TV', stream: 'Livestream', liveOn: 'Live', playOnTv: 'Am TV abspielen',
      remove: 'Entfernen', play: 'Abspielen', pause: 'Pause', stop: 'Am TV beenden', back: 'Zurück', close: 'Schließen', ok: 'OK',
      back10: '10 Sek. zurück', ahead10: '10 Sek. vor', localTime: 'Uhr Ortszeit', channelWord: 'SENDER', remaining: 'Noch', upNext: 'DANACH', resume: 'Fortsetzen', record: 'Aufnehmen', stopRecord: 'Aufnahme stoppen', libraryWord: 'Mediathek', chargeLabel: 'Das Handy lädt', charging: 'Wird geladen …', charged: 'Vollständig geladen', countryLabel: 'Land', country2: 'Dein Land', phoneBattery: 'Akku deines Handys', lowTitle: '1 % Akku', lowText: 'Bitte lade dein Handy auf.', dead: 'Der Handy-Akku ist leer',
      finaleEyebrow: 'Oh, dein Handy-Akku ist leer.', finaleTitle: 'Lade dir OpenNava noch heute herunter', finaleOr: '…oder gib das deinem KI-Agenten:',
      copy: 'Kopieren', copied: 'Kopiert ✓', replay: 'Handy laden und nochmal', prompt: 'Richte OpenNava auf meinem Fernseher ein. Folge der Anleitung unter https://'
    }
  };

  // ---------- what differs between countries (sample titles are made up) ----------
  const LINEUP = {
    german: {
      channels: ['Das Erste', 'ZDF', 'ARTE', '3sat', 'NDR'],
      libraries: [
        { id: 'ard', name: 'ARD Mediathek', short: 'ARD', color: '#3d7bff', logo: 'ard-logo.svg', art: 'ocean.jpg' },
        { id: 'zdf', name: 'ZDF Mediathek', short: 'ZDF', color: '#ff8a3d', logo: 'zdf-logo.svg', art: 'forest.jpg' },
        { id: 'arte', name: 'ARTE', short: 'ARTE', color: '#ff5a4e', logo: 'arte-logo.svg', art: 'mountains.jpg' }],
      films: ['Über den Wolken', 'Wo der Wald leise wird', 'Die Küste im Winter', 'Ein Sommer am See', 'Nachts in den Bergen', 'Das alte Leuchtfeuer'],
      line: 'ARD · ZDF · ARTE'
    },
    us: {
      channels: ['CBS News 24/7', 'CBS News Baltimore', 'Bloomberg TV US', 'PBS Kids', 'NASA TV'],
      libraries: [
        { id: 'sci', name: 'UCTV Science', short: 'UCTV', color: '#2f8f7f', art: 'forest.jpg' },
        { id: 'health', name: 'UCTV Health', short: 'Health', color: '#b5567a', art: 'ocean.jpg' },
        { id: 'twit', name: 'This Week in Tech', short: 'TWiT', color: '#4a6fd1', art: 'mountains.jpg' }],
      films: ['Above the Clouds', 'Where the Forest Goes Quiet', 'The Winter Coast', 'A Summer by the Lake', 'Night in the Mountains', 'The Old Lighthouse'],
      line: 'UCTV · TWiT'
    }
  };
  const CHANNEL_VIDEOS = [
    { id: 'wSpP5uj61Pc', title: 'OpenNava When your TV goes Clean' },
    { id: 'ZopDypSQfIQ', title: 'The Glass Update | Luna AI Browser' },
    { id: 'I9_C4ql4xTA', title: 'Any Website, Your Way – Meet Luna Pages | Luna AI Browser' },
    { id: '-1fvCKmVkTM', title: 'How to customize Luna & MCP | Luna AI Browser' },
    { id: 'ipxlCxPQNDE', title: 'Luna Browser is Now on Windows | Luna AI Browser' },
    { id: 'D9wWWGS2GG0', title: 'Luna Next gen Browser english version | Luna AI Browser' },
    { id: 'QnWSfZg6Pvs', title: 'Luna Next gen Browser | Luna AI Browser' }
  ];
  const thumbnail = id => `https://i.ytimg.com/vi/${id}/mqdefault.jpg`;
  const TV_APPS = [
    { id: 'netflix', name: 'Netflix', letter: 'N', color: '#e50914', film: true },
    { id: 'prime', name: 'Prime Video', letter: 'P', color: '#1b98e0', film: true },
    { id: 'disney', name: 'Disney+', letter: 'D', color: '#2447b5', film: true },
    { id: 'spotify', name: 'Spotify', letter: 'S', color: '#1db954' },
    { id: 'plex', name: 'Plex', letter: 'X', color: '#e5a00d' }];
  const ART = ['mountains.jpg', 'ocean.jpg', 'forest.jpg', 'alpine-lake.webp', 'mountains.jpg', 'ocean.jpg'];
  const asset = name => new URL('assets/' + name, base).href;

  // ---------- icons ----------
  const PATHS = {
    tv: '<rect x="2.5" y="4" width="19" height="13.5" rx="2.5"/><path d="M8 21h8"/>',
    home: '<path d="m3 11 9-8 9 8M6 9.5V20h12V9.5"/>',
    live: '<circle cx="12" cy="12" r="2.4" fill="currentColor"/><path d="M8 8a6 6 0 0 0 0 8M16 8a6 6 0 0 1 0 8M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14"/>',
    library: '<rect x="3" y="4.5" width="4.5" height="15" rx="1"/><rect x="9.5" y="4.5" width="4.5" height="15" rx="1"/><path d="m16 5 2.8-.5L22 19l-2.9.6Z"/>',
    remote: '<rect x="6" y="2" width="12" height="20" rx="4"/><circle cx="12" cy="9" r="3"/><path d="M10 17h4"/>',
    apps: '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    pencil: '<path d="m4 20 1.2-4.8L16 4.4 19.6 8 8.8 18.8ZM13.8 6.6l3.6 3.6"/>',
    rewind: '<path d="M11.5 5.5v13L3 12ZM21 5.5v13L12.5 12Z"/>', forward: '<path d="M3 5.5v13L11.5 12ZM12.5 5.5v13L21 12Z"/>',
    play: '<path d="M7 4.5 20 12 7 19.5Z"/>', pause: '<path d="M7 4h3.4v16H7zM13.6 4H17v16h-3.4z"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>', plus: '<path d="M12 5v14M5 12h14"/>', check: '<path d="m4.5 12 5 5 10-10"/>',
    left: '<path d="M14.5 6 8.5 12l6 6"/>', right: '<path d="m9.5 6 6 6-6 6"/>', up: '<path d="m6 14.5 6-6 6 6"/>', down: '<path d="m6 9.5 6 6 6-6"/>',
    back: '<path d="M10 5 3 12l7 7M4 12h17"/>', gear: '<circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="7"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/>'
  };
  const icon = (name, solid) => `<svg class="i${solid ? ' solid' : ''}" viewBox="0 0 24 24" aria-hidden="true">${PATHS[name]}</svg>`;
  const esc = value => String(value).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ---------- where the visitor probably is ----------
  function guessRegion() {
    let zone = '';
    try { zone = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { /* no time zone */ }
    const tags = (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || 'en']);
    const language = tags[0].toLowerCase().startsWith('de') ? 'de' : 'en';
    const american = /^(America\/(New_York|Chicago|Denver|Los_Angeles|Phoenix|Anchorage|Detroit|Boise|Juneau|Indiana\/.*|Kentucky\/.*|North_Dakota\/.*)|Pacific\/Honolulu)$/;
    let country = zone === 'Europe/Berlin' || zone === 'Europe/Busingen' ? 'de' : zone === 'Europe/Vienna' ? 'at'
      : zone === 'Europe/Zurich' ? 'ch' : american.test(zone) ? 'us' : '';
    const sure = country !== '';
    if (!country) {
      const named = tags.map(t => (t.split('-')[1] || '').toLowerCase()).find(c => ['de', 'at', 'ch', 'us'].includes(c));
      country = named || (language === 'de' ? 'de' : 'us');
    }
    return { language, country, sure };
  }
  const store = {
    get() { try { return JSON.parse(localStorage.getItem('opennava-demo-region')); } catch (e) { return null; } },
    set(v) { try { localStorage.setItem('opennava-demo-region', JSON.stringify(v)); } catch (e) { /* private window */ } }
  };

  // ---------- state ----------
  const saved = store.get(), guessed = guessRegion();
  const siteLanguage = () => {
    const code = window.OpenNavaSite && window.OpenNavaSite.language;
    return code ? (code === 'de' ? 'de' : 'en') : guessed.language;
  };
  let lang = siteLanguage();
  let country = saved && TEXT.en[saved.country] ? saved.country : guessed.country;
  const fresh = () => ({
    tv: { page: 'home', library: 0, chapter: 0, channel: 0, paused: false, recording: false, film: '', source: '', picture: '' },
    ph: { page: 'home', library: 0, tubeQuery: '', editing: false, sheet: false, widgets: ['live', 'libraries', 'navatube', 'netflix', 'youtube'] },
    battery: 82, warning: false, dead: false, ended: false
  });
  let S = fresh(), timers = [], tvKey = '', phoneKey = '', miniShown = false, focusIndex = -1;
  const t = key => TEXT[lang][key];
  const lineup = () => LINEUP[country === 'us' ? 'us' : 'german'];
  const films = () => (country === 'us') === (lang === 'en') ? lineup().films : LINEUP[lang === 'en' ? 'us' : 'german'].films;
  const channel = () => lineup().channels[S.tv.channel];

  // ---------- television ----------
  const tile = (act, art, label, badge) => `<button class="tv-tile" data-act="${act}"><img src="${asset(art)}" alt="">${badge ? `<span class="badge">${badge}</span>` : ''}<span class="label"><span>${esc(label)}</span><span aria-hidden="true">→</span></span></button>`;
  const card = (act, art, small, title) => `<button class="tv-card" data-act="${act}" data-title="${esc(title)}"><img src="${asset(art)}" alt=""><span class="meta"><small>${esc(small)}</small><b>${esc(title)}</b></span></button>`;
  const tvNav = active => `<nav class="tv-nav">${['home', 'watchlist', 'apps', 'settings'].map(p => `<button data-act="tv-go:${p}"${p === active ? ' aria-current="page"' : ''}>${t(p)}</button>`).join('')}</nav>`;
  const heading = (title, sub) => `<div class="tv-title">${esc(title)}</div>${sub ? `<div class="tv-sub">${esc(sub)}</div>` : ''}`;

  const LANDSCAPES = ['mountains.jpg', 'ocean.jpg', 'forest.jpg', 'alpine-lake.webp', 'mountains.jpg'];
  const clockText = date => date.toLocaleTimeString(lang === 'de' ? 'de-DE' : 'en-GB', { hour: '2-digit', minute: '2-digit' });

  /** Live TV as the real app shows it: programme details above, three controls below, over a calm landscape. */
  function liveMarkup() {
    const tv = S.tv, now = new Date(), start = new Date(now);
    start.setMinutes(Math.floor(now.getMinutes() / 15) * 15 - 15, 0, 0);
    const end = new Date(start.getTime() + 45 * 60000);
    const done = Math.min(100, Math.max(0, (now - start) / (end - start) * 100));
    const shows = films(), on = shows[tv.channel % shows.length], following = shows[(tv.channel + 3) % shows.length];
    const number = String(tv.channel + 1).padStart(2, '0');
    const control = (act, glyph, label, extra) => `<button class="lv-button${extra || ''}" data-act="${act}"><span class="lv-round">${glyph}</span><span>${label}</span></button>`;
    return `<div class="lv${tv.paused ? ' is-paused' : ''}">
      <img class="lv-picture" src="${asset(LANDSCAPES[tv.channel % LANDSCAPES.length])}" alt=""><div class="lv-shade"></div>
      <div class="lv-brand"><i></i><i></i><i></i><b>${t('live')}</b></div>
      <div class="lv-clock"><i></i>${clockText(now)} ${t('localTime')}</div>
      <div class="lv-info">
        <p class="lv-tag">${t('channelWord')} ${number} · ${tv.paused ? t('paused') : t('onAir')}${tv.recording ? ' · <em>● REC</em>' : ''}</p>
        <h3>${esc(channel())}</h3>
        <p class="lv-title">${esc(on)}</p>
        <p class="lv-time"><span>${clockText(start)}</span><span class="lv-bar"><i style="width:${done}%"></i></span><span>${clockText(end)}</span><span>· ${t('remaining')} ${Math.max(1, Math.round((end - now) / 60000))} Min</span></p>
        <p class="lv-next"><b>${t('upNext')}</b><span>${clockText(end)}</span><span>${esc(following)}</span></p>
      </div>
      <div class="lv-number" aria-hidden="true">${number}</div>
      <div class="lv-controls">
        ${control('toggle', icon(tv.paused ? 'play' : 'pause', true), tv.paused ? t('resume') : t('pause'), ' main')}
        ${control('record', '<i class="lv-dot"></i>', tv.recording ? t('stopRecord') : t('record'), tv.recording ? ' recording' : '')}
        ${control('tv-go:libraries', icon('library'), t('libraryWord'), '')}
      </div></div>`;
  }

  /** A film or video, as the real library player shows it. */
  function playerMarkup() {
    const tv = S.tv, now = new Date();
    const control = (glyph, label, extra) => `<span class="lv-button${extra || ''}"><span class="lv-round">${glyph}</span><span>${label}</span></span>`;
    return `<div class="lv">
      <img class="lv-picture" src="${asset(tv.picture)}" alt=""><div class="lv-shade"></div>
      <div class="lv-brand"><i></i><i></i><i></i><b>${esc(tv.source)}</b></div>
      <div class="lv-clock"><i></i>${clockText(now)}</div>
      <h3 class="pl-title">${esc(tv.film)}</h3>
      <div class="pl-seek"><span class="pl-position">0:32</span><span class="pl-track"><i></i></span><span>1:28:49</span></div>
      <div class="lv-controls">
        ${control(icon('pause', true), t('pause'), ' main')}
        ${control(icon('rewind', true), t('back10'))}
        ${control(icon('forward', true), t('ahead10'))}
      </div></div>`;
  }

  function tvMarkup() {
    const tv = S.tv, line = lineup();
    if (tv.page === 'tube-player') {
      const { id, title } = tv.video;
      return `<div class="tube-player"><div class="tube-toolbar"><button data-act="tv-go:youtube">← ${t('back')}</button>
        <a href="https://www.youtube.com/watch?v=${id}" target="_blank" rel="noopener">${t('watchOnYouTube')} ↗</a></div>
        <iframe src="https://www.youtube-nocookie.com/embed/${id}?autoplay=1&amp;playsinline=1" title="${esc(title)}"
          allow="autoplay; encrypted-media; fullscreen; picture-in-picture" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe></div>`;
    }
    if (tv.page === 'live') return liveMarkup();
    if (tv.page === 'player') return playerMarkup();
    let body = '';
    if (tv.page === 'home') {
      const logos = line.libraries[0].logo ? line.libraries.map(l => `<img src="${asset(l.logo)}" alt="${l.short}">`).join('') : '';
      body = `<div class="tv-tiles">${tile('tv-live', 'ocean.jpg', t('live'), icon('tv'))}${tile('tv-go:libraries', 'forest.jpg', t('libraries'), logos)}${tile('tv-go:youtube', 'mountains.jpg', 'YouTube', '<svg class="yt" viewBox="0 0 28 20" aria-hidden="true"><path fill="#ff0033" d="M27.4 3.1a3.5 3.5 0 0 0-2.5-2.5C22.7 0 14 0 14 0S5.3 0 3.1.6A3.5 3.5 0 0 0 .6 3.1C0 5.3 0 10 0 10s0 4.7.6 6.9a3.5 3.5 0 0 0 2.5 2.5c2.2.6 10.9.6 10.9.6s8.7 0 10.9-.6a3.5 3.5 0 0 0 2.5-2.5c.6-2.2.6-6.9.6-6.9s0-4.7-.6-6.9Z"/><path fill="#fff" d="M11.2 5.8v8.4l7.3-4.2Z"/></svg>')}</div>
        <button class="tv-search" data-act="tv-go:youtube">${icon('search')}<span>${t('ask')}</span><small>${t('libraries')} · YouTube</small></button>`;
    } else if (tv.page === 'libraries') {
      body = heading(t('libraries'), t('chooseLibrary')) + `<div class="tv-tiles low">${line.libraries.map((l, i) => tile('tv-library:' + i, l.art, l.name,
        l.logo ? `<img src="${asset(l.logo)}" alt="">` : `<span class="initial" style="background:${l.color}">${l.name[0]}</span>`)).join('')}</div>`;
    } else if (tv.page === 'library') {
      const lib = line.libraries[tv.library];
      body = heading(lib.name, t('latest')) + `<div class="tv-grid">${films().map((f, i) => card('film', ART[(i + tv.library) % 6], lib.name, f)).join('')}</div>`;
    } else if (tv.page === 'youtube') {
      body = heading('YouTube', 'Luna technologys') + `<div class="tv-grid tube-grid">${CHANNEL_VIDEOS.map(({ id, title }) =>
        `<button class="tv-card" data-act="tube:${id}"><img src="${thumbnail(id)}" alt=""><span class="meta"><b>${esc(title)}</b></span></button>`).join('')}</div>`;
    } else if (tv.page === 'apps') {
      body = heading(t('apps'), t('appsSub')) + `<div class="tv-tiles low">${TV_APPS.slice(0, 3).map((a, i) => tile('app:' + a.id, ['mountains.jpg', 'ocean.jpg', 'forest.jpg'][i], a.name, `<span class="initial" style="background:${a.color}">${a.letter}</span>`)).join('')}</div>`;
    } else if (tv.page === 'watchlist') {
      body = heading(t('watchlist'), '') + `<div class="tv-empty">${t('empty')}</div>`;
    } else if (tv.page === 'settings') {
      const chapters = ['appearance', 'backgrounds', 'phoneAccounts', 'system'];
      const rows = [[['personal', 'personalSub'], ['dim', 'dimSub']], [['backgrounds', 'backgroundsSub']], [['phoneAccounts', 'phoneAccountsSub']], [['system', 'systemSub']]][tv.chapter];
      body = heading(t('settings'), '') + `<div class="tv-chapters">${chapters.map((c, i) => `<button class="tv-btn${i === tv.chapter ? ' on' : ''}" data-act="tv-chapter:${i}"><b>${t(c)}</b><small>${t(c + 'Sub')}</small></button>`).join('')}</div>
        <div class="tv-panel"><h3>${t(chapters[tv.chapter])}</h3>${rows.map(r => `<div class="tv-btn"><b>${t(r[0])}</b><small>${t(r[1])}</small></div>`).join('')}</div>`;
    }
    const active = ['home', 'watchlist', 'apps', 'settings'].includes(tv.page) ? tv.page : '';
    return `<img class="tv-bg" src="${asset('alpine-lake.webp')}" alt=""><div class="tv-shade"></div><div class="tv-page"><span class="logo" translate="no">Opennava</span>${body}${tvNav(active)}</div>`;
  }

  // ---------- phone ----------
  const widgetInfo = id => {
    if (id === 'live') return { name: t('live') };
    if (id === 'libraries') return { name: t('libraries'), note: lineup().line, art: 'forest.jpg', mark: icon('library'), markStyle: 'background:#0f2a22cc', act: 'ph-go:library' };
    if (id === 'navatube') return { name: 'NavaTube', note: t('yourVideos'), ground: 'linear-gradient(140deg,#8d72e8,#3b2a78 55%,#1c1540)', mark: icon('play', true), markStyle: 'background:#e8defe;color:#3b2a78', act: 'ph-go:navatube' };
    if (id === 'youtube') return { name: 'YouTube', note: t('openOnTv'), ground: 'linear-gradient(140deg,#7d1620,#25090d)', mark: icon('play', true), markStyle: 'background:#ff2438', act: 'ph-youtube' };
    const app = TV_APPS.find(a => a.id === id);
    return { name: app.name, note: t('openOnTv'), ground: `linear-gradient(140deg, ${app.color}66, #12060a)`, mark: app.letter, markStyle: `background:#000;color:${app.color};font-size:1.25em`, act: 'app:' + app.id };
  };

  function widgetMarkup(id) {
    const wide = id === 'live', info = widgetInfo(id);
    const remove = S.ph.editing ? `<button class="remove" data-act="widget-remove:${id}" aria-label="${t('remove')} ${esc(info.name)}">−</button>` : '';
    if (wide) {
      const on = S.tv.page === 'live';
      return `<div class="widget wide" data-widget="${id}"><button class="tile photo live" data-act="ph-go:live"><img src="${asset('ocean.jpg')}" alt="">
        <span class="live-badge"><i class="dot"></i>${on ? t('liveOnTv') : t('liveTv')}</span><span class="words"><b>${esc(channel())}</b><small>${t('allChannels')}</small></span><span class="go">${icon('right')}</span></button>${remove}</div>`;
    }
    return `<div class="widget" data-widget="${id}"><button class="tile${info.art ? ' photo' : ''}" data-act="${info.act}" style="${info.ground ? 'background:' + info.ground : ''}">${info.art ? `<img src="${asset(info.art)}" alt="">` : ''}
      <span class="mark" style="${info.markStyle}">${info.mark}</span><span class="words"><b>${esc(info.name)}</b><small>${esc(info.note)}</small></span></button>${remove}</div>`;
  }

  function tubePhoneMarkup() {
    const query = S.ph.tubeQuery;
    const words = query.toLowerCase().split(/\s+/);
    const videos = CHANNEL_VIDEOS.filter(({ title }) => words.every(word => (title + ' Luna technologys').toLowerCase().includes(word)));
    const results = videos.map(({ id, title }) => `<button class="tube-video" data-act="tube:${id}" aria-label="${esc(title)} · ${t('playOnTv')}">
      <span class="tube-picture"><img src="https://i.ytimg.com/vi/${id}/hqdefault.jpg" alt=""><span aria-hidden="true">▶</span></span>
      <b>${esc(title)}</b><small>Luna technologys</small></button>`).join('');
    return `<form class="tube-search" role="search"><input type="text" maxlength="200" enterkeyhint="search" aria-label="${t('tubeSearch')}" placeholder="${t('tubeSearch')}" value="${esc(query)}">
        <button type="${query ? 'button' : 'submit'}"${query ? ' data-act="tube-clear"' : ''} aria-label="${t(query ? 'tubeClear' : 'tubeSubmit')}">${icon(query ? 'close' : 'right')}</button></form>
      <h3 class="tube-heading" tabindex="-1">${query ? esc(t('tubeResults').replace('$1', () => query)) : t('tubeVideos')}</h3>
      ${results || `<p class="tube-empty">${t('tubeEmpty')}</p>`}
      <p class="tube-note">${t('tubeNote')}</p><button class="tube-action tube-show" data-act="ph-youtube">${icon('tv')}${t('tubeShow')}</button>`;
  }

  function phoneBody() {
    const ph = S.ph, line = lineup();
    if (ph.page === 'home') {
      const hour = new Date().getHours(), greeting = t('greeting')[hour < 5 ? 2 : hour < 12 ? 0 : hour < 18 ? 1 : 2];
      return `<div class="hello"><h2>${greeting}</h2>${ph.editing ? `<button class="pencil done" data-act="edit">${t('doneEdit')}</button>` : `<button class="pencil" data-act="edit" aria-label="${t('edit')}">${icon('pencil')}</button>`}</div>
        <p class="ask${ph.editing ? ' editing' : ''}">${ph.editing ? t('editHint') : t('watch')}</p>
        <div class="board${ph.editing ? ' editing-board' : ''}">${ph.widgets.map(widgetMarkup).join('')}</div>
        ${ph.editing ? `<button class="add-widget" data-act="sheet">＋ ${t('addWidget')}</button>` : ''}`;
    }
    if (ph.page === 'live') {
      const on = S.tv.page === 'live';
      return `<div class="now-card"><div class="row-top"><span><i class="dot" style="${on ? '' : 'background:#6b7286'}"></i>${on ? t('onTv') : t('preview')}</span><em>${esc(channel())}</em></div><b>${t('stream')}</b></div>
        <div class="channels">${line.channels.map((c, i) => `<button class="channel" data-act="channel:${i}"${i === S.tv.channel ? ' aria-current="true"' : ''}><small>${String(i + 1).padStart(2, '0')}</small>${esc(c)}</button>`).join('')}</div>
        <div class="transport"><button class="round main" data-act="${on ? 'toggle' : 'channel:' + S.tv.channel}" aria-label="${on && !S.tv.paused ? t('pause') : t('play')}">${icon(on && !S.tv.paused ? 'pause' : 'play', true)}</button></div>`;
    }
    if (ph.page === 'library') {
      const lib = line.libraries[ph.library], list = films();
      return `<div class="lib-tabs">${line.libraries.map((l, i) => `<button data-act="ph-library:${i}"${i === ph.library ? ' aria-current="true"' : ''}>${esc(l.short)}</button>`).join('')}</div>
        <button class="hero-card" data-act="film" data-title="${esc(list[ph.library])}"><img src="${asset(lib.art)}" alt=""><span><b>${esc(list[ph.library])}</b><i class="play-pill">${icon('play', true)}${t('playOnTv')}</i></span></button>
        ${list.slice(3).map((f, i) => `<button class="video-row" data-act="film" data-title="${esc(f)}"><span class="thumb"><img src="${asset(ART[(i + ph.library) % 6])}" alt=""></span><span><b>${esc(f)}</b><small>${esc(lib.name)}</small></span></button>`).join('')}`;
    }
    if (ph.page === 'navatube') return tubePhoneMarkup();

    const key = (dir, label) => `<button class="${dir}" data-act="key:${dir}" aria-label="${label}">${dir === 'ok' ? 'OK' : icon(dir)}</button>`;
    return `<div class="remote"><div class="dpad">${key('up', 'Up')}${key('left', 'Left')}${key('ok', 'OK')}${key('right', 'Right')}${key('down', 'Down')}</div>
      <div class="remote-row"><button class="round" data-act="key:back" aria-label="${t('back')}">${icon('back')}</button><button class="round" data-act="ph-tab:home" aria-label="${t('home')}">${icon('home')}</button><button class="round" data-act="toggle" aria-label="${t('play')}">${icon('play', true)}</button></div></div>`;
  }

  function miniMarkup() {
    const film = S.tv.page === 'player';
    if (S.tv.page !== 'live' && !film) return '';
    const playing = !S.tv.paused;
    return `<div class="mini${miniShown ? '' : ' enter'}"><span class="art"><img src="${asset(film ? S.tv.picture : 'ocean.jpg')}" alt=""><span class="eq${playing ? '' : ' rest'}"><i></i><i></i><i></i><i></i></span></span>
      <span class="text"><b>${esc(film ? S.tv.film : channel())}</b><small>${film ? esc(S.tv.source) : t('liveOn')} · OpenNava</small></span>
      <button class="round main small" data-act="toggle" aria-label="${playing ? t('pause') : t('play')}">${icon(playing ? 'pause' : 'play', true)}</button>
      <button class="round small" data-act="stop" aria-label="${t('stop')}">${icon('close')}</button><span class="progress"><i></i></span></div>`;
  }

  function sheetMarkup() {
    if (!S.ph.sheet) return '';
    const row = (id, name, note, thumb) => { const on = S.ph.widgets.includes(id); return `<button class="row${on ? ' on' : ''}" data-act="widget-toggle:${id}"><span class="thumb"${thumb.style ? ` style="${thumb.style}"` : ''}>${thumb.html}</span><span><b>${esc(name)}</b><small>${esc(note)}</small></span><span class="plus">${icon(on ? 'check' : 'plus')}</span></button>`; };
    const own = [['live', 'live'], ['libraries', 'library'], ['navatube', 'play'], ['youtube', 'tv']];
    return `<div class="sheet-shade" data-act="sheet"></div><div class="sheet"><h3>${t('addWidgets')}<button data-act="sheet" aria-label="${t('close')}">${icon('close')}</button></h3>
      <small style="color:var(--lilac);font-weight:800;font-size:.62em">${t('ownTiles')}</small>${own.map(([id, glyph]) => row(id, widgetInfo(id).name, 'OpenNava', { html: icon(glyph) })).join('')}
      <small style="color:var(--lilac);font-weight:800;font-size:.62em">${t('tvApps')}</small>${TV_APPS.map(a => row(a.id, a.name, t('appOnTv'), { html: a.letter, style: 'background:' + a.color })).join('')}</div>`;
  }

  function phoneMarkup() {
    const ph = S.ph, sub = ph.page === 'navatube';
    const tabs = [['home', 'home', 'tabHome'], ['live', 'live', 'tabLive'], ['library', 'library', 'tabLibrary'], ['remote', 'remote', 'tabRemote']];
    return `<div class="ph-head">${sub ? `<button class="back" data-act="ph-go:home" aria-label="${t('back')}">${icon('left')}</button><h2>NavaTube</h2>` : '<span class="logo" translate="no">Opennava</span>'}${powerMarkup()}</div>
      <div class="ph-body${sub ? ' tube-body' : ''}${S.tv.page === 'live' || S.tv.page === 'player' ? ' mini-space' : ''}">${phoneBody()}</div>
      ${miniMarkup()}
      <nav class="tabs">${tabs.map(([id, glyph, label]) => `<button data-act="ph-tab:${id}"${ph.page === id ? ' aria-current="page"' : ''}>${icon(glyph)}<span>${t(label)}</span></button>`).join('')}</nav>
      ${sheetMarkup()}
      ${S.warning && !S.dead ? `<div class="battery-warning" role="alert"><b>${t('lowTitle')}</b><small>${t('lowText')}</small></div>` : ''}
      ${S.dead ? `<div class="dead" role="img" aria-label="${t('dead')}"><span class="empty"><i></i></span></div>` : ''}`;
  }

  // ---------- drawing ----------
  const powerMarkup = () => `<span id="power" class="battery${S.battery <= 15 ? ' low' : ''}" title="${t('phoneBattery')}"><span class="percent">${S.battery}%</span><span class="cell"><i style="width:${S.battery}%"></i></span></span>`;
  function render() {
    const nextTv = JSON.stringify([S.tv, lang, country]);
    if (nextTv !== tvKey) { tvKey = nextTv; tvRoot.innerHTML = tvMarkup(); focusIndex = -1; const v = tvRoot.querySelector('video'); if (v) { if (S.tv.paused) v.pause(); else v.play().catch(() => {}); } }
    const old = phoneRoot.querySelector('.ph-body'), sheet = phoneRoot.querySelector('.sheet');
    const scroll = old ? old.scrollTop : 0, sheetScroll = sheet ? sheet.scrollTop : 0;
    const same = phoneKey === S.ph.page; phoneKey = S.ph.page;
    phoneRoot.innerHTML = phoneMarkup();
    if (same) phoneRoot.querySelector('.ph-body').scrollTop = scroll;
    const newSheet = phoneRoot.querySelector('.sheet'); if (newSheet && sheet) { newSheet.style.animation = 'none'; newSheet.scrollTop = sheetScroll; }
    miniShown = S.tv.page === 'live' || S.tv.page === 'player';
  }

  function chrome() {
    $('note').textContent = t('note');
    $('region-button').textContent = `${t('countryLabel')}: ${t(country)}`;
    $('agent-link').textContent = t('agentLink');
    $('agent-link').href = new URL('../agent/' + (lang === 'de' ? '#de' : ''), base).href;
  }

  // ---------- the two react to each other ----------
  /** Something happened on the television: the phone follows it, like the real app. */
  function tvGo(page, library) {
    S.tv.page = page; S.tv.paused = false;
    if (library !== undefined) S.tv.library = library;
    const follow = { home: 'home', live: 'live', libraries: 'library', library: 'library', youtube: 'navatube' }[page];
    if (follow && !S.ph.editing) { S.ph.page = follow; S.ph.sheet = false; if (page === 'library') S.ph.library = library; }
  }

  /** The battery runs down in front of the viewer's eyes, then the phone goes dark. */
  function drainBattery() {
    if (S.ended) return;
    S.ended = true; S.ph.editing = false; S.ph.sheet = false;
    const later = (ms, run) => timers.push(setTimeout(run, ms));
    const levels = [61, 38, 19, 9, 4, 1];
    levels.forEach((level, i) => later(900 + i * 190, () => { S.battery = level; if (level === 1) S.warning = true; render(); }));
    later(3300, () => { S.dead = true; S.battery = 0; render(); });
    later(4300, showFinale);
    // The film's clock runs on while it plays.
    [1, 2, 3, 4].forEach(second => later(second * 1000, () => {
      const position = tvRoot.querySelector('.pl-position');
      if (position) position.textContent = '0:' + (32 + second);
    }));
  }

  function startFilm(title, source) {
    if (S.ended) return;
    const pictures = ['alpine-lake.webp', 'forest.jpg', 'ocean.jpg', 'mountains.jpg'];
    S.tv.page = 'player'; S.tv.paused = false; S.tv.film = title; S.tv.source = source;
    S.tv.picture = pictures[[...title].reduce((sum, c) => sum + c.charCodeAt(0), 0) % pictures.length];
    render();
    drainBattery();
  }

  /** Live television starts like any other programme. */
  function startLive() {
    render();
    drainBattery();
  }

  function showFinale() {
    finale.innerHTML = `<div><span class="logo" translate="no">Opennava</span><p>${t('finaleEyebrow')}</p><h2>${t('finaleTitle')}</h2><p>${t('finaleOr')}</p>
      <div class="address"><span>${esc(config.agentAddress)}</span><button type="button" data-act="copy">${t('copy')}</button></div>
      <div class="actions"><a class="primary" href="${esc(config.downloadUrl)}">${t('download')}</a><button type="button" data-act="replay">${t('replay')}</button></div></div>`;
    finale.hidden = false;
    finale.setAttribute('role', 'status');
    // On narrow screens the viewer is looking at the phone below; bring the message into view.
    if (window.matchMedia('(max-width: 900px)').matches) finale.parentElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
    tvRoot.querySelector('.tube-player iframe')?.remove();
    const video = tvRoot.querySelector('video'); if (video) video.pause();
  }

  function replay() {
    timers.forEach(clearTimeout); timers = [];
    S = fresh(); finale.hidden = true; finale.innerHTML = ''; tvKey = ''; phoneKey = ''; miniShown = false;
    render();
  }

  /** The phone gets its charge, in a little scene of its own, and the demo starts over. */
  function chargeAndRestart() {
    return window.OpenNavaCharge.play(shadow, { label: t('chargeLabel'), charging: t('charging'), charged: t('charged') }, replay);
  }

  async function copyPrompt(button) {
    const text = t('prompt') + config.agentAddress;
    try { await navigator.clipboard.writeText(text); }
    catch (e) {
      const area = document.createElement('textarea'); area.value = text; document.body.append(area); area.select();
      document.execCommand('copy'); area.remove();
    }
    button.textContent = t('copied');
  }

  /** The phone's arrows move a focus frame across the television, as a remote does. */
  function remoteKey(key) {
    if (key === 'back') { if (S.tv.page === 'library') tvGo('libraries'); else tvGo('home'); S.ph.page = 'remote'; return render(); }
    if (S.tv.page === 'live' && (key === 'up' || key === 'down')) {
      const count = lineup().channels.length;
      S.tv.channel = (S.tv.channel + (key === 'up' ? 1 : count - 1)) % count;
      S.tv.paused = false; S.tv.recording = false;
      return render();
    }
    const items = [...tvRoot.querySelectorAll('button[data-act]')];
    if (!items.length) { if (key === 'ok' && S.tv.page === 'live') { S.tv.paused = !S.tv.paused; render(); } return; }
    let current = items[focusIndex];
    if (!current) { focusIndex = 0; current = items[0]; if (key !== 'ok') return mark(items); }
    if (key === 'ok') { run(current.dataset.act, current); if (!S.ended) { S.ph.page = 'remote'; render(); } return; }
    const from = current.getBoundingClientRect(), cx = from.x + from.width / 2, cy = from.y + from.height / 2;
    let best = -1, score = Infinity;
    items.forEach((item, i) => {
      const r = item.getBoundingClientRect(), dx = r.x + r.width / 2 - cx, dy = r.y + r.height / 2 - cy;
      const along = { left: -dx, right: dx, up: -dy, down: dy }[key], across = key === 'left' || key === 'right' ? Math.abs(dy) : Math.abs(dx);
      if (i !== focusIndex && along > 4 && along + across * 2.4 < score) { score = along + across * 2.4; best = i; }
    });
    if (best >= 0) focusIndex = best;
    mark(items);
  }
  function mark(items) { items.forEach((item, i) => item.classList.toggle('tv-focus', i === focusIndex)); }

  function run(action, element) {
    const [name, value] = action.split(':');
    if (name === 'replay') return void chargeAndRestart();
    if (name === 'copy') return void copyPrompt(element);
    if (S.ended) return;
    switch (name) {
      case 'tv-go': tvGo(value); break;
      case 'tv-live': tvGo('live'); return startLive();
      case 'tv-library': tvGo('library', Number(value)); break;
      case 'tv-chapter': S.tv.chapter = Number(value); break;
      case 'tube-clear':
        S.ph.tubeQuery = '';
        render();
        phoneRoot.querySelector('.tube-search input').focus({ preventScroll: true });
        return;
      case 'tube': {
        const video = CHANNEL_VIDEOS.find(item => item.id === value);
        if (!video) return;
        S.tv.page = 'tube-player';
        S.tv.video = video;
        render();
        if (window.matchMedia('(max-width: 900px)').matches) tvRoot.scrollIntoView({ behavior: 'smooth', block: 'center' });
        return;
      }
      case 'film': return startFilm(element.dataset.title, t('libraryWord'));
      case 'app': {
        const app = TV_APPS.find(a => a.id === value);
        if (app.film) return startFilm(films()[1], app.name);
        S.tv.page = 'apps'; break;
      }
      case 'ph-tab':
        S.ph.page = value; S.ph.editing = false; S.ph.sheet = false;
        // The phone's Home button also takes the television home.
        if (value === 'home' && S.tv.page !== 'home') { S.tv.page = 'home'; S.tv.paused = false; }
        break;
      case 'ph-go': S.ph.page = value; break;
      case 'ph-library': S.ph.library = Number(value); break;
      case 'ph-youtube': S.tv.page = 'youtube'; S.tv.paused = false; S.ph.page = 'navatube'; break;
      case 'channel': if (S.tv.channel !== Number(value)) S.tv.recording = false; S.tv.channel = Number(value); S.tv.page = 'live'; S.tv.paused = false; S.ph.page = 'live'; return startLive();
      case 'toggle': if (S.tv.page === 'live') S.tv.paused = !S.tv.paused; break;
      case 'record': S.tv.recording = !S.tv.recording; break;
      case 'stop': S.tv.page = 'home'; S.tv.paused = false; S.ph.page = 'home'; break;
      case 'edit': S.ph.editing = !S.ph.editing; S.ph.sheet = false; break;
      case 'sheet': S.ph.sheet = !S.ph.sheet; break;
      case 'widget-remove': S.ph.widgets = S.ph.widgets.filter(w => w !== value); break;
      case 'widget-toggle': S.ph.widgets = S.ph.widgets.includes(value) ? S.ph.widgets.filter(w => w !== value) : [...S.ph.widgets, value]; break;
      case 'key': return remoteKey(value);
      default: return;
    }
    render();
  }

  phoneRoot.addEventListener('input', event => {
    if (!event.target.matches('.tube-search input')) return;
    const clear = S.ph.tubeQuery && event.target.value.trim() === S.ph.tubeQuery;
    const button = event.target.form.querySelector('button');
    button.type = clear ? 'button' : 'submit';
    if (clear) button.dataset.act = 'tube-clear';
    else delete button.dataset.act;
    button.innerHTML = icon(clear ? 'close' : 'right');
    button.setAttribute('aria-label', t(clear ? 'tubeClear' : 'tubeSubmit'));
  });
  phoneRoot.addEventListener('submit', event => {
    if (!event.target.matches('.tube-search')) return;
    event.preventDefault();
    if (S.ended) return;
    const typed = event.target.querySelector('input').value.trim();
    S.ph.tubeQuery = typed;
    render();
    phoneRoot.querySelector('.tube-heading').focus({ preventScroll: true });
    if (typed && phoneRoot.querySelector('.tube-empty')) drainBattery();
  });

  shadow.addEventListener('click', event => {
    const target = event.target.closest('[data-act]');
    if (target) run(target.dataset.act, target);
  });

  // ---------- dragging widgets ----------
  let drag = null;
  phoneRoot.addEventListener('pointerdown', event => {
    const widget = event.target.closest('.editing-board .widget');
    if (!widget || event.target.closest('.remove')) return;
    event.preventDefault();
    drag = { widget, x: event.clientX, y: event.clientY, id: event.pointerId };
    widget.classList.add('dragging');
    phoneRoot.setPointerCapture(event.pointerId);
  });
  phoneRoot.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.id) return;
    const w = drag.widget;
    w.style.transform = `translate(${event.clientX - drag.x}px, ${event.clientY - drag.y}px) scale(1.04)`;
    const over = shadow.elementsFromPoint(event.clientX, event.clientY).map(e => e.closest && e.closest('.widget')).find(e => e && e !== w);
    if (!over) return;
    const board = w.parentElement, all = [...board.children], before = w.getBoundingClientRect();
    board.insertBefore(w, all.indexOf(over) > all.indexOf(w) ? over.nextSibling : over);
    // Keep the widget under the finger after the grid has moved its slot.
    w.style.transform = '';
    const slot = w.getBoundingClientRect();
    drag.x = event.clientX - (before.x - slot.x); drag.y = event.clientY - (before.y - slot.y);
    w.style.transform = `translate(${event.clientX - drag.x}px, ${event.clientY - drag.y}px) scale(1.04)`;
  });
  const drop = () => {
    if (!drag) return;
    S.ph.widgets = [...drag.widget.parentElement.children].map(c => c.dataset.widget);
    drag = null;
    render();
  };
  phoneRoot.addEventListener('pointerup', drop);
  phoneRoot.addEventListener('pointercancel', drop);

  // ---------- country; the language comes from the website's own language setting ----------
  function openRegion() {
    dialog.innerHTML = `<h2 id="region-title">${t('country2')}</h2><p>${t('regionHelp')}</p>
      <div class="choices">${['de', 'at', 'ch', 'us'].map(c => `<button type="button" data-region="${c}" aria-pressed="${country === c}">${t(c)}</button>`).join('')}</div>
      <button type="button" class="primary" data-region="done">${t('done')}</button>`;
    if (!dialog.open) dialog.showModal();
  }
  dialog.addEventListener('click', event => {
    const button = event.target.closest('[data-region]');
    if (!button) return;
    const code = button.dataset.region;
    if (code === 'done') { store.set({ country }); return dialog.close(); }
    country = code;
    store.set({ country });
    replay(); chrome(); openRegion();
  });
  $('region-button').addEventListener('click', openRegion);

  function followSiteLanguage() {
    const next = siteLanguage();
    if (next === lang) return;
    lang = next;
    replay(); chrome();
  }
  document.addEventListener('opennava:site-language', followSiteLanguage);
  if (window.OpenNavaSite) window.OpenNavaSite.ready.then(followSiteLanguage);

  chrome();
  render();
  // Only ask when the browser did not make the country clear.
  if (!saved && !guessed.sure) openRegion();
})();
