const { test, expect } = require('@playwright/test');

const companionDownload = 'https://github.com/fcore247/opennava/releases/latest/download/OpenNava-Companion.apk';

async function checkProfile(page, profile) {
  await expect(page.locator('#demo-tv')).toHaveAttribute('src', './demo/' + profile + '/index.html');
  await expect(page.locator('input[name="preview"][value="' + profile + '"]')).toBeChecked();
  await expect(page.locator('#virtual-phone, [data-phone], a[href="#virtual-phone"]')).toHaveCount(0);
  const download = page.getByRole('link', {name: /Download Android app/i});
  await expect(download).toBeVisible();
  await expect(download).toHaveAttribute('href', companionDownload);
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
    await page.goto('./', {waitUntil: 'load'});
    await checkProfile(page, 'full');
    const tv = page.frameLocator('#demo-tv');
    const navigation = tv.getByRole('navigation', {name: 'Main navigation'});
    const forYou = navigation.getByRole('button', {name: 'For you'});
    await forYou.click();
    await forYou.press('ArrowRight');
    const library = navigation.getByRole('button', {name: 'Media library'});
    await expect(library).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(library).toHaveAttribute('aria-current', 'page');

    const sample = tv.getByRole('button', {name: 'Play Above the clouds', exact: true});
    await sample.press('Enter');
    const player = tv.getByRole('dialog');
    await checkPlaying(player);
    await expect(player.getByRole('heading', {name: 'Above the clouds'})).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(player).toHaveCount(0);
    await expect(sample).toBeVisible();

    await tv.getByRole('button', {name: 'Senior view', exact: true}).click();
    await checkProfile(page, 'senior');
    await expect(tv.getByRole('button', {name: 'Open ZDF library'})).toBeVisible();
    await page.getByRole('group', {name: 'Choose the live demo profile'}).getByText('Full launcher').click();
    await checkProfile(page, 'full');
    await expect(navigation.getByRole('button', {name: 'For you'})).toBeVisible();
  });
});

test.describe('Senior view on a touch screen', () => {
  test.use({ viewport: { width: 393, height: 852 }, isMobile: true, hasTouch: true });

  test('tap the TV in portrait and landscape and return to the full launcher', async ({page}) => {
    await page.goto('./', {waitUntil: 'domcontentloaded'});
    const toggle = page.locator('label').filter({has: page.locator('input[name="preview"][value="senior"]')});
    await toggle.tap();
    await checkProfile(page, 'senior');
    const tv = page.frameLocator('#demo-tv');
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
    await expect(tv.getByRole('button', {name: 'For you'})).toBeVisible();
  });
});
