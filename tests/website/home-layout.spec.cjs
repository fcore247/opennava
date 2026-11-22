const { test, expect } = require('@playwright/test');

test('the resized search keeps results clickable and its empty download offer above the keyboard', async ({ page }) => {
  const sizes = [
    { width: 1440, height: 760 },
    { width: 1280, height: 720 },
    { width: 320, height: 568 },
  ];
  await page.setViewportSize(sizes[0]);
  await page.goto('./?lang=en');
  const scene = page.locator('#home-demo');
  const query = scene.locator('#hd-query');
  const keyboard = scene.locator('#hd-keyboard');
  const shelf = scene.locator('#hd-results');
  await scene.locator('#hd-search-open').click();
  await query.pressSequentially('clouds');

  async function resize(size) {
    await page.setViewportSize(size);
    await page.evaluate(async () => {
      await document.fonts.ready;
      // Let responsive layout and the navigation's ResizeObserver settle.
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
  }

  async function expectControlsInViewport() {
    await expect(scene).toBeInViewport({ ratio: 1 });
    await expect(keyboard).toBeInViewport({ ratio: 1 });
    await expect(scene.locator('.hd-nav')).toBeInViewport({ ratio: 1 });
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
  }

  for (const size of sizes) {
    await resize(size);
    await expect(query).toHaveValue('clouds');
    await expectControlsInViewport();

    for (const selector of ['[data-hd-result="alpine"]', '[data-hd-save="alpine"]']) {
      const control = shelf.locator(selector);
      await expect(control).toBeVisible();
      const hit = await control.evaluate(element => {
        const bounds = element.getBoundingClientRect();
        const target = document.elementFromPoint(
          bounds.left + bounds.width / 2,
          bounds.top + bounds.height / 2,
        );
        return bounds.height >= 18 && element.contains(target);
      });
      expect(hit, `${selector} must receive a click at its center at ${size.width}×${size.height}`).toBe(true);
    }
  }

  await scene.locator('#hd-clear').click();
  await query.pressSequentially('qqqq');
  await expect(shelf).toHaveAttribute('data-empty', 'true');
  await expect(scene.locator('.hd-search-download')).toBeHidden();
  await expect(shelf.getByText(/no titles found/i)).toHaveCount(0);
  const offer = shelf.locator('.hd-empty-download');
  const download = offer.getByRole('link', { name: 'Download', exact: true });
  await expect(offer.locator('strong')).toHaveText('Download OpenNava now');
  for (const size of sizes) {
    await resize(size);
    await expect(query).toHaveValue('qqqq');
    const bottom = await offer.evaluate(element => Math.max(
      ...[element, ...element.children].map(node => node.getBoundingClientRect().bottom),
    ));
    for (const control of [scene.locator('#hd-services'), query, keyboard]) {
      const bounds = await control.boundingBox();
      expect(bottom, 'the download offer must finish above every search control').toBeLessThanOrEqual(bounds.y + 1);
    }
    await expect(download).toBeInViewport({ ratio: 1 });
    await expectControlsInViewport();
  }
  await query.press('Enter');
  await expect(download).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/download\/\?lang=en$/);
});
