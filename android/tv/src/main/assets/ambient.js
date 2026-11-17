(() => {
  'use strict';
  const layers = ['land-a','land-b'].map(id=>document.getElementById(id));
  const timeZone = 'Europe/Berlin';
  const clock = new Intl.DateTimeFormat('en-GB',{timeZone,hourCycle:'h23',hour:'2-digit',minute:'2-digit'});
  const calendar = new Intl.DateTimeFormat('en-GB',{timeZone,weekday:'long',day:'numeric',month:'long'});
  const origin = Date.now();
  let layer = 1, requested = '', lastRefresh = 0, retryAt = 0;
  let timer;
  function show(scene) {
    const key = scene.image + scene.light;
    if (requested === key || Date.now() < retryAt) return;
    requested = key;
    const file = 'assets/landschaften/'+scene.image+'.jpg';
    const picture = new Image();
    picture.onload = () => {
      if (requested !== key) return;
      const next = 1-layer;
      window.SeniorLand.paint(layers[next], scene);
      layers[next].classList.add('visible','motion');
      layers[layer].classList.remove('visible','motion');
      layer = next;
    };
    picture.onerror = () => {
      if (requested !== key) return;
      requested = '';
      retryAt = Date.now()+60000;
      if (!layers[layer].classList.contains('visible')) {
        layers[layer].style.backgroundImage = 'url("assets/starttal.jpg")';
        layers[layer].classList.add('visible');
      }
    };
    picture.src = file;
  }
  function tick() {
    clearTimeout(timer);
    if (document.hidden) return;
    const now = new Date();
    if (now.getTime()-lastRefresh >= 300000 || !lastRefresh) {
      window.SeniorLand.refresh();
      lastRefresh = now.getTime();
    }
    const scene = window.SeniorLand.read(now);
    show(scene);
    const time = document.getElementById('time');
    time.textContent = clock.format(now); time.dateTime = now.toISOString();
    document.getElementById('date').textContent = calendar.format(now);
    const weather = document.getElementById('weather');
    weather.hidden = !scene.caption;
    weather.textContent = scene.caption;
    const offset = Math.floor((now.getTime()-origin)/60000)%4;
    document.querySelector('.almanac').style.transform = 'translate('+[0,1.3,1.3,0][offset]+'vw,'+[0,0,-1.3,-1.3][offset]+'vh)';
    timer = setTimeout(tick, Math.min(5000, 60020-(now.getTime()%60000)));
  }
  document.addEventListener('visibilitychange',tick);
  tick();
})();
