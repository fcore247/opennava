const { test, expect } = require('@playwright/test');

async function checkProfile(page, profile) {
  await expect(page.locator('#demo-tv')).toHaveJSProperty('src', new URL(profile + '/index.html', page.url()).href);
  await expect(page.locator('input[name="preview"][value="' + profile + '"]')).toBeChecked();
  await expect(page.locator('#virtual-phone, [data-phone], a[href="#virtual-phone"]')).toHaveCount(0);
}

async function checkPlaying(player) {
  const video = player.locator('video');
  await expect(video).toBeVisible();
  await expect(video).toHaveJSProperty('paused', false);
  await expect.poll(() => video.evaluate(v => v.currentTime)).toBeGreaterThan(0);
}

test.describe('Full launcher on desktop', () => {
  test.use({ viewport: { width: 1440, height: 960 } });

  test('navigate with a keyboard, play a sample and switch profiles inside the TV', async ({page}) => {
    await page.goto('./demo/?lang=de', {waitUntil: 'load'});
    await page.getByRole('tab', {name: 'OpenNava TV'}).click();
    await checkProfile(page, 'full');
    const tv = page.frameLocator('#demo-tv');
    const home = tv.locator('#nava-home');
    const library = home.getByRole('button', {name:/^Media library\b/i});
    await expect(library).toBeVisible();
    await expect(home.locator('[data-luna-play]')).toHaveCount(0);
    await library.press('Enter');
    await expect(tv.getByRole('heading', {name:'Media library', exact:true})).toBeVisible();

    const sample = tv.getByRole('button', {name: 'Above the clouds', exact: true});
    await sample.press('Enter');
    const player = tv.getByRole('dialog');
    await checkPlaying(player);
    await expect(player.getByRole('heading', {name: 'Above the clouds'})).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(player).toHaveCount(0);
    await expect(sample).toBeVisible();

    await tv.getByRole('button', {name: 'Settings', exact: true}).click();
    await tv.getByRole('button', {name: 'Quick settings', exact: true}).click();
    await tv.getByRole('button', {name: 'Senior view', exact: true}).click();
    await checkProfile(page, 'senior');
    await expect(tv.getByRole('button', {name: 'Open ZDF library'})).toBeVisible();
    await page.getByRole('group', {name: 'TV-Profil wählen'}).getByText('Vollmodus').click();
    await checkProfile(page, 'full');
    await expect(home.getByRole('button', {name:/^Live TV\b/i})).toBeVisible();
  });
});

test.describe('Senior view on a touch screen', () => {
  test.use({ viewport: { width: 393, height: 852 }, isMobile: true, hasTouch: true });

  test('tap the TV in portrait and landscape and return to the full launcher', async ({page}) => {
    await page.goto('./demo/?lang=de', {waitUntil: 'domcontentloaded'});
    await page.evaluate(() => document.fonts.ready);
    await page.getByRole('tab', {name: 'OpenNava TV'}).tap();
    const toggle = page.locator('label').filter({has: page.locator('input[name="preview"][value="senior"]')});
    await toggle.tap();
    await checkProfile(page, 'senior');
    const tv = page.frameLocator('#demo-tv');
    await expect(tv.locator('html')).toHaveAttribute('data-launcher-ready', 'true');
    await tv.getByRole('button', {name: 'Open ZDF library'}).tap();
    await expect(tv.locator('#station-title')).toContainText('ZDF');

    const frame = page.locator('#demo-tv');
    const portraitWidth = (await frame.boundingBox()).width;
    await page.setViewportSize({width: 852, height: 393});
    await expect.poll(async () => (await frame.boundingBox()).width).toBeGreaterThan(portraitWidth);
    // A landscape phone can scroll the outer article to see the TV controls.
    // Chromium does not automatically scroll it for taps in a transformed iframe.
    await frame.evaluate(element => element.scrollIntoView({block: 'center', behavior: 'instant'}));
    const bounds = await frame.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(-1);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(853);

    const watch = tv.getByRole('button', {name: 'Watch Inside the forest', exact: true}).first();
    await watch.tap();
    const player = tv.getByRole('dialog');
    await checkPlaying(player);
    await expect(player.locator('header small')).toContainText('ZDF');
    await player.getByRole('button', {name: 'Pause', exact: true}).tap();
    await expect(player.locator('video')).toHaveJSProperty('paused', true);
    await player.getByRole('button', {name: 'Play', exact: true}).tap();
    await expect(player.locator('video')).toHaveJSProperty('paused', false);
    await player.getByRole('button', {name: 'Back', exact: true}).tap();
    await expect(player).toHaveCount(0);
    await expect(watch).toBeVisible();

    await tv.getByRole('button', {name: 'Back to channel selection'}).tap();
    await tv.getByRole('button', {name: 'Switch view', exact: true}).tap();
    await tv.getByRole('button', {name: 'Open full launcher'}).tap();
    await checkProfile(page, 'full');
    await expect(tv.locator('#nava-home').getByRole('button', {name:/^Live TV\b/i})).toBeVisible();
  });
});

test('the full search uses the senior remote keyboard and keeps the submitted query across service tabs', async ({page}) => {
  await page.goto('demo/full/index.html');
  const key=value=>page.evaluate(button=>window.navaRemote(button),value);
  await page.locator('.nava-home-search').click();
  await expect(page.locator('.full-search input,.full-search textarea,.full-search [contenteditable=true]')).toHaveCount(0);
  const keyboard=page.locator('#tv-keyboard');
  await expect(keyboard.locator('[data-letter="Q"]')).toBeFocused();
  await expect(keyboard.locator('.key-row')).toHaveText(['QWERTYUIOP','ASDFGHJKL','ZXCVBNMÄÖÜß']);
  await key('ArrowDown'); await key('Enter');
  await expect(page.locator('#full-search-text')).toHaveText('A');
  await key('ArrowRight'); await key('Enter');
  await expect(page.locator('#full-search-text')).toHaveText('AS');
  await key('Delete');
  await expect(page.locator('#full-search-text')).toHaveText('A');
  await keyboard.locator('[data-command=mode]').click();
  await expect(keyboard.locator('.key-row').first()).toHaveText('1234567890');
  await keyboard.locator('[data-letter="1"]').click();
  await keyboard.locator('[data-command=space]').click();
  await keyboard.locator('[data-command=delete]').click();
  await expect(page.locator('#full-search-text')).toHaveText('A1');
  await key('Escape');
  await expect(keyboard).toBeHidden();
  await expect(page.locator('#full-search-query')).toBeFocused();
  await key('Enter');
  await expect(keyboard.locator('[data-letter="Q"]')).toBeFocused();
  await page.keyboard.type('forest');
  await keyboard.locator('[data-command=submit]').click();
  await expect(keyboard).toBeHidden();
  await expect(page.getByRole('button',{name:'Inside the forest',exact:true})).toBeVisible();
  await page.getByRole('tab',{name:'ZDF'}).click();
  await expect(page.locator('#full-search-text')).toHaveText('forest');
  await expect(page.locator('#full-results [data-tv-result] small')).toHaveText(['ZDF']);
  await page.getByRole('tab',{name:'Netflix'}).click();
  await expect(page.locator('[data-netflix-search]')).toHaveText(/Netflix.*forest/);
  await page.locator('[data-netflix-search]').click();
  await expect(page.getByRole('link',{name:'Search on Netflix'})).toHaveAttribute('href','https://www.netflix.com/search?q=forest');
});
