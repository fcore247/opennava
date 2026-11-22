// Standalone links use the site's locale loader. The embedded demo follows its parent.
export function languageSite() {
  try {
    if (parent !== window && parent.location.origin === location.origin && parent.OpenNavaSite) {
      return parent.OpenNavaSite;
    }
  } catch {}
  return window.OpenNavaSite;
}
export const getLanguage = () => languageSite()?.language || document.documentElement.lang || 'de';
export const locale = getLanguage;
export const label = (key, values) => languageSite()?.t('lunaDemo' + key, values) || key;
const paletteKeys = { luna: 'rose', ocean: 'ocean', graphite: 'graphite' };
export function paletteName(id) {
  if (id === 'luna') return 'Luna ' + languageSite().t(paletteKeys.luna);
  if (paletteKeys[id]) return languageSite().t(paletteKeys[id]);
  return label(id[0].toUpperCase() + id.slice(1));
}
// The original clock calls t with German source text. Palette labels are replaced
// in the host when mounted, so a language change can also update its open panel.
export const t = (source) => (source === 'Uhrzeit' ? label('Clock') : source);
export const msg = (parts, ...values) =>
  parts.reduce((out, part, i) => out + part + (values[i] ?? ''), '');
