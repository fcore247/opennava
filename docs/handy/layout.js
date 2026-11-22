(() => {
  const controls = document.querySelector('.layout-controls');
  const sample = document.querySelector('.sample-apps');
  controls.hidden = false;
  controls.addEventListener('click', event => {
    const choice = event.target.closest('button[data-mode]');
    if (!choice) return;
    sample.dataset.layout = choice.dataset.mode;
    for (const button of controls.querySelectorAll('button')) {
      button.setAttribute('aria-pressed', String(button === choice));
    }
  });
})();
