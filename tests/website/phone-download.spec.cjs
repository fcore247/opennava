const {test, expect} = require('@playwright/test');

test.use({viewport: {width: 360, height: 780}, isMobile: true, hasTouch: true, locale: 'de-DE'});

test('the Android download page offers matching TV builds and switches the sample layout', async ({page, baseURL}) => {
  await page.goto('handy/');
  await expect(page.getByRole('heading', {level: 1})).toHaveText('OpenNava fürs Handy');
  const phone = page.locator('.phone-button');
  const assets = new URL(await phone.getAttribute('href'));
  expect(assets.origin).toBe('https://github.com');
  expect(assets.pathname).toMatch(/^\/fcore247\/opennava\/releases\/download\/[^/]+\/OpenNava-Companion\.apk$/);
  const release = new URL('.', assets);
  const tv = page.locator('#rust-prototype');
  for (const name of ['OpenNava-TV.apk', 'OpenNava-Rust-TV.apk']) {
    await expect(tv.locator('a[href$="/' + name + '"]')).toHaveAttribute('href', new URL(name, release).href);
  }
  await page.getByRole('button', {name: 'Reihe', exact: true}).tap();
  await expect(page.locator('.sample-apps')).toHaveAttribute('data-layout', 'row');
  await page.getByRole('button', {name: 'Raster', exact: true}).tap();
  await expect(page.locator('.sample-apps')).toHaveAttribute('data-layout', 'grid');
  await page.locator('.menu-toggle').tap();
  await expect(page.locator('[data-menu-page=phone]')).toHaveAttribute('aria-current', 'page');
  await page.locator('.menu-close').tap();
  await page.locator('[data-language-link]').tap();
  await page.getByRole('option', {name: 'English', exact: true}).tap();
  await page.locator('#language-done').tap();
  await expect(page).toHaveURL(new URL('handy/?lang=en', baseURL).href);
  await expect(page.getByRole('heading', {level: 1})).toHaveText('OpenNava for Android');
});

test('the old Rust link goes to the prototype download and preserves the language', async ({page, baseURL}) => {
  await page.goto('./?lang=fr#rust-prototype');
  await expect(page).toHaveURL(new URL('handy/?lang=fr#rust-prototype', baseURL).href);
  await expect(page.locator('#rust-prototype a[href$="/OpenNava-Rust-TV.apk"]')).toBeVisible();
});
