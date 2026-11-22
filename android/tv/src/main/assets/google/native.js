/* Native TV integration. The browser prototype continues to work without it. */
if (window.NavaTV) {
  document.body.classList.add('native-tv');
  let nativeRefreshAt = 0;
  const preferred = ['com.netflix.ninja', 'com.google.android.youtube.tv', 'com.amazon.amazonvideo.livingroom', 'com.disney.disneyplus', 'com.zdf.android.mediathek', 'de.swr.avp.ard.tv', 'com.viki.kindle'];
  const brandLogos = new Map([
    ['com.netflix.ninja', apps.find(a => a.id === 'netflix')?.logo],
    ['com.google.android.youtube.tv', apps.find(a => a.id === 'youtube')?.logo],
    ['com.amazon.amazonvideo.livingroom', apps.find(a => a.id === 'prime')?.logo],
    ['de.swr.avp.ard.tv', apps.find(a => a.id === 'ard')?.logo]
  ]);

  function refreshInstalledApps() {
    try {
      const installed = JSON.parse(NavaTV.getApps());
      if (!Array.isArray(installed)) return;
      const entries = installed.filter(a => /^[\w.]+$/.test(a.packageName || '') && a.name).map(a => {
        const artwork = typeof a.icon === 'string' && /^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(a.icon)
          ? `<img class="native-app-icon" src="${a.icon}" alt="">` : icon('monitor');
        return {id: a.packageName, name: a.name, logo: brandLogos.get(a.packageName) || `<span class="native-app-identity">${artwork}<span>${escapeHTML(a.name)}</span></span>`};
      });
      entries.sort((a,b) => {
        const ai = preferred.indexOf(a.id), bi = preferred.indexOf(b.id);
        return (ai < 0 ? 100 : ai) - (bi < 0 ? 100 : bi) || a.name.localeCompare(b.name, 'en');
      });
      apps.splice(0, apps.length, ...entries);
      nativeRefreshAt = Date.now();
    } catch (error) { console.error('OpenNava app list unavailable', String(error)); }
  }

  window.renderNativeApps = function() {
    main.innerHTML = `<section class="page"><p class="page-topline">YOUR GOOGLE TV APPS</p><h1>Your TV apps.</h1><p class="page-intro">${apps.length} installed apps. Select one to open it.</p><div class="app-grid native-all-apps">${apps.map(a => `<button class="app-card" data-app="${a.id}" aria-label="Open ${escapeHTML(a.name)}">${a.logo}</button>`).join('')}</div></section>`;
  };

  window.navaCustomizeSettings = function() {
    panel.classList.add('native-quick');
    const fullscreen = panel.querySelector('#quick-fullscreen');
    if (fullscreen) {
      fullscreen.id = 'quick-device';
      fullscreen.dataset.action = 'native-settings';
      fullscreen.dataset.kind = 'general';
      fullscreen.removeAttribute('aria-pressed');
      fullscreen.setAttribute('aria-label', 'Open device settings');
      fullscreen.querySelector('.quick-tile-title').textContent = 'Device settings';
      fullscreen.querySelector('.quick-tile-status').textContent = 'Picture, sound and more';
      fullscreen.querySelector('.quick-cinema-icon').innerHTML = icon('monitor');
    }
    const section = document.createElement('div');
    section.className = 'native-system-shortcuts';
    section.innerHTML = `<button data-action="native-settings" data-kind="network">Wi-Fi ${icon('chevron')}</button><button data-action="native-settings" data-kind="bluetooth">Bluetooth ${icon('chevron')}</button><button data-action="native-settings" data-kind="display">Display ${icon('chevron')}</button>`;
    panel.querySelector('.quick-appearance').before(section);
    const updates = document.createElement('button');
    updates.className = 'native-home-toggle';
    updates.textContent = navigator.language.startsWith('de') ? 'Softwareupdates' : 'Software updates';
    updates.setAttribute('data-update-badge', '');
    updates.setAttribute('aria-describedby', 'update-description');
    updates.addEventListener('click', () => window.NavaTV.openUpdates());
    section.after(updates);
    if (typeof NavaTV.openHomePersonalization === 'function') {
      const personalize = document.createElement('button');
      personalize.className = 'native-home-toggle';
      personalize.textContent = navigator.language.startsWith('de') ? 'Personalisierung' : 'Personalization';
      personalize.addEventListener('click', () => NavaTV.openHomePersonalization());
      updates.before(personalize);
    }
    const back = document.createElement('button');
    back.className = 'visitor-return-panel';
    back.dataset.action = 'return-senior';
    back.textContent = 'Senior view';
    section.after(back);
    requestAnimationFrame(() => panel.querySelector('#quick-device')?.focus());
    panel.querySelector('.quick-footer').innerHTML = '<span>☰ Quick settings</span><span>↩ Back</span>';
  };

  document.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.action === 'global-search') {
      event.preventDefault(); event.stopImmediatePropagation();
      window.navaOpenGlobalSearch();
      return;
    }
    if (button.dataset.app) {
      event.preventDefault(); event.stopImmediatePropagation();
      if (button.dataset.app === 'com.google.android.youtube.tv' && window.NavaTube) { navigate('youtube'); return; }
      if (button.dataset.app === 'de.swr.avp.ard.tv' && window.NavaTube) { NavaTube.showBroadcaster('ard'); return; }
      if (button.dataset.app === 'com.zdf.android.mediathek' && window.NavaTube) { NavaTube.showBroadcaster('zdf'); return; }
      try { if (!NavaTV.launchApp(button.dataset.app)) toast('Could not open this app.'); } catch { toast('Could not open this app.'); }
      return;
    }
    if (button.dataset.action === 'native-settings') {
      event.preventDefault(); event.stopImmediatePropagation();
      try { NavaTV.openSettings(button.dataset.kind || 'general'); } catch { toast('Could not open system settings.'); }
    }
    if (button.dataset.action === 'return-senior') {
      event.preventDefault(); event.stopImmediatePropagation();
      NavaTV.returnToSenior();
    }
  }, true);

  window.navaRemote = function(key) {
    if (['youtube','mediathek'].includes(state.view) && !panel.open && !player.open && window.NavaTube?.handleKey(key)) return;
    if (key === 'Escape') {
      if (player.open) closePlayer();
      else if (panel.open) closePanel();
      else { navigate('home'); main.querySelector('[data-app]')?.focus(); }
      return;
    }
    if (key === 'Menu') {
      if (panel.open) closePanel();
      else { if (player.open) closePlayer(); openSettings(); }
      return;
    }
    if (key === 'Enter') {
      const focused = document.activeElement;
      if (focused?.matches('button,a,input')) focused.click();
      else main.querySelector('[data-app]')?.focus();
      return;
    }
    if (key.startsWith('Media')) {
      if (player.open) {
        const video = player.querySelector('video');
        if (key === 'MediaPause') video.pause();
        else if (key === 'MediaPlay') video.play().catch(() => {});
        else togglePlay();
      }
      return;
    }
    const focused = document.activeElement;
    if (player.open && focused?.matches('input[type=range]') && (key === 'ArrowLeft' || key === 'ArrowRight')) {
      const video = player.querySelector('video');
      video.currentTime = Math.max(0, Math.min(video.duration || 12, video.currentTime + (key === 'ArrowLeft' ? -2 : 2)));
      return;
    }
    if (key.startsWith('Arrow')) moveFocus(key);
  };

  window.navaNativeHome = function() {
    if (player.open) closePlayer();
    if (panel.open) closePanel();
    refreshInstalledApps();
    navigate('home');
    requestAnimationFrame(() => main.querySelector('[data-app]')?.focus());
  };

  window.navaNativeResume = function() {
    if (Date.now() - nativeRefreshAt > 10000) {
      const appId = document.activeElement?.dataset?.app;
      refreshInstalledApps();
      if (!panel.open && !player.open && !['home','youtube','mediathek'].includes(state.view)) {
        render();
        const restore = appId ? main.querySelector(`[data-app="${appId}"]`) : null;
        (restore || main.querySelector('[data-app]') || main.querySelector('button'))?.focus();
      }
    }
  };

  window.navaOpenGlobalSearch = function(provider) {
    const target = provider === 'youtube' || provider === 'mediathek' ? provider
      : (['youtube','mediathek'].includes(state.view) ? state.view : 'mediathek');
    if (panel.open) closePanel();
    if (state.view !== target) navigate(target);
    window.NavaTube?.focusSearch({provider:target});
  };
  document.querySelector('.header [data-action="search"]').dataset.action = 'global-search';
  const youtubeNav = document.querySelector('.header [data-view="discover"]');
  youtubeNav.dataset.view = 'youtube'; youtubeNav.textContent = 'YouTube';

  const mediathekNav = document.createElement('button');
  mediathekNav.className = 'nav-button'; mediathekNav.dataset.view = 'mediathek'; mediathekNav.textContent = 'Media library';
  document.querySelector('.header nav').insertBefore(mediathekNav, youtubeNav);
  const appsNav = document.querySelector('.header [data-view="library"]');
  appsNav.dataset.view = 'apps'; appsNav.textContent = 'Apps';
  document.querySelector('.prototype-label').innerHTML = '<span></span> OpenNava <i>·</i> YOUR TELEVISION';
  document.querySelector('.keyboard-hints').innerHTML = '<span>↔ Navigate</span><span>OK Select</span><span>☰ Settings</span>';
  const footerButton = document.querySelector('.footer-fullscreen');
  footerButton.dataset.action = 'native-settings'; footerButton.dataset.kind = 'general';
  footerButton.innerHTML = `Google TV ${icon('settings')}`;
  refreshInstalledApps(); render();
  requestAnimationFrame(() => main.querySelector('[data-app]')?.focus());
  console.log('OpenNava native ready', JSON.stringify({width:innerWidth,height:innerHeight,dpr:devicePixelRatio,apps:apps.length}));
}
