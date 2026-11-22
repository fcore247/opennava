const {test, expect} = require('@playwright/test');

test.use({viewport: {width: 360, height: 780}, isMobile: true, hasTouch: true, locale: 'de-DE'});

const release = 'https://github.com/fcore247/opennava/releases/latest/download/';

async function expectTVDownloads(section) {
  await expect(section.locator('a.home-download-button')).toHaveAttribute('href', release + 'OpenNava-Rust-TV.apk');
  const classic = section.getByRole('link', {name: 'Download the old version', exact: true});
  await expect(classic).toHaveAttribute('href', release + 'OpenNava.apk');
  await expect(classic).toHaveAttribute('lang', 'en');
  await expect(classic).toHaveAttribute('translate', 'no');
}

test('the homepage and TV download page offer native Rust first and the classic version below', async ({page}) => {
  for (const path of ['./', 'download/']) {
    await page.goto(path + '?lang=de');
    const links = page.locator('.home-download-links');
    await expectTVDownloads(links);
    const rust = await links.locator('a.home-download-button').boundingBox();
    const classic = await links.getByRole('link', {name: 'Download the old version'}).boundingBox();
    expect(classic.y).toBeGreaterThanOrEqual(rust.y + rust.height);
  }
});

test('the Android download page offers matching TV builds and switches the sample layout', async ({page, baseURL}) => {
  await page.goto('handy/');
  await expect(page.getByRole('heading', {level: 1})).toHaveText('OpenNava fürs Handy');
  const phone = page.locator('.phone-button');
  await expect(phone).toHaveAttribute('href', release + 'OpenNava-Companion.apk');
  const tv = page.locator('#rust-prototype');
  await expectTVDownloads(tv);
  expect((await tv.boundingBox()).y).toBeGreaterThan((await phone.boundingBox()).y);
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
