// "Charge the phone": a phone rises from the bottom of the screen, the page blurs behind it, a cable
// plugs in, the battery fills up, the phone restarts with the Opennava logo and leaves again.
window.OpenNavaCharge = (() => {
  'use strict';
  let running = null;

  const markup = `
    <div class="ch-veil"></div>
    <div class="ch-rig">
      <div class="ch-phone">
        <span class="ch-side ch-side-a"></span><span class="ch-side ch-side-b"></span><span class="ch-side ch-side-c"></span>
        <div class="ch-screen">
          <div class="ch-view ch-empty on"><span class="ch-battery"><i></i></span></div>
          <div class="ch-view ch-loading">
            <div class="ch-cell"><div class="ch-fill"><span></span><span></span></div>
              <svg class="ch-bolt" viewBox="0 0 24 24" aria-hidden="true"><path d="M13.5 2 4 13.6h6.2L9.4 22 20 9.8h-6.6Z"/></svg></div>
            <strong class="ch-percent">0%</strong>
            <small class="ch-status"></small>
          </div>
          <div class="ch-view ch-boot"><span class="ch-glow"></span><span class="ch-logo" translate="no">Opennava</span><span class="ch-line"></span></div>
          <span class="ch-camera"></span>
          <span class="ch-gloss"></span>
        </div>
      </div>
      <div class="ch-cable" aria-hidden="true">
        <span class="ch-tip"></span><span class="ch-plug"></span><span class="ch-relief"></span><span class="ch-cord"></span>
      </div>
      <span class="ch-spark" aria-hidden="true"></span>
    </div>`;

  /**
   * host: element inside the demo's shadow root; texts: {label, charging, charged}.
   * restart() is called while the page is hidden behind the blur, so it is fresh when it comes back.
   */
  function play(host, texts, restart) {
    if (running) return running;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      restart();
      return Promise.resolve();
    }
    const dialog = document.createElement('dialog');
    dialog.className = 'ch';
    dialog.setAttribute('aria-label', texts.label);
    dialog.innerHTML = markup;
    host.append(dialog);
    // A modal dialog lies above the whole website, including its header, and keeps the page still.
    dialog.showModal();

    const $ = selector => dialog.querySelector(selector);
    const rig = $('.ch-rig'), veil = $('.ch-veil'), cable = $('.ch-cable'), spark = $('.ch-spark');
    let skipped = false, wake = () => {};
    const wait = ms => new Promise(resolve => {
      if (skipped) return resolve();
      const timer = setTimeout(resolve, ms);
      wake = () => { clearTimeout(timer); resolve(); };
    });
    // Escape skips straight to the end.
    dialog.addEventListener('cancel', event => { event.preventDefault(); skipped = true; wake(); });
    const move = (element, frames, options) => {
      const animation = element.animate(frames, Object.assign({ fill: 'forwards' }, options));
      if (skipped) animation.finish();
      return Promise.race([animation.finished, new Promise(r => setTimeout(r, (options.duration || 0) + 120))]);
    };
    const view = name => {
      for (const v of dialog.querySelectorAll('.ch-view')) v.classList.toggle('on', v.classList.contains('ch-' + name));
    };

    running = (async () => {
      // 1 · the phone pops up from the bottom edge
      await move(rig, [
        { transform: 'translate(-50%, 125vh) rotate(7deg)' },
        { transform: 'translate(-50%, -5.5vh) rotate(-2.2deg)', offset: .62 },
        { transform: 'translate(-50%, 1.6vh) rotate(.9deg)', offset: .82 },
        { transform: 'translate(-50%, 0) rotate(0deg)' }
      ], { duration: 950, easing: 'cubic-bezier(.22, .9, .3, 1)' });
      await wait(160);
      // 2 · only now the website behind it goes out of focus
      veil.classList.add('on');
      await wait(720);
      // 3 · the cable comes from below and clicks in
      cable.classList.add('shown');
      await move(cable, [
        { transform: 'translateY(75vh)' },
        { transform: 'translateY(5.2vh)', offset: .62 },
        { transform: 'translateY(5.2vh)', offset: .76 },
        { transform: 'translateY(0)' }
      ], { duration: 880, easing: 'cubic-bezier(.3, .7, .25, 1)' });
      spark.classList.add('on');
      move(rig, [
        { transform: 'translate(-50%, 0)' }, { transform: 'translate(-50%, -1.5vh)' },
        { transform: 'translate(-50%, .5vh)' }, { transform: 'translate(-50%, 0)' }
      ], { duration: 330, easing: 'ease-out' });
      await wait(300);
      // 4 · charging, quickly
      view('loading');
      dialog.classList.add('powered');
      $('.ch-status').textContent = texts.charging;
      await wait(320);
      const percent = $('.ch-percent'), fill = $('.ch-fill');
      await new Promise(resolve => {
        const started = performance.now(), length = 1900;
        const step = now => {
          const part = skipped ? 1 : Math.min(1, (now - started) / length);
          const level = Math.round(100 * (1 - Math.pow(1 - part, 1.8)));
          percent.textContent = level + '%';
          fill.style.height = level + '%';
          fill.classList.toggle('low', level < 20);
          if (part < 1) requestAnimationFrame(step); else resolve();
        };
        requestAnimationFrame(step);
        // A hidden tab pauses animation frames; the scene must still go on.
        setTimeout(() => { percent.textContent = '100%'; fill.style.height = '100%'; resolve(); }, length + 600);
      });
      $('.ch-status').textContent = texts.charged;
      dialog.classList.add('full');
      await wait(650);
      // 5 · black, then the restart with the logo; meanwhile the page behind starts over
      view('none');
      restart();
      await wait(520);
      view('boot');
      await wait(2250);
      // 6 · unplug, sharpen the page, and the phone leaves the way it came
      move(cable, [{ transform: 'translateY(0)' }, { transform: 'translateY(80vh)' }],
        { duration: 520, easing: 'cubic-bezier(.55, 0, .8, .4)' });
      dialog.classList.remove('powered');
      await wait(330);
      veil.classList.remove('on');
      await wait(200);
      await move(rig, [
        { transform: 'translate(-50%, 0) rotate(0deg)' },
        { transform: 'translate(-50%, -3.4vh) rotate(-1.5deg)', offset: .24 },
        { transform: 'translate(-50%, 125vh) rotate(6deg)' }
      ], { duration: 780, easing: 'cubic-bezier(.5, 0, .75, .2)' });
    })().finally(() => {
      if (skipped) restart();
      dialog.close();
      dialog.remove();
      running = null;
    });
    return running;
  }

  return { play };
})();
