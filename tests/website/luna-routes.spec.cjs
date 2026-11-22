const { test, expect } = require('@playwright/test');
const french = require('../../docs/locales/fr.json');
const japanese = require('../../docs/locales/ja.json');

test('both products share one menu and changing the site language updates the embedded Luna demo', async ({ page, baseURL }) => {
  await page.goto('./?lang=fr');
  const primary = page.locator('#site-menu .menu-links');
  await page.locator('.menu-toggle').click();
  await expect(primary.getByRole('link', { name: 'OpenNava', exact: true })).toHaveAttribute('aria-current', 'page');
  await primary.getByRole('link', { name: 'Luna Browser', exact: true }).click();
  await expect(page).toHaveURL(new URL('luna/?lang=fr', baseURL).href);

  const iframe = page.locator('#luna-demo-frame');
  const demo = page.frameLocator('#luna-demo-frame');
  await expect(iframe).toHaveJSProperty('src', new URL('luna/demo/', baseURL).href);
  await expect(iframe).toHaveAttribute('title', french.lunaDemoFrame);
  await expect(demo.getByRole('tab').first()).toBeVisible();
  await page.locator('[data-language-link]').click();
  const wheel = page.getByRole('listbox');
  await wheel.press('End');
  await wheel.press('ArrowUp');
  await expect(wheel.locator('[aria-selected=true]')).toHaveText('日本語');
  await page.locator('#language-done').click();
  await expect(page).toHaveURL(new URL('luna/?lang=ja', baseURL).href);
  await expect(iframe).toHaveAttribute('title', japanese.lunaDemoFrame);
  await expect(demo.locator('html')).toHaveAttribute('lang', 'ja');

  await page.locator('.menu-toggle').click();
  await expect(primary.getByRole('link', { name: 'Luna Browser', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.keyboard.press('Escape');
  await page.locator('footer').getByRole('link', { name: 'OpenNava', exact: true }).click();
  await expect(page).toHaveURL(new URL('?lang=ja', baseURL).href);
  await expect(page.locator('#home-demo')).toBeVisible();
});

test('Luna downloads stay in the Luna section and lead to the matching desktop packages', async ({ page, baseURL }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('./produkte/?lang=fr');
  await page.locator('#luna .download').click();
  const downloadURL = new URL('luna/download/?lang=fr', baseURL).href;
  await expect(page).toHaveURL(downloadURL);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(french.lunaInstallTitle);
  await expect(page.locator('[data-install="mac"]')).toHaveAttribute('href',
    'https://github.com/fcore247/luna-browser/releases/latest/download/Luna-Browser-macos-arm64.dmg');
  await expect(page.locator('[data-install="windows"]')).toHaveAttribute('href',
    /^https:\/\/github\.com\/fcore247\/luna-browser\/releases\/download\/v([\d.]+)\/Luna-Browser-\1-windows-amd64\.zip$/);
  await expect(page.locator('[data-install="windows"] p')).toHaveText(french.lunaWindowsHelp);

  await page.locator('.mast .brand').click();
  await expect(page).toHaveURL(new URL('luna/?lang=fr', baseURL).href);
  await expect(page.locator('.home-download-button')).toHaveJSProperty('href', downloadURL);
  await page.locator('.menu-toggle').click();
  await page.locator('.menu-bottom [data-menu-page="download"]').click();
  await expect(page).toHaveURL(downloadURL);
});
