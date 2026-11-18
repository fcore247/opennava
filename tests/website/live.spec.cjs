const {test, expect} = require('@playwright/test');
const {broadcasters} = require('./fixtures/live/broadcasters.cjs');

async function openLive(page, profile, touch = false) {
  await page.goto('./', {waitUntil: 'domcontentloaded'});
  if (touch) await page.evaluate(() => document.fonts.ready);
  await page.getByRole('tab', {name: 'OpenNava TV'}).click();
  if (profile === 'senior') {
    const toggle = page.locator('label').filter({has: page.locator('input[name="preview"][value="senior"]')});
    if (touch) await toggle.tap(); else await toggle.click();
  }
  const screen = page.locator('#demo-tv');
  await expect(screen).toHaveAttribute('src', './demo/' + profile + '/index.html');
  await screen.scrollIntoViewIfNeeded();
  const tv = page.frameLocator('#demo-tv');
  await expect(tv.locator('html')).toHaveAttribute('data-launcher-ready', 'true');
  const opener = profile === 'senior' ? tv.getByRole('button', {name: 'Open live TV', exact: true})
    : tv.locator('#nava-home').getByRole('button', {name: /^Live TV/});
  await expect(opener).toBeVisible();
  if (touch) await opener.tap(); else await opener.press('Enter');
  const player = tv.getByRole('dialog', {name: 'Live TV'});
  await expect(player).toBeVisible();
  return {tv, opener, player, video: player.locator('video')};
}

async function frames(video) {
  await expect(video).toHaveJSProperty('paused', false);
  await expect.poll(() => video.evaluate(v => v.videoWidth)).toBeGreaterThan(0);
  await expect.poll(() => video.evaluate(v => v.getVideoPlaybackQuality().totalVideoFrames)).toBeGreaterThan(1);
}

async function hold(page, key, until) {
  await page.keyboard.down(key);
  try { await until(); } finally { await page.keyboard.up(key); }
}

test.describe('Full launcher · live TV with the keyboard', () => {
  test.use({viewport: {width: 1440, height: 960}});

  test('tune, pause, seek within the stream, inspect the guide and select a channel', async ({page}) => {
    await broadcasters(page);
    const {opener, player, video} = await openLive(page, 'full');
    const station = player.locator('.live-meta h1');
    const number = player.locator('.live-number');
    await frames(video);
    await expect(station).toHaveText('Das Erste');
    await expect(number).toHaveText('01');

    await page.keyboard.press('ArrowUp');
    await expect(station).toHaveText('ZDF');
    await expect(number).toHaveText('02');
    await frames(video);
    await page.keyboard.press('ArrowDown');
    await expect(station).toHaveText('Das Erste');
    await frames(video);

    // Start with the controls hidden. A short OK reveals the strip without pausing.
    await expect(player.locator('.live-chrome')).toHaveClass(/is-hidden/, {timeout: 13_000});
    await page.keyboard.press('Enter');
    await expect(player.getByRole('button', {name: 'Pause', exact: true})).toBeFocused();
    await expect(video).toHaveJSProperty('paused', false);
    await hold(page, 'Enter', () => expect(video).toHaveJSProperty('paused', true));
    await expect(video).toHaveJSProperty('paused', true);
    await hold(page, 'Enter', () => expect(video).toHaveJSProperty('paused', false));

    const before = await video.evaluate(v => v.currentTime);
    await hold(page, 'ArrowLeft', () => expect(player.getByRole('status')).toHaveText(/Rewind|Start of available stream/));
    await expect.poll(() => video.evaluate(v => v.currentTime)).toBeLessThan(before);
    const inStream = await video.evaluate(v => v.currentTime >= v.seekable.start(0) - 0.2 && v.currentTime <= v.seekable.end(v.seekable.length - 1) + 0.2);
    expect(inStream).toBe(true);
    await hold(page, 'ArrowRight', () => expect(player.getByRole('status')).toHaveText('Back to live'));
    await expect.poll(() => video.evaluate(v => Math.abs(v.seekable.end(v.seekable.length - 1) - v.currentTime))).toBeLessThanOrEqual(2);

    await hold(page, 'ArrowUp', () => expect(player.getByRole('heading', {name: 'TV guide'})).toBeVisible());
    await expect(player.locator('[data-slot="earlier"]')).toContainText('ARD Early hours');
    await expect(player.locator('[data-slot="previous"]')).toContainText('ARD Morning');
    await expect(player.locator('[data-slot="now"]')).toContainText('ARD Afternoon');
    await expect(player.locator('[data-slot="next"]')).toContainText('ARD Evening');
    await expect(player.locator('[data-slot="later"]')).toContainText('ARD Late night');
    await player.locator('[data-slot="next"]').press('Enter');
    await expect(player.getByRole('status')).toContainText('Reminder saved');
    await page.keyboard.press('Escape');

    await hold(page, 'ArrowDown', () => expect(player.getByRole('heading', {name: 'Channels'})).toBeVisible());
    await expect(player.locator('[data-station="daserste"]')).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(player.locator('[data-station="zdf"]')).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(station).toHaveText('ZDF');
    await frames(video);
    await page.keyboard.press('Escape');
    await expect(player).toHaveCount(0);
    await expect(opener).toBeFocused();
  });
});

