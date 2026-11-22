(() => {
  const frame = document.getElementById('luna-demo-frame');
  if (!frame) return;
  const syncLanguage = () => window.OpenNavaSite?.ready.then(() => {
    frame.contentWindow?.postMessage({
      type: 'luna-demo:language',
      language: window.OpenNavaSite.language,
    }, location.origin);
  });
  frame.addEventListener('load', syncLanguage);
  document.addEventListener('opennava:site-language', syncLanguage);
  window.addEventListener('message', event => {
    if (event.source === frame.contentWindow && event.origin === location.origin && event.data?.type === 'luna-demo:ready') syncLanguage();
  });
  syncLanguage();
})();
