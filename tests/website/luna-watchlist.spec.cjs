const {test, expect} = require('@playwright/test');
const {broadcasters} = require('./fixtures/live/broadcasters.cjs');

const dark = {id: '2', title: 'Dark', provider: 'Netflix'};
const arte = {id: '1', title: 'Stadt Land Kunst', provider: 'ARTE'};
const tatort = {id: '3', title: 'Tatort', provider: 'ARD'};
const listButton = '[data-home-action="luna-list"]';
const visiblePanel = '#panel[open]';

// Substitute only the three Android watchlist methods. Live TV, navigation and
// focus run through the shipped launcher and the browser's real device bridge.
async function startLauncher(page, saved = [], profile = 'full') {
  await page.addInitScript(initial => {
    if (sessionStorage.getItem('fixture-luna') === null) {
      sessionStorage.setItem('fixture-luna', JSON.stringify(initial));
    }
    let bridge;
    window.openedLunaTitles = [];
    window.renderedHomeSuggestions = [];
    new MutationObserver(records => {
      for (const record of records) for (const node of record.addedNodes) {
        if (!(node instanceof Element)) continue;
        // Capture even a discarded initial render, before the native home bridge is ready.
        const hero = node.matches('section.hero, .nava-hero') ? node : node.querySelector('section.hero, .nava-hero');
        if (hero) window.renderedHomeSuggestions.push(hero.textContent);
      }
    }).observe(document, {subtree:true, childList:true});
    const rows = () => JSON.parse(sessionStorage.getItem('fixture-luna'));
    const device = {
      getLunaSettings: () => sessionStorage.getItem('fixture-settings') || '{}',
      requestLunaSettings: () => { window.requestedSettings = (window.requestedSettings || 0) + 1; return true; },
      getLunaWatchlist: () => JSON.stringify(rows()),
      openLunaProgramme: id => { window.openedLunaTitles.push(id); },
      removeLunaProgramme: id => {
        sessionStorage.setItem('fixture-luna', JSON.stringify(rows().filter(item => item.id !== id)));
        return true;
      }
    };
    Object.defineProperty(window, 'NavaTV', {
      get: () => bridge,
      set: value => { bridge = {...value, ...device}; }
    });
  }, saved);
  await page.goto('demo/' + profile + '/index.html');
  await expect(page.locator('html')).toHaveAttribute('data-launcher-ready', 'true');
}

async function receive(page, rows) {
  await page.evaluate(items => {
    sessionStorage.setItem('fixture-luna', JSON.stringify(items));
    window.NavaHome.watchlistChanged();
  }, rows);
}

const remote = (page, key) => page.evaluate(value => window.navaRemote(value), key);
const playbackCalls = page => page.evaluate(() => window.openedLunaTitles);

async function checkHome(page, saved) {
  const home = page.locator('#nava-home');
  await expect(home).toBeVisible();
  await expect(home.locator(listButton)).toBeVisible();
  expect(await page.evaluate(() => window.renderedHomeSuggestions)).toEqual([]);
  await expect(home.getByText(/featured programme|watch now/i)).toHaveCount(0);
  await expect(home.locator('[data-luna-play]')).toHaveCount(0);
  for (const {title} of [...saved, {title:'Above the clouds'}]) {
    await expect(home).not.toContainText(title);
  }
}

test('the empty watchlist is usable and Home does not recommend a programme', async ({page}) => {
  await startLauncher(page);
  await checkHome(page, []);
  const doors = page.locator('#nava-home [data-dock]');
  await expect(doors).toHaveCount(3);
  await expect(page.getByRole('button', {name:'Live TV', exact:true})).toBeFocused();
  await remote(page, 'ArrowLeft');
  await expect(doors.first()).toBeFocused();
  for (let i = 1; i < 3; i++) {
    await remote(page, 'ArrowRight');
    await expect(doors.nth(i)).toBeFocused();
  }
  await remote(page,'ArrowDown');
  await expect(page.locator('.nava-home-search')).toBeFocused();
  await remote(page,'ArrowDown');
  await expect(page.locator('#bottom-navigation :focus')).toHaveCount(1);
  await expect(page.locator('#bottom-navigation [data-bottom]')).toHaveCount(4);
  const list = page.locator(listButton);
  await list.focus();
  await remote(page, 'Enter');
  const panel = page.locator(visiblePanel);
  await expect(panel).toBeVisible();
  await expect(panel.locator('[data-luna-play]')).toHaveCount(0);
  await expect(panel.getByRole('button')).not.toHaveCount(0);
  await remote(page, 'Escape');
  await expect(panel).toHaveCount(0);
  await expect(list).toBeFocused();
  expect(await playbackCalls(page)).toEqual([]);
});

test('on cold start or wake, OK opens the watchlist and a second OK opens only the selected title', async ({page}) => {
  await startLauncher(page, [dark, arte]);
  const list = page.locator(listButton);
  const panel = page.locator(visiblePanel);
  for (const wake of [false, true]) {
    if (wake) {
      await page.getByRole('button', {name:/^Apps\b/}).click();
      await expect(page.getByRole('heading', {name: 'Apps', exact:true})).toBeVisible();
      await page.evaluate(() => window.NavaHome.wake());
    }
    await checkHome(page, [dark, arte]);
    await expect(list).toBeFocused();
    const opened = await playbackCalls(page);
    await remote(page, 'Enter');
    await expect(panel).toBeVisible();
    await expect(panel.locator('[data-luna-play="2"]')).toBeFocused();
    expect(await playbackCalls(page)).toEqual(opened);
    if (wake) {
      await remote(page, 'ArrowDown');
      await expect(panel.locator('[data-luna-play="1"]')).toBeFocused();
    }
    await remote(page, 'Enter');
    expect(await playbackCalls(page)).toEqual([...opened, wake ? '1' : '2']);
    await remote(page, 'Escape');
    await expect(list).toBeFocused();
  }
});