test.describe('Senior launcher · live TV by touch', () => {
  test.use({viewport: {width: 932, height: 430}, isMobile: true, hasTouch: true});

  test('choose a station, control playback, save a recording and return to the launcher', async ({page}) => {
    await broadcasters(page);
    const {tv, opener, player, video} = await openLive(page, 'senior', true);
    await frames(video);
    await player.getByRole('button', {name: 'Channels', exact: true}).tap();
    await player.getByRole('button', {name: 'Add ZDF to favourites', exact: true}).tap();
    await player.getByRole('button', {name: 'My channels', exact: true}).tap();
    await expect(player.locator('[data-station]')).toHaveCount(1);
    await player.locator('[data-station="zdf"]').tap();
    await expect(player.locator('.live-meta h1')).toHaveText('ZDF');
    await frames(video);
    await player.getByRole('button', {name: 'Pause', exact: true}).tap();
    await expect(video).toHaveJSProperty('paused', true);
    await player.getByRole('button', {name: 'Play', exact: true}).tap();
    await expect(video).toHaveJSProperty('paused', false);
    await player.getByRole('button', {name: 'TV guide', exact: true}).tap();
    await expect(player.locator('[data-slot="now"]')).toContainText('ZDF Afternoon');
    await player.getByRole('button', {name: 'Back', exact: true}).tap();

    await player.getByRole('button', {name: 'Record', exact: true}).tap();
    await expect(player.getByRole('button', {name: 'Until the programme ends'})).toBeVisible();
    await expect(player.getByRole('button', {name: '60 minutes', exact: true})).toBeVisible();
    await player.getByRole('button', {name: '1 minute', exact: true}).tap();
    const stop = player.getByRole('button', {name: 'Stop recording', exact: true});
    await expect(stop).toBeVisible();
    const recordingStart = await video.evaluate(v => v.getVideoPlaybackQuality().totalVideoFrames);
    await expect.poll(() => video.evaluate(v => v.getVideoPlaybackQuality().totalVideoFrames)).toBeGreaterThan(recordingStart + 16);
    await stop.tap();
    await expect(player.getByRole('status')).toContainText('Saved in Recordings');
    await player.getByRole('button', {name: 'Back from Live TV'}).tap();
    await expect(player).toHaveCount(0);
    await expect(opener).toBeVisible();
    await tv.getByRole('button', {name: /open recordings/i}).tap();
    const archive = tv.getByRole('dialog', {name: 'Recordings'});
    const clip = archive.getByRole('button', {name: /ZDF Afternoon.*Browser recording/});
    await expect(clip).toBeVisible();
    await clip.tap();
    const savedVideo = tv.getByRole('dialog', {name: 'ZDF Afternoon'}).locator('video');
    await expect.poll(() => savedVideo.evaluate(v => v.getVideoPlaybackQuality().totalVideoFrames)).toBeGreaterThan(1);
    expect(await savedVideo.evaluate(v => v.currentSrc)).toMatch(/^blob:/);
    await tv.getByRole('button', {name: 'Back', exact: true}).tap();
    await archive.getByRole('button', {name: 'Delete ZDF Afternoon'}).tap();
    await expect(clip).toHaveCount(0);
  });
});

test('Back hides the expanded controls while paused, then exits on the next press', async ({page}) => {
  await broadcasters(page);
  const {opener, player, video} = await openLive(page, 'full');
  await frames(video);
  await hold(page, 'Enter', () => expect(video).toHaveJSProperty('paused', true));
  await expect(player.locator('.live-chrome')).not.toHaveClass(/is-hidden/);
  await page.keyboard.press('Escape');
  await expect(player.locator('.live-chrome')).toHaveClass(/is-hidden/);
  await expect(video).toHaveJSProperty('paused', true);
  await page.keyboard.press('Escape');
  await expect(player).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test('a browser without stream capture can save a broadcaster replay link', async ({page}) => {
  await page.addInitScript(() => Object.defineProperty(HTMLMediaElement.prototype, 'captureStream', {value: undefined}));
  await broadcasters(page);
  const {tv, player, video} = await openLive(page, 'senior');
  await frames(video);
  await player.getByRole('button', {name: 'Record', exact: true}).click();
  await player.getByRole('button', {name: '1 minute', exact: true}).click();
  await expect(player.getByRole('status')).toContainText('cannot capture this stream');
  await player.getByRole('button', {name: 'Save broadcaster link'}).click();
  await expect(player.getByRole('status')).toContainText('Broadcaster link saved');
  await player.getByRole('button', {name: 'Back from Live TV'}).click();
  await tv.getByRole('button', {name: 'Open recordings'}).click();
  await expect(tv.getByRole('dialog', {name: 'Recordings'}).getByRole('button', {name: /ARD Afternoon.*Open broadcaster website/})).toBeVisible();
});

test('a failed broadcaster stays offline until the visitor explicitly chooses sample footage', async ({page}) => {
  await broadcasters(page, {offline: true});
  const {player, video, opener} = await openLive(page, 'senior');
  await expect(player.getByText('Live stream unavailable', {exact: true})).toBeVisible();
  await expect(video).toHaveJSProperty('paused', true);
  await expect(player.getByRole('button', {name: 'Retry live stream'})).toBeVisible();
  await player.getByRole('button', {name: 'Play sample footage'}).click();
  await frames(video);
  await expect(player.locator('.live-kicker')).toContainText(/sample/i);
  await player.getByRole('button', {name: 'Back from Live TV'}).click();
  await expect(opener).toBeVisible();
});

test('the unmodified public broadcaster delivers decoded live frames', async ({page}) => {
  test.skip(process.env.OPENNAVA_LIVE_SMOKE !== '1', 'Requires an audience region with access to the broadcaster.');
  const {player, video} = await openLive(page, 'senior');
  await frames(video);
  await expect(player.locator('.live-kicker')).not.toContainText(/sample/i);
  const url = await video.evaluate(v => v.currentSrc);
  expect(url).toMatch(/^(blob:|https:\/\/)/);
  expect(url).not.toMatch(/\/demo\/full\/assets\//);
});
