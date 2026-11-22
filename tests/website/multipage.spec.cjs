const {test, expect} = require('@playwright/test');

const paths = ['', 'luna/', 'demo/', 'produkte/'];

function menuLink(page, name) {
  return page.locator('#site-menu').getByRole('link', {name, exact:true});
}

test('keyboard navigation opens real pages and Escape returns focus to the hamburger', async ({page, baseURL}) => {
  await page.goto('./?lang=de');
  const trigger = page.locator('.menu-toggle');
  const dialog = page.locator('#site-menu');
  await expect(trigger).toHaveAttribute('aria-controls','site-menu');
  await expect(trigger).toHaveAttribute('aria-expanded','false');
  await expect(page.locator('#demo-tv, #copy-prompt')).toHaveCount(0);
  await trigger.press('Enter');
  await expect(dialog).toBeVisible();
  await expect(trigger).toHaveAttribute('aria-expanded','true');
  await expect.poll(() => dialog.evaluate(el => el.contains(document.activeElement))).toBe(true);
  const links = dialog.getByRole('navigation').getByRole('link');
  expect(await links.evaluateAll(els => els.map(a => new URL(a.href).pathname))).toEqual(paths.map(path => new URL(path, baseURL).pathname));
  await expect(dialog.locator('a[aria-current="page"]')).toHaveJSProperty('pathname',new URL(baseURL).pathname);
  await menuLink(page,'Produkte').focus();
  await page.keyboard.press('Tab');
  await expect(menuLink(page,'Einrichten')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(trigger).toHaveAttribute('aria-expanded','false');
  await expect(trigger).toBeFocused();

  await trigger.press('Enter');
  await menuLink(page,'Produkte').press('Enter');
  await expect(page).toHaveURL(/\/produkte\/(?:[?#]|$)/);
  const products = page.locator('main');
  for (const [product, path] of [['luna', 'luna/'], ['opennava', '']]) {
    const card = products.locator('#' + product);
    await expect(card.locator('.text-link')).toHaveJSProperty('href', new URL(path + '?lang=de', baseURL).href);
    await expect(card.locator('.download')).toHaveJSProperty('href', new URL(path + 'download/?lang=de', baseURL).href);
  }
  await expect(page.locator('#demo-tv, #copy-prompt')).toHaveCount(0);
  await trigger.press('Enter');
  await expect(dialog.locator('a[aria-current="page"]')).toHaveJSProperty('pathname',new URL('produkte/', baseURL).pathname);
  await menuLink(page,'Einrichten').press('Enter');
  await expect(page).toHaveURL(/\/einrichten\/(?:[?#]|$)/);
  await page.locator('label').filter({has:page.locator('input[name="profile"][value="senior"]')}).click();
  await expect(page.locator('#agent-guide')).toHaveAttribute('href',/#senior-view$/);
  await expect(page.locator('#prompt-text')).toContainText('--profile senior');
  await page.goBack();
  await expect(page).toHaveURL(/\/produkte\/(?:[?#]|$)/);
});

test.describe('mobile navigation', () => {
  test.use({viewport:{width:360,height:780},isMobile:true,hasTouch:true,locale:'en-US'});
  test('tap navigation preserves a Japanese deep link and automatic language restores the device locale', async ({page}) => {
    await page.goto('./einrichten/?lang=ja');
    const trigger=page.locator('.menu-toggle'), dialog=page.locator('#site-menu');
    await expect(page.locator('html')).toHaveAttribute('lang','ja');
    await trigger.tap();
    await expect(dialog).toBeVisible();
    await dialog.locator('.menu-close').tap();
    await expect(dialog).toBeHidden();
    await trigger.tap();
    const demo=dialog.getByRole('link').filter({hasText:'デモ'});
    await demo.tap();
    await expect(page).toHaveURL(/\/demo\/(?:[?#]|$)/);
    await expect(page.locator('html')).toHaveAttribute('lang','ja');
    await page.locator('[data-language-link]').tap();
    await expect(page).toHaveURL(/\/sprache\//);
    const wheel=page.getByRole('listbox');
    await expect(wheel.locator('[aria-selected=true]')).toHaveText('日本語');
    await wheel.press('Home');
    await expect(wheel.locator('[aria-selected=true]')).toHaveAttribute('data-code','auto');
    await page.locator('#language-done').tap();
    await expect(page).toHaveURL(/\/demo\/(?:[?#]|$)/);
    await expect(page.locator('html')).toHaveAttribute('lang','en');
    expect(await page.evaluate(()=>new URL(location.href).searchParams.has('lang'))).toBe(false);
  });
});
