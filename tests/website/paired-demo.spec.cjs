const {test, expect} = require('@playwright/test');

test('mark in Luna, watch on TV, switch profiles, open a Netflix link and remove', async ({page}) => {
  await page.goto('./');
  const luna = page.getByRole('tab', {name: 'Luna Browser'});
  const tv = page.frameLocator('#demo-tv');
  const save = page.locator('#save-film');
  await save.click();
  await expect(save).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', {name: 'Demo-TV starten'}).click();
  await page.locator('#demo-tv').evaluate((frame) => frame.scrollIntoView({block: 'center', behavior: 'instant'}));
  const watchlist = tv.locator('[data-home-action="luna-list"]');
  const home = tv.locator('#nava-home');
  await expect(home).toBeVisible();
  await expect(home.getByText('Über den Wolken', {exact: true})).toHaveCount(0);
  await watchlist.press('Enter');
  await tv.locator('[data-luna-play="alpine"]').press('Enter');
  await expect(tv.getByRole('dialog', {name: 'Über den Wolken'}).locator('video')).toHaveJSProperty('paused', false);
  await page.keyboard.press('Escape');

  await page.getByRole('group', {name: 'TV-Profil wählen'}).getByText('Seniorenmodus').click();
  await expect(tv.getByRole('button', {name: 'Open ZDF library'})).toBeVisible();
  await expect(watchlist).toHaveCount(0);
  await page.getByRole('group', {name: 'TV-Profil wählen'}).getByText('Vollmodus').click();
  await watchlist.click();
  await expect(tv.locator('[data-luna-play="alpine"]')).toBeVisible();
  await luna.click();

  await page.locator('[data-film="netflix"]').click();
  await save.click();
  await page.reload();
  await page.getByRole('tab', {name: 'OpenNava TV'}).click();
  await page.locator('#demo-tv').evaluate((frame) => frame.scrollIntoView({block: 'center', behavior: 'instant'}));
  await expect(home.getByText('Unser Planet', {exact: true})).toHaveCount(0);
  await watchlist.press('Enter');
  const netflix = tv.locator('[data-luna-play="netflix"]');
  await expect(netflix).toBeFocused();
  await netflix.press('Enter');
  await expect(tv.getByRole('link', {name: 'Titelseite bei Netflix öffnen'})).toHaveAttribute('href', 'https://www.netflix.com/de/title/80049832');
  await tv.getByRole('button', {name: 'Back', exact: true}).click();
  await tv.locator('[data-luna-remove="netflix"]').click();
  await luna.click();
  await page.locator('[data-film="netflix"]').click();
  await expect(save).toHaveAttribute('aria-pressed', 'false');
});

test('phone layout, keyboard device tabs and hero clip', async ({page}) => {
  await page.setViewportSize({width: 393, height: 852});
  await page.goto('./');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  const palette = page.getByRole('combobox', {name: 'Luna-Farbthema'});
  await palette.selectOption('ocean');
  await expect(page.locator('.browser-mock')).toHaveAttribute('data-luna-theme', 'ocean');
  await page.reload();
  await expect(palette).toHaveValue('ocean');
  await page.getByRole('button', {name: 'Beispielclip Über den Wolken abspielen'}).click();
  await expect(page.getByRole('dialog').locator('video')).toHaveJSProperty('paused', false);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  const luna = page.getByRole('tab', {name: 'Luna Browser'});
  await luna.focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('tab', {name: 'OpenNava TV'})).toBeFocused();
  await page.keyboard.press('Home');
  await expect(luna).toBeFocused();
  await expect(luna).toHaveAttribute('aria-selected', 'true');
});
