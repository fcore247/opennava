(() => {
  'use strict';
  const solarClock = new Intl.DateTimeFormat('en-GB', {
    timeZone:'Europe/Berlin', hour:'2-digit', minute:'2-digit', hourCycle:'h23'
  });
  const solarDate = new Intl.DateTimeFormat('sv-SE', {
    timeZone:'Europe/Berlin', year:'numeric', month:'2-digit', day:'2-digit'
  });
  function minuteOfDay(value) {
    const match = /^(\d{2}):(\d{2})$/.exec(value);
    return match ? Number(match[1])*60 + Number(match[2]) : NaN;
  }
  function phaseAt(now, weather) {
    const minute = minuteOfDay(solarClock.format(now));
    const date = solarDate.format(now);
    function sunMinute(value, fallback) {
      const parts = typeof value === 'string' ? value.split('T') : [];
      const time = minuteOfDay((parts[1] || '').slice(0,5));
      return parts[0] === date && Number.isFinite(time) ? time : fallback;
    }
    const sunrise = sunMinute(weather?.sunrise, 7*60);
    const sunset = sunMinute(weather?.sunset, 18*60);
    if (minute < sunrise-45 || minute >= sunset+50) return 'night';
    if (minute < sunrise+100) return 'dawn';
    if (minute >= sunset-85) return 'evening';
    return 'day';
  }
  function conditions(code) {
    if ([71,73,75,77,85,86].includes(code)) return ['snow','Snow'];
    if ([95,96,99].includes(code)) return ['wet','Thunderstorms'];
    if ([51,53,55,56,57].includes(code)) return ['wet','Drizzle'];
    if ([61,63,65,66,67,80,81,82].includes(code)) return ['wet','Rain'];
    if ([45,48].includes(code)) return ['cloud','Fog'];
    if (code === 3) return ['cloud','Overcast'];
    if (code === 2) return ['fair','Partly cloudy'];
    if (code === 1) return ['fair','Mostly clear'];
    if (code === 0) return ['fair','Clear'];
    return ['fair',''];
  }
  function read(now = new Date()) {
    let weather;
    try { weather = JSON.parse(window.SeniorWeather?.cached() || '{}'); } catch { weather = null; }
    const phase = phaseAt(now, weather);
    const age = now.getTime() - Number(weather?.observedAtMs);
    const fresh = weather && Number.isFinite(age) && age >= -900000 && age <= 90*60000 && !weather.stale;
    const [kind, description] = conditions(fresh ? weather.weatherCode : null);
    let image = kind === 'fair' ? phase : kind;
    let light = 'none';
    if (phase === 'night') {
      if (kind === 'wet' || kind === 'cloud') image = 'nightcloud';
      if (kind === 'snow') light = 'brightness(.35) sepia(.12) saturate(.7) hue-rotate(164deg)';
    } else if (phase !== 'day' && kind !== 'fair') {
      light = 'brightness(.79) sepia(.13) saturate(.9)';
    }
    let caption = '';
    if (fresh && description && Number.isFinite(weather.temperatureC)) {
      const temp = new Intl.NumberFormat('en-GB',{maximumFractionDigits:0}).format(Math.round(weather.temperatureC) || 0);
      caption = [weather.location, temp+' °C', description].filter(Boolean).join('  ·  ');
    }
    return {image, light, caption};
  }
  function refresh() {
    try { window.SeniorWeather?.refresh(); } catch { /* Local landscapes also work offline. */ }
  }
  function paint(element, landscape) {
    element.style.setProperty('--landschaft', 'url("assets/landschaften/'+landscape.image+'.jpg")');
    element.style.setProperty('--landschaft-licht', landscape.light);
  }
  window.SeniorLand = {read, refresh, paint};
})();
