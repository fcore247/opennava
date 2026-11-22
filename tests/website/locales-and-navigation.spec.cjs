const {test, expect} = require('@playwright/test');

test('the full launcher resolves regional languages and translates existing dialogs offline', async ({browser, baseURL}) => {
  const context = await browser.newContext({locale:'nb-NO', baseURL});
  const page = await context.newPage();
  await page.goto('demo/full/index.html');
  await expect(page.locator('html')).toHaveAttribute('lang', 'no');
  const translations = await page.evaluate(() => {
    const i18n = window.NavaI18n;
    return i18n.languages.map(locale => {
      i18n.setLanguage(locale);
      return [i18n.language, i18n.t('openLuna', 'Missing entry'), i18n.t('savedCount', '', {count:7})];
    });
  });
  expect(translations).toHaveLength(20);
  for (const [, label, count] of translations) {
    expect(label).not.toBe('Missing entry');
    expect(count).toContain('7');
  }
  const heading = page.locator('#locale-regression');
  await page.evaluate(() => {
    window.NavaI18n.setLanguage('fr-CA');
    const element = document.createElement('p');
    element.id = 'locale-regression';
    element.textContent = 'Reduce motion';
    document.body.append(element);
  });
  await expect(heading).toHaveText('Réduire les animations');
  await page.evaluate(() => window.NavaI18n.setLanguage('de'));
  await expect(heading).toHaveText('Bewegung reduzieren');
  await page.evaluate(() => window.NavaI18n.setLanguage('auto'));
  await expect(page.locator('html')).toHaveAttribute('lang', 'no');
  await context.close();
});

test('Waterline tabs keep their selected state and remain usable without WebGL', async ({page}) => {
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.addInitScript(() => {
    const getContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(type, ...options) {
      return type.includes('webgl') ? null : getContext.call(this, type, ...options);
    };
  });
  await page.goto('./demo/');
  const tabs = page.locator('.device-tabs[data-liquid-nav]');
  await expect(tabs).toHaveAttribute('data-lq-gl', 'off');
  await expect(tabs).toHaveAttribute('data-lq-motion', 'reduce');
  const buttons = tabs.getByRole('tab');
  await buttons.first().focus();
  await page.keyboard.press('ArrowRight');
  await expect(buttons.nth(1)).toBeFocused();
  await expect(buttons.nth(1)).toHaveAttribute('aria-selected', 'true');
  await expect.poll(async () => {
    const selection = await buttons.nth(1).boundingBox();
    const drop = await tabs.locator('.lq-drop').boundingBox();
    return Math.abs(drop.x + drop.width / 2 - selection.x - selection.width / 2);
  }).toBeLessThan(3);
});
