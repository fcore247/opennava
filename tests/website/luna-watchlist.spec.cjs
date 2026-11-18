const {test, expect} = require('@playwright/test');

// Standard Android JavascriptInterface, injected in the same place as the demo device bridge.
async function installWatchlist(page) {
  await page.addInitScript(() => {
    let bridge;
    const rows = () => JSON.parse(sessionStorage.getItem('fixture-luna') || '[]');
    const device = {
      getLunaWatchlist: () => JSON.stringify(rows()),
      openLunaProgramme: id => { window.openedLunaTitle = id; },
      removeLunaProgramme: id => {
        sessionStorage.setItem('fixture-luna', JSON.stringify(rows().filter(item => item.id !== id)));
        return true;
      }
    };
    Object.defineProperty(window, 'NavaTV', {
      get: () => bridge,
      set: value => { bridge = {...value, ...device}; }
    });
  });
}

test('OpenNava starts on a Luna title, opens it with OK and manages the watchlist', async ({page}) => {
  await installWatchlist(page);
  await page.goto('demo/full/index.html');
  await expect(page.locator('main [data-luna-play]')).toHaveCount(0);
  await page.evaluate(() => {
    sessionStorage.setItem('fixture-luna', JSON.stringify([
      {id: '2', title: 'Dark', provider: 'Netflix'}, {id: '1', title: 'Stadt Land Kunst', provider: 'ARTE'}
    ]));
    window.NavaHome.watchlistChanged();
  });
  await expect(page.getByRole('heading', {name: 'Dark', exact: true})).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', {name: 'Open title'})).toBeFocused();
  await page.getByRole('button', {name:'Apps', exact:true}).click();
  await page.evaluate(() => window.NavaHome.wake());
  await expect(page.getByRole('button', {name: 'Open title'})).toBeFocused();
  await page.evaluate(() => window.navaRemote('Enter'));
  await expect.poll(() => page.evaluate(() => window.openedLunaTitle)).toBe('2');
  await page.evaluate(() => window.navaRemote('ArrowRight'));
  const list = page.getByRole('button', {name: 'Luna watchlist (2)'});
  await expect(list).toBeFocused();
  await page.evaluate(() => window.navaRemote('Enter'));
  const panel = page.getByRole('dialog');
  await expect(panel.getByRole('button', {name: 'Netflix Dark'})).toBeFocused();
  await panel.getByRole('button', {name: 'Remove Dark'}).click();
  await expect(panel.getByRole('button', {name: 'ARTE Stadt Land Kunst'})).toBeFocused();
  await panel.getByRole('button', {name: 'Remove Stadt Land Kunst'}).click();
  await expect(panel).toHaveCount(0);
  await expect(page.locator('main [data-luna-play]')).toHaveCount(0);
});

test('the senior profile never surfaces a Luna title', async ({page}) => {
  await installWatchlist(page);
  await page.goto('demo/full/index.html');
  await page.evaluate(() => sessionStorage.setItem('fixture-luna', JSON.stringify([{id:'1', title:'Dark', provider:'Netflix'}])));
  await page.goto('demo/senior/index.html');
  await expect(page.getByRole('button', {name: 'Open ZDF library'})).toBeVisible();
  await expect(page.getByText('Dark', {exact:true})).toHaveCount(0);
  await expect(page.locator('[data-luna-play], [data-home-action="luna-list"]')).toHaveCount(0);
});

test('a freshly saved title takes the focused action, OK opens exactly it and the D-pad reaches every door', async ({page}) => {
  await installWatchlist(page);
  await page.goto('demo/full/index.html');
  await page.evaluate(() => {
    sessionStorage.setItem('fixture-luna', JSON.stringify([{id: '1', title: 'Dark', provider: 'Netflix'}]));
    window.NavaHome.watchlistChanged();
  });
  await page.reload();
  await expect(page.getByRole('button', {name: 'Open title'})).toBeFocused();
  await page.evaluate(() => {
    sessionStorage.setItem('fixture-luna', JSON.stringify([
      {id: '3', title: 'Tatort', provider: 'ARD'}, {id: '1', title: 'Dark', provider: 'Netflix'}
    ]));
    window.NavaHome.watchlistChanged();
  });
  await expect(page.getByRole('heading', {name: 'Tatort', exact: true})).toBeVisible();
  await expect(page.getByRole('button', {name: 'Open title'})).toBeFocused();
  await page.evaluate(() => window.navaRemote('Enter'));
  await expect.poll(() => page.evaluate(() => window.openedLunaTitle)).toBe('3');

  await page.evaluate(() => window.navaRemote('ArrowDown'));
  await expect(page.getByRole('button', {name: /^Live TV/})).toBeFocused();
  await page.evaluate(() => window.navaRemote('ArrowRight'));
  await expect(page.getByRole('button', {name: /^YouTube/})).toBeFocused();
  await page.evaluate(() => window.navaRemote('ArrowUp'));
  await page.evaluate(() => window.navaRemote('ArrowUp'));
  await expect(page.locator('.header .icon-button, .header .nav-button').first()).toBeVisible();
});
