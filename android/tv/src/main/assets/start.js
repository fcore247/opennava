(() => {
  'use strict';
  const welcome = document.getElementById('welcome');
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let fadeTimer, closeTimer;
  function updateLandscape() {
    if (document.hidden) return;
    window.SeniorLand.refresh();
    window.SeniorLand.paint(document.documentElement,window.SeniorLand.read());
  }
  function onVisible() {
    if (!document.hidden) {
      updateLandscape();
      setTimeout(updateLandscape, 4000);
    }
  }
  onVisible();
  setInterval(updateLandscape,60000);
  document.addEventListener('visibilitychange', onVisible);
  function dismiss() {
    clearTimeout(fadeTimer);
    clearTimeout(closeTimer);
    welcome.hidden = true;
    welcome.classList.remove('is-leaving');
  }
  window.seniorStartup = () => {
    dismiss();
    updateLandscape();
    document.dispatchEvent(new Event('update-greeting'));
    document.getElementById('welcome-greeting').textContent = document.getElementById('greeting').textContent;
    document.getElementById('welcome-date').textContent = document.getElementById('today').textContent;
    welcome.hidden = false;
    fadeTimer = setTimeout(() => {
      if (reducedMotion.matches) { dismiss(); return; }
      welcome.classList.add('is-leaving');
      closeTimer = setTimeout(dismiss, 420);
    }, reducedMotion.matches ? 900 : 2200);
  };
  window.dismissSeniorStartup = () => {
    if (welcome.hidden) return false;
    dismiss();
    return true;
  };
  welcome.addEventListener('click', dismiss);
  document.addEventListener('visibilitychange', () => { if (document.hidden) dismiss(); });
})();
