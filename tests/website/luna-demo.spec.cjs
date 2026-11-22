const { test, expect } = require('@playwright/test');

async function openDemo(page) {
  await page.goto('luna/?lang=de');
  const demo = page.frameLocator('#luna-demo-frame');
  await expect(demo.getByRole('tab', { name: 'Startseite' })).toBeVisible();
  return demo;
}
async function command(demo, text) {
  const address = demo.locator('#demo-address');
  await address.fill(text);
  await address.press('Enter');
}
async function note(demo) {
  await demo.locator('#demo-note').click();
  const editor = demo.locator('.demo-note-text');
  await expect(editor).toBeFocused();
  return editor;
}

test('Luna palettes change the native colors and follow the light/dark setting', async ({
  page,
}) => {
  const demo = await openDemo(page);
  await demo.locator('#demo-address').fill('farben');
  const dialog = demo.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('[data-color]')).toHaveCount(11);
  await dialog.locator('[data-mode="light"]').click();
  const palette = dialog.locator('[data-color="ocean"]');
  await palette.click();
  await expect(palette).toHaveAttribute('aria-pressed', 'true');
  await expect(demo.locator('html')).toHaveAttribute('data-color-theme', 'ocean');
  const light = await demo
    .locator('#demo-page')
    .evaluate((el) => getComputedStyle(el).backgroundColor);
  await dialog.locator('[data-mode="dark"]').click();
  await expect(demo.locator('html')).toHaveAttribute('data-theme', 'dark');
  const dark = await demo
    .locator('#demo-page')
    .evaluate((el) => getComputedStyle(el).backgroundColor);
  expect(dark).not.toBe(light);
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await page.reload();
  await expect(demo.locator('html')).toHaveAttribute('data-color-theme', 'ocean');
  await expect(demo.locator('html')).toHaveAttribute('data-theme', 'dark');
});

