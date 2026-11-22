const { test, expect } = require('@playwright/test');

async function expectCentered(page, code) {
  const wheel = page.locator('#language-wheel');
  const row = wheel.locator('[aria-selected="true"]');
  await expect(row).toHaveAttribute('data-code', code);
  await expect(wheel).toHaveAttribute('aria-activedescendant', 'language-option-' + code);
  await expect
    .poll(() =>
      row.evaluate((option) => {
        const box = option.getBoundingClientRect();
        const viewport = option.parentElement.getBoundingClientRect();
        return Math.abs(box.top + box.height / 2 - viewport.top - viewport.height / 2);
      }),
    )
    .toBeLessThan(2);
}

test('the language button restores the same route, query and anchor after selection', async ({
  page,
  baseURL,
}) => {
  await page.goto('produkte/?lang=de&view=compact#luna');
  const link = page.locator('footer [data-language-link]');
  await link.click();
  const wheel = page.getByRole('listbox');
  await expect(wheel.getByRole('option')).toHaveCount(21);
  await expectCentered(page, 'de');
  await wheel.getByRole('option', { name: 'English', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expectCentered(page, 'en');
  await wheel.press('f');
  await wheel.press('r');
  await expectCentered(page, 'fr');
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  await page.locator('#language-done').click();
  const target = new URL('produkte/?lang=fr&view=compact#luna', baseURL);
  await expect(page).toHaveURL(target.href);
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
});

test('a physical mouse wheel settles and applies its center row', async ({ page }) => {
  await page.goto('sprache/?lang=de');
  const wheel = page.getByRole('listbox');
  await expectCentered(page, 'de');
  await wheel.hover();
  await page.mouse.wheel(0, 155);
  const selected = wheel.locator('[aria-selected=true]');
  await expect(selected).not.toHaveAttribute('data-code', 'de');
  await expect
    .poll(() =>
      page.evaluate(() => {
        const current = document.querySelector('#language-wheel [aria-selected=true]').dataset.code;
        return current !== 'de' && window.OpenNavaSite.preference === current;
      }),
    )
    .toBe(true);
  await expectCentered(page, await selected.getAttribute('data-code'));
});

test('return links accept only pages on this site', async ({ page, baseURL }) => {
  const home = new URL('?lang=de', baseURL);
  for (const target of ['https://example.org/', '//example.org/luna/', '/unrelated/']) {
    await page.goto('sprache/?lang=de&return=' + encodeURIComponent(target));
    await expectCentered(page, 'de');
    await expect(page.locator('#language-done')).toHaveJSProperty('href', home.href);
  }
});

test.describe('phone language wheel', () => {
  test.use({
    viewport: { width: 360, height: 740 },
    isMobile: true,
    hasTouch: true,
    locale: 'en-US',
  });
  test('a touch swipe changes the choice and a tap on Done returns in that language', async ({
    page,
    context,
  }) => {
    await page.goto('sprache/?lang=de');
    await expectCentered(page, 'de');
    const wheel = page.getByRole('listbox');
    const box = await wheel.boundingBox();
    const x = Math.round(box.x + box.width / 2);
    const y = Math.round(box.y + box.height * 0.73);
    const client = await context.newCDPSession(page);
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    for (let step = 1; step <= 8; step++) {
      await client.send('Input.dispatchTouchEvent', {
        type: 'touchMove',
        touchPoints: [{ x, y: y - 12 * step }],
      });
    }
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await expect.poll(() => page.evaluate(() => window.OpenNavaSite.preference)).not.toBe('de');
    const code = await page.evaluate(() => window.OpenNavaSite.preference);
    await expectCentered(page, code);
    await page.locator('#language-done').tap();
    await expect(page.locator('html')).toHaveAttribute('lang', code);
    await expect(page).not.toHaveURL(/\/sprache\//);
    await client.detach();
  });
});

test('changing language keeps the setup profile chosen after opening the page', async ({
  page,
}) => {
  await page.goto('einrichten/?lang=en');
  await page
    .locator('label')
    .filter({ has: page.locator('input[name="profile"][value="senior"]') })
    .click();
  await page.locator('[data-language-link]').click();
  const wheel = page.getByRole('listbox');
  await wheel.press('ArrowUp');
  await expectCentered(page, 'de');
  await page.locator('#language-done').click();
  await expect(page).toHaveURL(url => url.pathname.endsWith('/einrichten/'));
  const url = new URL(page.url());
  expect(url.searchParams.get('profile')).toBe('senior');
  expect(url.searchParams.get('lang')).toBe('de');
  await expect(page.locator('[name=profile][value=senior]')).toBeChecked();
});

test('Done already leads back to the source page while the first dictionary loads', async ({
  page,
  baseURL,
}) => {
  let resume;
  const ready = new Promise((resolve) => {
    resume = resolve;
  });
  await page.route(
    '**/locales/de.json',
    async (route) => {
      await ready;
      await route.continue().catch(() => {});
    },
    { times: 1 },
  );
  const target = new URL('luna/download/?lang=de', baseURL);
  await page.goto('sprache/?lang=de&return=' + encodeURIComponent(target.pathname));
  await expect(page.locator('#language-done')).toHaveJSProperty('href', target.href);
  try {
    await page.locator('#language-done').click();
    await expect(page).toHaveURL(target.href);
  } finally {
    resume();
  }
});

test('the confirmed choice is retained when a dictionary is still loading', async ({
  page,
  baseURL,
}) => {
  let resume;
  const ready = new Promise((resolve) => {
    resume = resolve;
  });
  await page.route(
    '**/locales/fr.json',
    async (route) => {
      await ready;
      await route.continue();
    },
    { times: 1 },
  );
  await page.goto('sprache/?lang=en');
  const wheel = page.getByRole('listbox');
  await wheel.press('ArrowDown');
  await expectCentered(page, 'fr');
  try {
    await page.locator('#language-done').click();
    await expect(wheel).toHaveJSProperty('inert', true);
    await page.keyboard.press('ArrowUp');
    await expectCentered(page, 'fr');
  } finally {
    resume();
  }
  await expect(page).toHaveURL(new URL('?lang=fr', baseURL).href);
});

test('the wheel accepts its first key while the initial dictionary is in flight', async ({page}) => {
  let release;
  const wait = new Promise(resolve => { release = resolve; });
  await page.route('**/locales/en.json', async route => {
    await wait;
    await route.continue().catch(() => {});
  }, {times: 1});
  try {
    await page.goto('sprache/?lang=en');
    await page.getByRole('listbox').press('ArrowUp');
    await expectCentered(page, 'de');
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
  } finally {
    release();
  }
});

test('keyboard focus reaches the wheel only once its script can handle the first key', async ({page}) => {
  let releaseScript;
  let scriptRequested;
  const waitForScript = new Promise(resolve => { scriptRequested = resolve; });
  const waitForRelease = new Promise(resolve => { releaseScript = resolve; });
  await page.route('**/sprache/language.js*', async route => {
    scriptRequested();
    await waitForRelease;
    await route.continue();
  });
  try {
    await page.goto('sprache/?lang=en', {waitUntil: 'commit'});
    await waitForScript;
    await page.locator('#language-wheel').waitFor({state: 'attached'});
    await expect(page.getByRole('listbox')).toHaveCount(0);
  } finally {
    releaseScript();
  }
  await page.getByRole('listbox').press('ArrowUp');
  await expectCentered(page, 'de');
});

test('the Luna download link keeps the chosen language before translations arrive', async ({page, baseURL}) => {
  let releaseDictionary;
  const wait = new Promise(resolve => { releaseDictionary = resolve; });
  await page.route('**/locales/fr.json', async route => {
    await wait;
    await route.continue().catch(() => {});
  }, {times: 1});
  try {
    await page.goto('produkte/?lang=fr');
    await page.locator('#luna .download').click();
    await expect(page).toHaveURL(new URL('luna/download/?lang=fr', baseURL).href);
  } finally {
    releaseDictionary();
  }
});
