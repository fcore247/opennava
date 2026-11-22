(() => {
  'use strict';

  const wheel = document.getElementById('language-wheel');
  const done = document.getElementById('language-done');
  const site = window.OpenNavaSite;
  if (!wheel || !done || !site) return;

  const root = new URL('../', document.currentScript.src);
  const pagePaths = new Set(
    [
      '',
      'demo/',
      'produkte/',
      'einrichten/',
      'handy/',
      'download/',
      'tvsettings/',
      'luna/',
      'luna/download/',
    ].map((path) => new URL(path, root).pathname),
  );
  const codes = ['auto', ...Object.keys(site.languages)];
  const options = [];
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  let selected = -1;
  let lastApplied = site.preference;
  let finishing = false;
  let destination = null;
  let rowSize = 56;
  let scrollTimer;
  let frame = 0;
  let changing = Promise.resolve();
  let prefix = '';
  let lastKeyTime = 0;

  function returnLink(code) {
    let url = new URL(root);
    const value = new URL(location.href).searchParams.get('return');
    if (value) {
      try {
        const candidate = new URL(value, root);
        const path = candidate.pathname.replace(/index\.html$/, '').replace(/([^/])$/, '$1/');
        if (
          candidate.origin === root.origin &&
          !candidate.username &&
          !candidate.password &&
          pagePaths.has(path)
        ) {
          url = candidate;
          url.pathname = path;
        }
      } catch {}
    }
    if (code === 'auto') url.searchParams.delete('lang');
    else url.searchParams.set('lang', code);
    return url.href;
  }

  // A slow initial dictionary must not change where the already-visible link leads.
  done.href = returnLink(site.preference);
  const clamp = (index) => Math.max(0, Math.min(codes.length - 1, index));
  const centered = () => clamp(Math.round(wheel.scrollTop / rowSize));
  function highlight(index) {
    if (selected === index) return;
    selected = index;
    wheel.setAttribute('aria-activedescendant', options[index].id);
    for (const [i, option] of options.entries()) {
      option.setAttribute('aria-selected', String(i === index));
      option.dataset.distance = Math.min(3, Math.abs(i - index));
    }
  }
  function refreshLabels() {
    const automatic = site.t('auto');
    if (automatic !== 'auto') options[0].firstElementChild.textContent = automatic;
    options[0].lang = site.language;
    const preference = site.preference;
    done.href = returnLink(preference);
    if (preference !== lastApplied && !finishing) {
      lastApplied = preference;
      choose(Math.max(0, codes.indexOf(preference)), false);
    }
  }
  function apply(index) {
    const code = codes[index];
    lastApplied = code;
    done.href = returnLink(code);
    if (site.preference !== code) {
      changing = site.setLanguage(code);
    }
    return changing;
  }

  function settle() {
    if (finishing) return;
    clearTimeout(scrollTimer);
    const index = centered();
    // A delayed scrollend from the previous animation can arrive after another click.
    if (destination !== null && index !== destination) return;
    destination = null;
    highlight(index);
    apply(index);
  }
  function choose(index, animate = true) {
    const next = clamp(index);
    destination = next;
    clearTimeout(scrollTimer);
    const top = next * rowSize;
    if (Math.abs(wheel.scrollTop - top) <= 1) {
      wheel.scrollTo({ top, behavior: 'instant' });
      return settle();
    }
    const smooth = animate && !reducedMotion.matches;
    wheel.scrollTo({ top, behavior: smooth ? 'smooth' : 'instant' });
    if (!smooth) settle();
  }
  function onScroll() {
    if (finishing) return;
    if (!frame) {
      frame = requestAnimationFrame(() => {
        frame = 0;
        highlight(centered());
      });
    }
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(settle, 160);
  }
  function interrupt() {
    destination = null;
    clearTimeout(scrollTimer);
  }
  function fold(text) {
    return text
      .normalize('NFD')
      .replace(/\p{Mark}/gu, '')
      .toLocaleLowerCase();
  }
  function onKey(event) {
    if (event.altKey || event.ctrlKey || event.metaKey || event.isComposing) return;
    const now = destination ?? centered();
    const moves = {
      ArrowDown: now + 1,
      ArrowUp: now - 1,
      Home: 0,
      End: codes.length - 1,
      PageDown: now + 5,
      PageUp: now - 5,
    };
    if (Object.hasOwn(moves, event.key)) {
      event.preventDefault();
      prefix = '';
      choose(moves[event.key]);
      return;
    }
    if (event.key.length !== 1 || event.key === ' ') return;
    const stamp = performance.now();
    prefix = stamp - lastKeyTime < 750 ? prefix + fold(event.key) : fold(event.key);
    lastKeyTime = stamp;
    let index = options.findIndex((option) => fold(option.textContent).startsWith(prefix));
    if (index < 0 && prefix.length > 1) {
      prefix = fold(event.key);
      index = options.findIndex((option) => fold(option.textContent).startsWith(prefix));
    }
    if (index >= 0) {
      event.preventDefault();
      choose(index);
    }
  }

  function initialize() {
    // Language names are available before the dictionary. Accept the first key immediately.
    const rows = document.createDocumentFragment();
    for (const [index, code] of codes.entries()) {
      const option = document.createElement('div');
      option.className = 'language-option';
      option.id = 'language-option-' + code;
      option.dataset.code = code;
      option.setAttribute('role', 'option');
      option.setAttribute('aria-posinset', String(index + 1));
      option.setAttribute('aria-setsize', String(codes.length));
      option.lang = code === 'auto' ? site.language : code;
      const name = document.createElement('span');
      name.className = 'language-option-label';
      name.textContent = code === 'auto' ? 'Automatisch' : site.languages[code];
      option.append(name);
      options.push(option);
      rows.append(option);
    }
    wheel.append(rows);
    wheel.hidden = false;
    rowSize = options[0].offsetHeight || rowSize;
    const index = Math.max(0, codes.indexOf(site.preference));
    wheel.scrollTo({ top: index * rowSize, behavior: 'instant' });
    highlight(index);
    refreshLabels();
    if (document.activeElement === document.body) wheel.focus({ preventScroll: true });

    wheel.addEventListener('scroll', onScroll, { passive: true });
    wheel.addEventListener('scrollend', settle);
    for (const type of ['wheel', 'pointerdown', 'touchstart'])
      wheel.addEventListener(type, interrupt, { passive: true });
    wheel.addEventListener('click', (event) => {
      const option = event.target.closest('[role="option"]');
      if (!option) return;
      wheel.focus({ preventScroll: true });
      choose(codes.indexOf(option.dataset.code));
    });
    wheel.addEventListener('keydown', onKey);
    document.addEventListener('opennava:site-language', refreshLabels);
    new ResizeObserver(() => {
      const nextSize = options[0].offsetHeight;
      if (nextSize && nextSize !== rowSize) {
        rowSize = nextSize;
        choose(destination ?? selected, false);
      }
    }).observe(options[0]);

    done.addEventListener('click', async (event) => {
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0)
        return;
      event.preventDefault();
      if (finishing) return;
      const index = destination ?? centered();
      choose(index, false);
      finishing = true;
      wheel.inert = true;
      done.setAttribute('aria-busy', 'true');
      try {
        await apply(index);
        location.assign(returnLink(codes[index]));
      } catch {
        unlock();
      }
    });
    function unlock() {
      finishing = false;
      wheel.inert = false;
      done.removeAttribute('aria-busy');
    }
    window.addEventListener('pageshow', (event) => {
      if (event.persisted) {
        unlock();
        refreshLabels();
      }
    });
  }
  initialize();
})();