test('Luna tabs, ticking flip clock and notes keep the right internal page', async ({ page }) => {
  const demo = await openDemo(page);
  await (await note(demo)).fill('Nur zur Startseite');
  await demo.locator('#demo-panel-close').click();
  await demo.locator('#demo-tab-new').click();
  await expect(demo.getByRole('tab')).toHaveCount(2);
  await command(demo, 'uhr');
  await expect(demo.getByRole('tab', { name: 'Uhr' })).toHaveAttribute('aria-selected', 'true');
  const display = demo.locator('.fm');
  await expect(display.locator('.fm-disk')).toHaveCount(341);
  await expect(display.locator('.fm-date')).not.toHaveText('');
  const before = await display.locator('.fm-screen').innerHTML();
  await expect.poll(() => display.locator('.fm-screen').innerHTML()).not.toEqual(before);
  const clockNote = await note(demo);
  await expect(clockNote).toHaveValue('');
  await clockNote.fill('Zur Uhr');
  await demo.locator('#demo-panel-close').click();
  await demo.getByRole('tab', { name: 'Startseite' }).click();
  await expect(await note(demo)).toHaveValue('Nur zur Startseite');
  await demo.locator('#demo-panel-close').click();
  await demo.getByRole('tab', { name: 'Startseite' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(demo.getByRole('tab', { name: 'Uhr' })).toBeFocused();
  await page.keyboard.press('Delete');
  await expect(demo.getByRole('tab')).toHaveCount(1);
  await page.reload();
  await command(demo, 'time');
  await expect(await note(demo)).toHaveValue('Zur Uhr');
});

test('the original mouse recognizer opens the note for the underlying page', async ({ page }) => {
  const demo = await openDemo(page);
  await (await note(demo)).fill('Kreis auf der Startseite');
  await demo.locator('#demo-panel-close').click();
  await command(demo, 'gesten');
  const pad = demo.locator('.demo-gesture-pad');
  await pad.scrollIntoViewIfNeeded();
  const rect = await pad.boundingBox();
  const radius = Math.min(54, rect.width / 8, rect.height / 7);
  const cx = rect.x + rect.width * 0.24;
  const cy = rect.y + rect.height * 0.4;
  async function drawArc(start, sweep) {
    await page.mouse.move(cx + radius * Math.cos(start), cy + radius * Math.sin(start));
    await page.mouse.down();
    for (let step = 1; step <= 68; step++) {
      const angle = start + (sweep * step) / 68;
      await page.mouse.move(cx + radius * Math.cos(angle), cy + radius * Math.sin(angle));
    }
    await page.mouse.up();
  }
  await drawArc(-Math.PI / 4, -1.5 * Math.PI);
  await expect(demo.locator('.demo-gesture-result')).toHaveText('Erkannt: C');
  await expect(demo.getByRole('dialog')).toBeHidden();
  await drawArc(-Math.PI / 2, -2 * Math.PI);
  await expect(demo.locator('.demo-gesture-result')).toHaveText('Erkannt: O');
  await expect(demo.locator('.demo-note-text')).toHaveValue('Kreis auf der Startseite');
});

test('the parent language switch preserves the open panel, selected palette and note', async ({
  page,
}) => {
  const demo = await openDemo(page);
  await demo.locator('#demo-tab-new').click();
  await command(demo, 'time');
  await demo.locator('#demo-theme').click();
  const dialog = demo.getByRole('dialog');
  const selected = dialog.locator('[data-color="ocean"]');
  await selected.click();
  await page.evaluate(() => window.OpenNavaSite.setLanguage('en'));
  await expect(dialog).toHaveAccessibleName('Color themes');
  await expect(selected).toHaveText(/Ocean/);
  await expect(selected).toHaveAttribute('aria-pressed', 'true');
  await expect(demo.getByRole('tab', { name: 'Clock' })).toHaveAttribute('aria-selected', 'true');
  await demo.locator('#demo-panel-close').click();
  const field = await note(demo);
  await field.fill('Keep this note');
  await page.evaluate(() => window.OpenNavaSite.setLanguage('de'));
  await expect(dialog).toHaveAccessibleName('Notiz');
  await expect(field).toHaveValue('Keep this note');
  await expect(demo.getByRole('tab')).toHaveCount(2);
});

test('new tabs stay inside the strip on a narrow screen while the address keeps focus', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 720 });
  const demo = await openDemo(page);
  const create = demo.locator('#demo-tab-new');
  await create.click();
  const outerScroll = await page.evaluate(() => scrollY);
  for (let i = 0; i < 5; i++) await create.click();
  await expect(demo.getByRole('tab')).toHaveCount(7);
  await expect(demo.locator('#demo-address')).toBeFocused();
  const positions = await demo.locator('#demo-tabs').evaluate((strip) => {
    const current = strip
      .querySelector('[aria-selected="true"]')
      .parentElement.getBoundingClientRect();
    const bounds = strip.getBoundingClientRect();
    return {
      start: current.left - bounds.left,
      end: bounds.right - current.right,
      scroll: strip.scrollLeft,
    };
  });
  expect(positions.scroll).toBeGreaterThan(0);
  expect(positions.start).toBeGreaterThanOrEqual(-1);
  expect(positions.end).toBeGreaterThanOrEqual(-1);
  expect(await page.evaluate(() => scrollY)).toBe(outerScroll);
});

test('entered website addresses expose distinct tabs and explicit external links', async ({
  page,
}) => {
  const demo = await openDemo(page);
  await command(demo, 'example.org/first');
  const first = demo.getByRole('tab', { name: 'example.org' });
  await expect(first).toHaveAttribute('title', 'https://example.org/first');
  await demo.locator('#demo-tab-new').click();
  await command(demo, 'https://example.com/docs');
  await expect(demo.getByRole('tab', { name: 'example.com' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(demo.getByRole('button', { name: 'Tab schließen · example.org' })).toHaveCount(1);
  await first.click();
  const link = demo.getByRole('link', { name: 'Website öffnen' });
  await expect(link).toHaveAttribute('href', 'https://example.org/first');
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(link).toHaveAttribute('rel', /noopener/);
});
