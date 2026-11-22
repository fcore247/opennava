const defaults = Object.freeze({ motif: 'waves', motifSpeed: 100 });
export const personal = () => defaults;

// An embedded demo also suspends its canvas when it scrolls out of the parent viewport.
export function watchStartVisibility(host, callback) {
  let onscreen = true;
  const sync = () => callback(onscreen && !document.hidden && !host.closest('[inert],[hidden]'));
  const observer = new IntersectionObserver(([entry]) => {
    onscreen = entry.isIntersecting;
    sync();
  });
  observer.observe(host);
  document.addEventListener('visibilitychange', sync);
  sync();
  return () => {
    observer.disconnect();
    document.removeEventListener('visibilitychange', sync);
  };
}
