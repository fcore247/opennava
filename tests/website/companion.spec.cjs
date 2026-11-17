const { test, expect } = require('@playwright/test');

for (const {profile, viewport} of [
  {profile: 'full', viewport: { width: 1440, height: 960 }},
  {profile: 'senior', viewport: { width: 400, height: 860 }}
]) {
  test.describe(profile + ' launcher', () => {
    test.use({ viewport });

    test('the phone controls the launcher and follows TV changes', async ({ page }) => {
      const phone = page.locator('#virtual-phone');
      const tv = page.frameLocator('#demo-tv');
      const choice = name => phone.locator('[data-choice="' + name + '"]');
      const home = phone.locator('[data-phone="tv-home"]');
      const player = tv.getByRole('dialog');
      const video = player.locator('video');
      const picture = player.locator('header small');
      const onAir = phone.locator('.phone-onair');

      await page.goto('./', { waitUntil: 'domcontentloaded' });
      await page.locator('label').filter({
        has: page.locator('input[name="preview"][value="' + profile + '"]')
      }).click();
      await expect(page.locator('#demo-tv')).toHaveAttribute('src', './demo/' + profile + '/index.html');
      await expect(phone).toHaveAttribute('data-ready', 'true');

      await test.step('tune to ZDF using the phone channel wheel', async () => {
        await choice('live').click();
        await choice('wheel').click();
        const wheel = phone.getByRole('listbox', {name: 'Channel wheel'});
        await wheel.press('Home');
        await wheel.press('ArrowDown');
        await expect(wheel.getByRole('option', {selected: true})).toContainText('ZDF');
        await phone.getByRole('button', {name: 'Watch ZDF', exact: true}).click();
        await expect(picture).toContainText(/CHANNEL 02\s*·\s*ZDF$/);
        await expect(onAir).toContainText('02 · ZDF');
        await expect(choice('slot-previous')).toContainText('Previously');
        await expect(choice('slot-next')).toContainText('Up next');
      });

      await test.step('pause and resume the video with the phone', async () => {
        await expect.poll(() => video.evaluate(v => v.currentTime)).toBeGreaterThan(0);
        await choice('pause').click();
        await expect(player.getByRole('button', {name: 'Play', exact: true})).toBeVisible();
        await expect(video).toHaveJSProperty('paused', true);
        await expect(choice('pause')).toHaveText('Resume');
        await choice('pause').click();
        await expect(video).toHaveJSProperty('paused', false);
        await expect(choice('pause')).toHaveText('Pause');
      });

      await test.step('change channels on the TV while connected and disconnected', async () => {
        const tvPause = player.getByRole('button', {name: 'Pause', exact: true});
        await tvPause.press('ArrowUp');
        await expect(picture).toContainText(/CHANNEL 03\s*·\s*ARTE$/);
        await expect(onAir).toContainText('03 · ARTE');

        await phone.locator('[data-phone="connection"]').click();
        await choice('disconnect').click();
        await tvPause.press('ArrowDown');
        await expect(picture).toContainText(/CHANNEL 02\s*·\s*ZDF$/);
        await home.click();
        await expect(picture).toContainText('ZDF');
        await expect(choice('connect')).toContainText('Connect to the virtual TV');
        await choice('connect').click();
        await expect(onAir).toContainText('02 · ZDF');
      });

      await test.step('open the guide on TV and play the next programme from the phone', async () => {
        await player.getByRole('button', {name: 'TV guide', exact: true}).click();
        await expect(phone.getByRole('heading', {name: 'TV guide', exact: true})).toBeVisible();
        const nextTitle = await choice('slot-next').locator('b').innerText();
        await expect(player.getByRole('button', {name: 'Next ' + nextTitle})).toBeVisible();
        await choice('slot-next').click();
        await expect(player.getByRole('heading', {name: nextTitle, exact: true})).toBeVisible();
        await expect(onAir).toContainText(nextTitle);
      });

      await test.step('search the ZDF library on the phone and open the result on TV', async () => {
        await home.click();
        await choice('libraries').click();
        await choice('zdf').click();
        const searchbox = phone.getByRole('searchbox', {name: 'Search ZDF'});
        await searchbox.click();
        await page.keyboard.type('forest', {delay: 300});
        await expect(searchbox).toBeFocused();
        await expect(searchbox).toHaveValue('forest');
        const query = tv.locator(profile === 'senior' ? '#search-text' : '#tube-query');
        const result = tv.locator(profile === 'senior'
          ? '[data-preview-play="forest"]:visible, [data-play="forest"]:visible'
          : '[data-yt-play="forest"]:visible');
        await expect(query).toHaveText(/forest/i);
        await expect(result).toContainText('Inside the forest');
        await expect(phone.locator('.phone-results button')).toHaveCount(1);
        await choice('forest').click();
        await expect(player.getByRole('heading', {name: 'Inside the forest'})).toBeVisible();
        await expect(onAir).toContainText('Inside the forest');
      });

      await test.step('save and delete the programme from both visible libraries', async () => {
        await choice('save').click();
        await expect(player.getByRole('button', {name: 'Remove from recordings'})).toBeVisible();
        await choice('recordings').click();
        const saved = player.getByRole('button', {name: 'Inside the forest', exact: true});
        await expect(saved).toBeVisible();
        await phone.getByRole('button', {name: 'Delete Inside the forest'}).click();
        await choice('confirm-delete').click();
        await expect(saved).toHaveCount(0);
        await expect(player.getByText('Save a show in the player to find it here.')).toBeVisible();
        await expect(phone.getByText('No saved programmes yet.')).toBeVisible();
      });
    });
  });
}
