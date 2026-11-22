/* Shared page navigation. URLs stay relative to this file for GitHub Pages project paths. */
(() => {
  'use strict';
  const root = new URL('.', document.currentScript.src);
  const header = document.querySelector('.mast');
  if (!header) return;

  const page = document.body.dataset.page;
  const onLuna = page === 'luna' || page === 'luna-download';
  const downloadPath = onLuna ? 'luna/download/' : 'download/';

  const destinations = [
    {page:'home', path:'', label:'OpenNava'},
    {page:'luna', path:'luna/', label:'Luna Browser'},
    {page:'demo', path:'demo/', key:'demos', label:'Demos'},
    {page:'products', path:'produkte/', key:'products', label:'Produkte'}
  ];
  const toggle = document.createElement('button');
  toggle.className = 'menu-toggle';
  toggle.type = 'button';
  toggle.setAttribute('aria-expanded', 'false');
  toggle.setAttribute('aria-controls', 'site-menu');
  toggle.innerHTML = '<span data-i18n="navMenu">Menü</span><span class="menu-lines" aria-hidden="true"><i></i><i></i></span>';
  header.append(toggle);

  const menu = document.createElement('dialog');
  menu.id = 'site-menu';
  menu.className = 'site-menu';
  menu.setAttribute('aria-label', 'Menü');
  menu.setAttribute('data-i18n-aria-label', 'navMenu');
  menu.innerHTML = '<div class="menu-shell"><div class="menu-top"><a class="brand menu-brand"></a><button class="menu-close" type="button" aria-label="Menü schließen" data-i18n-aria-label="navClose"><span data-i18n="navClose">Menü schließen</span><span class="menu-cross" aria-hidden="true"></span></button></div><div class="menu-body"><nav class="menu-links"></nav><div class="menu-landscape" aria-hidden="true"></div></div><div class="menu-bottom"><a href="https://github.com/fcore247/opennava" target="_blank" rel="noopener">GitHub <span aria-hidden="true">↗</span></a></div></div>';
  const brand = menu.querySelector('.menu-brand');
  const headerBrand = header.querySelector('.brand');
  brand.href = headerBrand?.href || root.href;
  if (headerBrand) {
    brand.innerHTML = headerBrand.innerHTML;
    brand.setAttribute('aria-label', headerBrand.getAttribute('aria-label'));
    brand.dataset.i18nAriaLabel = headerBrand.dataset.i18nAriaLabel;
  }

  const download = document.createElement('a');
  download.dataset.menuPage = 'download';
  download.dataset.i18n = 'downloads';
  download.textContent = 'Downloads';
  download.href = new URL(downloadPath, root).href;
  if (page === 'download' || page === 'luna-download') download.setAttribute('aria-current', 'page');

  const setup = document.createElement('a');
  setup.dataset.menuPage = 'setup';
  setup.dataset.i18n = 'setup';
  setup.textContent = 'Einrichten';
  setup.href = new URL('einrichten/', root).href;
  if (page === 'setup') setup.setAttribute('aria-current', 'page');
  const bottom = menu.querySelector('.menu-bottom');
  if (onLuna) bottom.querySelector('a').href = 'https://github.com/fcore247/luna-browser';
  const phone = document.createElement('a');
  phone.dataset.i18n = 'phoneNav';
  phone.dataset.menuPage = 'phone';
  phone.textContent = 'Handy-App';
  phone.href = new URL('handy/', root).href;
  if (page === 'phone') phone.setAttribute('aria-current', 'page');
  bottom.prepend(setup, download, phone);

  const links = menu.querySelector('.menu-links');
  for (const destination of destinations) {
    const link = document.createElement('a');
    link.href = new URL(destination.path, root).href;
    link.dataset.menuPage = destination.page;
    if (destination.page === page) link.setAttribute('aria-current', 'page');
    const label = document.createElement('span');
    if (destination.key) label.dataset.i18n = destination.key;
    else label.translate = false;
    label.textContent = destination.label;
    const arrow = document.createElement('span');
    arrow.className = 'menu-arrow';
    arrow.setAttribute('aria-hidden', 'true');
    arrow.textContent = '↗';
    link.append(label, arrow);
    links.append(link);
  }
  document.body.append(menu);

  function updateLanguage() {
    const site = window.OpenNavaSite;
    if (!site) return;
    toggle.querySelector('[data-i18n]').textContent = site.t('navMenu');
    for (const label of menu.querySelectorAll('[data-i18n]')) {
      const key = label.dataset.i18n;
      const translated = site.t(key);
      if (translated !== key) label.textContent = translated;
    }
    for (const node of [menu, brand, menu.querySelector('.menu-close')]) {
      const key = node.dataset.i18nAriaLabel;
      const translated = site.t(key);
      if (translated !== key) node.setAttribute('aria-label', translated);
    }
    brand.href = site.pageURL(onLuna ? 'luna/' : '');
    download.href = site.pageURL(downloadPath);
    setup.href = site.pageURL('einrichten/');
    phone.href = site.pageURL('handy/');
    for (const destination of destinations) {
      links.querySelector('[data-menu-page="' + destination.page + '"]').href = site.pageURL(destination.path);
    }
  }

  toggle.addEventListener('click', () => {
    updateLanguage();
    menu.showModal();
    document.body.classList.add('menu-is-open');
    toggle.setAttribute('aria-expanded', 'true');
    (menu.querySelector('[aria-current="page"]') || links.querySelector('a')).focus();
  });
  menu.querySelector('.menu-close').addEventListener('click', () => menu.close());
  menu.addEventListener('close', () => {
    document.body.classList.remove('menu-is-open');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.focus({preventScroll:true});
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted && menu.open) menu.close();
  });
  document.addEventListener('opennava:site-language', updateLanguage);
  window.OpenNavaSite?.ready.then(updateLanguage);
})();
