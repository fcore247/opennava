/* Native TV integration. The browser prototype continues to work without it. */
if (window.NavaTV) {
  document.body.classList.add('native-tv');
  let nativeRefreshAt = 0;
  const preferred = ['com.netflix.ninja', 'com.amazon.firetv.youtube', 'com.amazon.firebat', 'com.disney.disneyplus', 'de.swr.ard.avp.mobile.android.amazon', 'com.viki.kindle'];
  const brandLogos = new Map([
    ['com.netflix.ninja', apps.find(a => a.id === 'netflix')?.logo],
    ['com.amazon.firetv.youtube', apps.find(a => a.id === 'youtube')?.logo],
    ['com.amazon.firebat', apps.find(a => a.id === 'prime')?.logo],
    ['de.swr.ard.avp.mobile.android.amazon', apps.find(a => a.id === 'ard')?.logo]
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
    main.innerHTML = `<section class="page"><p class="page-topline">YOUR FIRE TV APPS</p><h1>Your TV apps.</h1><p class="page-intro">${apps.length} installed apps. Select one to open it.</p><div class="app-grid native-all-apps">${apps.map(a => `<button class="app-card" data-app="${a.id}" aria-label="Open ${escapeHTML(a.name)}">${a.logo}</button>`).join('')}</div></section>`;
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
    section.innerHTML = `<button data-action="native-settings" data-kind="network">Wi-Fi ${icon('chevron')}</button><button data-action="native-settings" data-kind="bluetooth">Bluetooth ${icon('chevron')}</button><button data-action="amazon-home">Amazon Home ${icon('external')}</button>`;
    panel.querySelector('.quick-appearance').before(section);
    const updates = document.createElement('button');
    updates.className = 'native-home-toggle';
    updates.textContent = 'Software updates';
    updates.addEventListener('click', () => window.NavaTV.openUpdates());
    section.after(updates);
    const launcher = document.createElement('button');
    launcher.className = 'native-home-toggle';
    launcher.dataset.action = 'native-home-toggle';
    launcher.setAttribute('role', 'switch');
    launcher.innerHTML = '<span><strong>OpenNava as your home screen</strong><small>Restore the original Home button at any time</small></span><span class="quick-switch" aria-hidden="true"><span></span></span>';
    section.after(launcher);
    if (NavaTV.hasPersonalView?.()) {
      const personal = document.createElement('button');
      personal.className = 'native-home-toggle';
      personal.textContent = 'Senior view';
      personal.addEventListener('click', () => NavaTV.returnToSenior());
      launcher.after(personal);
    }
    updateLauncherToggle();
    requestAnimationFrame(() => panel.querySelector('#quick-device')?.focus());
    panel.querySelector('.quick-footer').innerHTML = '<span>☰ Quick settings</span><span>↩ Back</span>';
  };

  function updateLauncherToggle() {
    const button = panel.querySelector('[data-action="native-home-toggle"]');
    if (!button) return;
    let enabled = false;
    try { enabled = NavaTV.isLauncherEnabled(); } catch {}
    button.setAttribute('aria-checked', String(enabled));
    button.setAttribute('aria-label', `OpenNava as your home screen, ${enabled ? 'on' : 'off'}`);
  }

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
      if (button.dataset.app === 'com.amazon.firetv.youtube' && window.NavaTube) { navigate('youtube'); return; }
      if (button.dataset.app === 'de.swr.ard.avp.mobile.android.amazon' && window.NavaTube) { navigate('mediathek'); return; }
      try { if (!NavaTV.launchApp(button.dataset.app)) toast('Could not open this app.'); } catch { toast('Could not open this app.'); }
      return;
    }
    if (button.dataset.action === 'native-settings') {
      event.preventDefault(); event.stopImmediatePropagation();
      try { NavaTV.openSettings(button.dataset.kind || 'general'); } catch { toast('Could not open system settings.'); }
    }
    if (button.dataset.action === 'native-home-toggle') {
      event.preventDefault(); event.stopImmediatePropagation();
      const enabled = button.getAttribute('aria-checked') !== 'true';
      try {
        if (!NavaTV.setLauncherEnabled(enabled)) {
          toast('Set up the Home button on your computer first.');
          return;
        }
        updateLauncherToggle();
        toast(enabled ? 'The Home button now opens OpenNava.' : 'The Home button now opens Amazon Home.');
      } catch { toast('Could not change the setting.'); }
    }
    if (button.dataset.action === 'amazon-home') {
      event.preventDefault(); event.stopImmediatePropagation();
      NavaTV.openAmazonHome();
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
    updateLauncherToggle();
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
  footerButton.innerHTML = `Fire TV ${icon('settings')}`;
  refreshInstalledApps(); render();
  requestAnimationFrame(() => main.querySelector('[data-app]')?.focus());
  console.log('OpenNava native ready', JSON.stringify({width:innerWidth,height:innerHeight,dpr:devicePixelRatio,apps:apps.length}));
}