test('an incoming title preserves the selected list row and removal reaches the empty list', async ({page}) => {
  await startLauncher(page, [dark, arte]);
  const list = page.locator(listButton);
  const panel = page.locator(visiblePanel);
  await expect(list).toBeFocused();
  await remote(page, 'Enter');
  await remote(page, 'ArrowDown');
  await expect(panel.locator('[data-luna-play="1"]')).toBeFocused();
  await receive(page, [tatort, dark, arte]);
  await expect(panel.locator('[data-luna-play="3"]')).toBeVisible();
  await expect(panel.locator('[data-luna-play="1"]')).toBeFocused();
  await remote(page, 'Escape');
  await expect(list).toBeFocused();
  await remote(page, 'Enter');
  for (const {id} of [tatort, dark, arte]) await panel.locator('[data-luna-remove="' + id + '"]').click();
  await expect(page.locator('#panel[open] [data-luna-play]')).toHaveCount(0);
  if (await panel.isVisible()) await remote(page, 'Escape');
  await expect(list).toBeFocused();
  await remote(page, 'Enter');
  await expect(panel).toBeVisible();
  await expect(panel.locator('[data-luna-play]')).toHaveCount(0);
  expect(await playbackCalls(page)).toEqual([]);
});

test('incoming titles preserve Apps focus, live playback and the Live TV return target', async ({page}) => {
  await broadcasters(page);
  await startLauncher(page);
  await page.getByRole('button', {name:/^Apps\b/}).click();
  const app = page.locator('main [data-app]').first();
  await app.focus();
  await receive(page, [dark]);
  await expect(app).toBeFocused();
  await remote(page, 'Escape');
  const live = page.getByRole('button', {name:'Live TV', exact:true});
  await live.focus();
  await remote(page, 'Enter');
  const player = page.getByRole('dialog', {name:'Live TV', exact:true});
  await expect(player).toBeVisible();
  const back = player.getByRole('button', {name:'Back from Live TV'});
  await back.focus();
  await receive(page, [tatort, dark]);
  await expect(back).toBeFocused();
  await expect(player.locator('video')).toHaveJSProperty('paused', false);
  await back.click();
  await expect(player).toHaveCount(0);
  await expect(live).toBeFocused();
  await checkHome(page, [tatort, dark]);
  expect(await playbackCalls(page)).toEqual([]);
});

test('saved titles never appear on the senior home', async ({page}) => {
  await startLauncher(page, [dark], 'senior');
  await expect(page.getByRole('button', {name:'Open ZDF library'})).toBeVisible();
  await expect(page.getByRole('button', {name:'Open live TV'})).toBeVisible();
  await expect(page.getByText('Dark', {exact:true})).toHaveCount(0);
  await expect(page.locator('[data-luna-play], ' + listButton)).toHaveCount(0);
  expect(await playbackCalls(page)).toEqual([]);
});

test('personalisation changes the three destinations and keeps a removed watchlist accessible on wake', async ({page}) => {
  await startLauncher(page,[dark]);
  await page.evaluate(() => {
    sessionStorage.setItem('fixture-settings',JSON.stringify({
      tiles:['arte','apps','search'], libraries:['arte'], language:'de', appearance:'rose',
      showClock:false,showWatchlistCount:false,defaultSearchService:'arte'
    }));
    window.NavaHome.settingsChanged();
  });
  await expect(page.locator('[data-dock]')).toHaveText(['ARTE','Apps','Suche']);
  await expect(page.locator('#clock')).toBeHidden();
  await expect(page.locator('body')).toHaveAttribute('data-tv-appearance','rose');
  await page.locator('[data-bottom="settings"]').click();
  const chooser=page.locator('#panel[open]');
  await expect(chooser).toContainText('Einstellungen bitte über den PC regeln oder Quick Settings nutzen.');
  await expect(chooser.getByRole('button')).toHaveText(['Quick Settings','Einstellungen im Luna Browser öffnen']);
  await chooser.locator('[data-tv-settings="luna"]').click();
  expect(await page.evaluate(()=>window.requestedSettings)).toBe(1);
  await remote(page,'Escape');
  await page.locator('[data-dock="search"]').click();
  await expect(page.getByRole('tab')).toHaveText(['Alle','ARTE','YouTube','Netflix']);
  await expect(page.getByRole('tab',{name:'ARTE'})).toHaveAttribute('aria-selected','true');
  await page.locator('[data-bottom="apps"]').click();
  await page.locator(listButton).click();
  await expect(chooser.locator('[data-luna-play="2"]')).toBeFocused();
  await remote(page,'Escape');
  await page.evaluate(()=>window.NavaHome.wake());
  await expect(chooser.locator('[data-luna-play="2"]')).toBeFocused();
  await remote(page,'Enter');
  expect(await playbackCalls(page)).toEqual(['2']);
});
