const {test,expect}=require('@playwright/test');

test('the homepage searches its catalogue with remote keys, filters by service and plays a saved title', async ({page})=>{
  await page.goto('./?lang=de');
  const demo=page.locator('#home-demo');
  const nav=demo.locator('.hd-nav[data-liquid-nav]');
  const query=demo.locator('#hd-query');
  const keys=demo.locator('#hd-keyboard');
  const saved=nav.locator('[data-hd-nav="watchlist"]');
  await expect(demo.locator('[data-hd-view="home"] iframe')).toHaveCount(0);
  expect(await demo.locator('[data-hd-open]').evaluateAll(els=>els.map(el=>el.dataset.hdOpen).sort())).toEqual(['library','live','youtube']);
  expect(await nav.locator('button[data-hd-nav]').evaluateAll(els=>els.map(el=>el.dataset.hdNav))).toEqual(['home','watchlist','apps','settings']);
  await demo.locator('#hd-search-open').press('Enter');
  await expect(demo.locator('[data-hd-view="search"]')).toBeVisible();
  await expect(query).toHaveJSProperty('readOnly',true);
  const regions=await demo.evaluate(el=>['#hd-results','#hd-services','#hd-query','#hd-keyboard'].map(id=>el.querySelector(id).getBoundingClientRect().top));
  expect([...regions].sort((a,b)=>a-b)).toEqual(regions);

  // Remote keycaps and physical typing both edit this demo; shortcuts elsewhere on the site do not.
  await keys.locator('[data-hd-key="Q"]').focus();
  await page.keyboard.press('ArrowRight');
  await expect(keys.locator('[data-hd-key="W"]')).toBeFocused();
  await page.keyboard.press('Enter');
  await keys.locator('[data-hd-command="mode"]').click();
  await keys.locator('[data-hd-key="1"]').press('Enter');
  await expect(query).toHaveValue(/w1/i);
  await keys.locator('[data-hd-command="delete"]').press('Enter');
  await keys.locator('[data-hd-command="mode"]').click();
  await keys.locator('[data-hd-key="O"]').press('Enter');
  await page.keyboard.type('lken');
  await expect(query).toHaveValue(/^wolken$/i);
  const expected=await query.inputValue();
  await page.locator('.menu-toggle').focus();
  await page.keyboard.type('x');
  await expect(query).toHaveValue(expected);

  const result=demo.locator('#hd-results [data-hd-result="alpine"]');
  await demo.locator('[data-hd-service="arte"]').click();
  await expect(result).toHaveCount(0);
  await demo.locator('[data-hd-service="ard"]').click();
  await expect(result).toBeVisible();
  await demo.locator('[data-hd-save="alpine"]').click();
  await result.press('Enter');
  const player=page.locator('#clip-dialog');
  await expect(player).toBeVisible();
  await expect(player.locator('video')).toHaveJSProperty('paused',false);
  await page.keyboard.press('Escape');
  await expect(player).toBeHidden();
  await result.focus();
  await page.keyboard.press('Escape');
  await expect(demo.locator('[data-hd-view="home"]')).toBeVisible();
  await saved.press('Enter');
  await expect(saved).toHaveAttribute('aria-current',/^(page|true)$/);
  await expect(demo.locator('[data-hd-view="watchlist"]')).toBeVisible();
  await expect(demo.locator('#hd-saved-list [data-hd-result="alpine"]')).toBeVisible();
  await demo.locator('[data-hd-view="watchlist"] [data-hd-search]').press('Enter');
  await expect(query).toBeFocused();
  await expect(nav.locator('[data-hd-nav="home"]')).toHaveAttribute('aria-current',/^(page|true)$/);
});

test('a phone opens the live and media collections, an app and quick settings in the homepage demo', async ({browser,baseURL})=>{
  const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,baseURL});
  const page=await context.newPage();
  await page.goto('./?lang=de');
  const demo=page.locator('#home-demo');
  const home=demo.locator('[data-hd-nav="home"]');
  await demo.locator('[data-hd-open="live"]').tap();
  await expect(demo.locator('[data-hd-result]').first()).toBeVisible();
  await home.tap();
  await demo.locator('[data-hd-open="library"]').tap();
  const shelf=demo.locator('#hd-results');
  const cards=shelf.locator('[data-hd-result]');
  await expect(cards.first()).toHaveAttribute('data-hd-result','alpine');
  await cards.first().focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await expect(cards.nth(2)).toBeFocused();
  await expect.poll(()=>cards.nth(2).evaluate(card=>{
    const bounds=card.closest('#hd-results').getBoundingClientRect();
    const item=card.getBoundingClientRect();
    return item.left>=bounds.left-1 && item.right<=bounds.right+1;
  })).toBe(true);
  await demo.locator('[data-hd-nav="apps"]').tap();
  await demo.locator('[data-hd-app="arte"]').tap();
  await expect(demo.locator('[data-hd-result="ocean"]')).toBeVisible();
  await expect(demo.locator('[data-hd-result="alpine"]')).toHaveCount(0);
  await demo.locator('[data-hd-nav="settings"]').tap();
  const toggle=demo.locator('#hd-quick-toggle');
  await toggle.tap();
  await expect(toggle).toHaveAttribute('aria-expanded','true');
  await expect(demo.locator('#hd-quick-panel')).toBeVisible();
  const slider=demo.locator('#hd-dim');
  const shade=()=>demo.evaluate(el=>getComputedStyle(el).getPropertyValue('--hd-shade'));
  const old=await shade();
  await slider.focus();
  await page.keyboard.press('End');
  if (await shade()===old) await page.keyboard.press('Home');
  expect(await shade()).not.toBe(old);
  await toggle.tap();
  await expect(demo.locator('#hd-quick-panel')).toBeHidden();
  await home.tap();
  await expect(demo.locator('#hd-search-open')).toBeVisible();
  const serviceStatus=page.waitForResponse(response=>
    new URL(response.url()).pathname.endsWith('/api/youtube/status') && response.request().method()==='GET');
  await demo.locator('[data-hd-open="youtube"]').tap();
  const youtube=demo.locator('[data-hd-view="youtube"]');
  await expect(youtube.getByRole('heading',{name:'YouTube',exact:true})).toBeVisible();
  const response=await serviceStatus;
  if (response.status()===404 || !response.headers()['content-type']?.includes('application/json')) {
    await expect(youtube.locator('#hd-youtube-root')).toHaveAttribute('data-state','local-only');
    const open=youtube.getByRole('link',{name:'YouTube öffnen'});
    await expect(open).toHaveAttribute('href','https://www.youtube.com/');
    await expect(open).toBeFocused();
  } else {
    expect(response.ok()).toBe(true);
    await expect(youtube.locator('[data-yt-action="start"]')).toBeFocused();
  }
  await expect(home).toHaveAttribute('aria-current',/^(page|true)$/);
  await home.focus();
  await page.keyboard.press('Escape');
  await expect(demo.locator('#hd-search-open')).toBeVisible();
  await context.close();
});
