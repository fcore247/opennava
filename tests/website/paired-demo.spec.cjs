const {test, expect} = require('@playwright/test');

test('mark in Luna, watch on TV, switch profiles, open a Netflix link and remove', async ({page}) => {
  await page.goto('./demo/?lang=de');
  await expect(page.locator('html')).toHaveAttribute('data-site-language','de');
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

test('phone demo remembers the theme and supports keyboard device tabs', async ({page}) => {
  await page.setViewportSize({width: 393, height: 852});
  await page.goto('./demo/?lang=de');
  await expect(page.locator('html')).toHaveAttribute('data-site-language','de');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  const palette = page.getByRole('combobox', {name: 'Luna-Farbthema'});
  await palette.selectOption('ocean');
  await expect(page.locator('.browser-mock')).toHaveAttribute('data-luna-theme', 'ocean');
  await page.reload();
  await expect(palette).toHaveValue('ocean');
  const luna = page.getByRole('tab', {name: 'Luna Browser'});
  await luna.focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('tab', {name: 'OpenNava TV'})).toBeFocused();
  await page.keyboard.press('Home');
  await expect(luna).toBeFocused();
  await expect(luna).toHaveAttribute('aria-selected', 'true');
});

test('TV opens its paired Luna settings as a page and applies the three shortcuts', async ({page}) => {
  await page.goto('./demo/?lang=de&profile=full#preview');
  const tv = page.frameLocator('#demo-tv');
  await tv.locator('[data-bottom="settings"]').click();
  await tv.locator('[data-tv-settings="luna"]').click();
  await expect(page).toHaveURL(/\/tvsettings\//);
  await expect(page.getByRole('heading', {name: 'TV-Einstellungen', level: 1})).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.locator('[data-slot="0"]').selectOption('arte');
  await page.locator('#show-clock').uncheck();
  await page.locator('[data-section="appearance"]').click();
  await page.locator('#tv-language').selectOption('fr');
  await page.locator('#tv-appearance').selectOption('rose');
  await page.getByRole('button', {name:'Auf dem Demo-TV ansehen'}).click();
  await expect(tv.locator('[data-dock="arte"]')).toBeVisible();
  const settings = await tv.locator('body').evaluate(() => JSON.parse(window.NavaTV.getLunaSettings()));
  expect(settings.tiles).toEqual(['arte','live','library']);
  expect(settings.language).toBe('fr');
  expect(settings.showClock).toBe(false);
});

test('website detects browser language, remembers a manual choice and falls back to English', async ({browser}) => {
  const page = await browser.newPage({locale:'fr-CA'});
  await page.goto('./');
  await expect(page.locator('html')).toHaveAttribute('data-site-language','fr');
  await expect(page.locator('.hd-nav [data-hd-nav="home"]')).toContainText('Accueil');
  await page.evaluate(() => window.OpenNavaSite.setLanguage('ja'));
  await expect(page.locator('html')).toHaveAttribute('data-site-language','ja');
  await page.goto('./tvsettings/');
  await expect(page.locator('html')).toHaveAttribute('data-site-language','ja');
  await expect(page.locator('#tv-language option')).toHaveCount(21);
  await page.close();
  const fallback = await browser.newPage({locale:'ar-EG'});
  await fallback.goto('./');
  await expect(fallback.locator('html')).toHaveAttribute('data-site-language','en');
  await fallback.close();
});

test('public TV demo searches ARTE, plays its sample and offers a Netflix search link', async ({page}) => {
  await page.goto('./demo/?lang=de&profile=full#preview');
  const tv = page.frameLocator('#demo-tv');
  await tv.locator('[data-bottom="search"]').click();
  const keys = tv.locator('#tv-keyboard');
  for (const letter of 'BLAUE') await keys.locator('[data-letter="' + letter + '"]').press('Enter');
  await keys.locator('[data-command="submit"]').press('Enter');
  await tv.locator('[data-service="arte"]').click();
  await tv.getByRole('button', {name:'Die blaue Stunde', exact:true}).press('Enter');
  await expect(tv.getByRole('dialog').locator('video')).toHaveJSProperty('paused',false);
  await page.keyboard.press('Escape');
  await tv.locator('[data-service="netflix"]').click();
  await tv.locator('#full-search-query').press('Enter');
  for (let i = 0; i < 5; i++) await keys.locator('[data-command="delete"]').press('Enter');
  for (const letter of 'PLANET') await keys.locator('[data-letter="' + letter + '"]').press('Enter');
  await keys.locator('[data-command="submit"]').press('Enter');
  await tv.locator('[data-netflix-search]').press('Enter');
  await expect(tv.getByRole('link', {name:'Auf Netflix suchen'})).toHaveAttribute('href','https://www.netflix.com/search?q=PLANET');
});
