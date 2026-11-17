(() => {
  'use strict';

  const lessons = [
    ['Change channels', 'Use the ring on your remote to change the channel.'],
    ['Pause and resume', 'Use the round OK button to pause and resume.'],
    ['Record a show', 'Open the playback controls and choose the recording duration.'],
    ['Open the TV guide', 'In live TV, the guide shows earlier broadcasts and what comes next.'],
    ['Reminders and catch-up', 'Set reminders for upcoming shows. Open an earlier show when it is available in the media library.']
  ];
  const shows = [
    ['Das Erste', 'Tagesschau', 'tagesschau'],
    ['ZDF', 'Bares für Rares', 'bares'],
    ['ARTE', 'Stadt Land Kunst', 'stadt-land-kunst']
  ];
  const times = ['5 minutes', '30 minutes', 'Until the show ends'];
  let shell, trigger, nav, stage, footer, status, lesson = 0, progress = 0, station = 0, duration = 1;
  let active = false, holdTimer = 0, heldKey = '', holdFired = false;
  const complete = new Set();
  const $ = (selector, element = shell) => element.querySelector(selector);
  function focus(element) { if (element) element.focus({preventScroll:true}); }
  function button(text, action) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = text;
    b.addEventListener('click', action);
    return b;
  }
  function init() {
    if (shell) return;
    shell = document.createElement('section');
    shell.id = 'senior-help';
    shell.hidden = true;
    shell.tabIndex = -1;
    shell.setAttribute('role', 'dialog');
    shell.setAttribute('aria-modal', 'true');
    shell.setAttribute('aria-label', 'Practice with the remote');
    shell.innerHTML = `
      <div class="ih-page">
        <header class="ih-head"><span class="ih-sign"><i aria-hidden="true"></i>Remote control</span>
          <button type="button" class="ih-close"><span aria-hidden="true">×</span> Close</button></header>
        <div class="ih-content">
          <nav class="ih-topics" aria-label="Choose an exercise"></nav>
          <main class="ih-lesson">
            <h1 class="ih-title"></h1>
            <p class="ih-description"></p>
            <button type="button" class="ih-stage" aria-describedby="ih-task">
              <span class="ih-stage-head">Practice TV</span>
              <span class="ih-picture"><span class="ih-network"></span><strong class="ih-programme"></strong><span class="ih-options"></span></span>
              <span class="ih-handset" aria-hidden="true"><span class="ih-ring">
                <i class="ih-up"></i><i class="ih-down"></i><i class="ih-left"></i><i class="ih-right"></i><b>OK</b></span></span>
            </button>
            <p id="ih-task" class="ih-task" role="status" aria-live="polite"></p>
            <div class="ih-actions"></div>
          </main>
        </div>
      </div>`;
    document.body.append(shell);
    stage = $('.ih-stage'); nav = $('.ih-topics'); footer = $('.ih-actions'); status = $('.ih-task');
    $('.ih-close').onclick = close;
    lessons.forEach(([name], i) => {
      const item = button(name, () => choose(i));
      item.setAttribute('aria-label', 'Exercise: ' + name);
      nav.append(item);
    });
    stage.addEventListener('click', event => {
      const option = event.target.closest('[data-duration]');
      if (option) duration = Number(option.dataset.duration);
      if (suppressClick) { suppressClick = false; return; }
      exercise('Click');
    });
    stage.addEventListener('pointerdown', event => {
      if (!event.isPrimary || event.button !== 0) return;
      suppressClick = false;
      const key = holdTarget();
      if (key) { focus(stage); beginHold(key); }
    });
    stage.addEventListener('pointerup', endHold);
    stage.addEventListener('pointercancel', endHold);
    stage.addEventListener('pointerleave', endHold);
    stage.addEventListener('focus', syncGuidePractice);
    stage.addEventListener('blur', () => window.NavaTV?.setGuidePractice?.(false));
    const repeat = button('Practice again', () => choose(lesson));
    repeat.className = 'ih-repeat';
    const next = button('Next exercise', advance);
    next.className = 'ih-next';
    footer.append(repeat, next);
  }
  function clearTimers() {
    endHold();
    suppressClick = false;
  }
  function syncGuidePractice() {
    window.NavaTV?.setGuidePractice?.(active && lesson === 3 && progress === 0 && document.activeElement === stage);
  }
  function choose(i) {
    clearTimers();
    lesson = i; progress = 0; duration = 1; station = 0;
    render();
    focus(stage);
  }
  function advance() { if (lesson === lessons.length - 1) close(); else choose(lesson + 1); }
  function open(button) {
    init();
    if (active) return;
    trigger = button instanceof HTMLElement ? button : document.activeElement;
    active = true;
    window.NavaTV?.setHelpOpen?.(true);
    shell.hidden = false;
    if (window.SeniorLand) window.SeniorLand.paint(shell, window.SeniorLand.read());
    document.documentElement.classList.add('ih-open');
    choose(0);
  }
  function close() {
    if (!active) return;
    active = false;
    window.NavaTV?.setGuidePractice?.(false);
    window.NavaTV?.setHelpOpen?.(false);
    clearTimers();
    document.documentElement.classList.remove('ih-open');
    shell.hidden = true;
    if (trigger && trigger.isConnected) focus(trigger);
  }
  function done(message) {
    complete.add(lesson);
    render(message);
    focus($('.ih-next'));
  }
  const lastStep = [2, 2, 4, 1, 4];
  function render(override) {
    syncGuidePractice();
    const finished = progress === lastStep[lesson];
    $('.ih-title').textContent = lessons[lesson][0];
    $('.ih-description').textContent = lessons[lesson][1];
    Array.from(nav.children).forEach((b, i) => {
      b.setAttribute('aria-current', i === lesson ? 'step' : 'false');
      b.classList.toggle('ih-ticked', complete.has(i));
    });
    $('.ih-next').textContent = lesson === lessons.length - 1 ? 'Finish guide' : 'Next exercise';
    let [network, title, art] = shows[station], cue = '', task = '', options = [], selection = -1;
    if (lesson === 0) {
      cue = progress === 0 ? 'up' : 'down';
      task = progress === 0 ? 'Press Up on the ring to change to the next channel.' : 'Press Down on the ring to switch back.';
    } else if (lesson === 1) {
      cue = 'ok';
      if (progress === 1) network += '  ·  Paused';
      task = progress === 0 ? 'Hold OK until the picture pauses.' : 'Hold OK again to resume playback.';
    } else if (lesson === 2) {
      cue = 'ok';
      if (progress === 0) task = 'Press OK once to open the playback controls.';
      if (progress === 1 || progress === 2) {
        options = ['Pause', 'Record', 'Media library']; selection = progress - 1;
        cue = progress === 1 ? 'right' : 'ok';
        task = progress === 1 ? 'Press Right on the ring to select “Record”.' : 'Press OK to select “Record”.';
      }
      if (progress === 3) {
        options = times; selection = duration;
        cue = 'vertical';
        task = 'Press Up or Down to choose a duration, then press OK.';
      }
      if (finished) {options = ['●  ' + times[duration]]; selection = 0;}
    } else if (lesson === 3) {
      cue = 'up-hold';
      task = 'Hold Up until the TV guide opens.';
      if (finished) { network = 'TV guide'; options = ['Earlier', 'Now', 'Later']; selection = 1; }
    } else {
      cue = 'ok'; network = 'TV guide';
      if (progress < 2) {
        title = 'Tatort'; art = 'tatort';
        options = progress === 0 ? ['Later'] : ['Set reminder']; selection = 0;
        task = progress === 0 ? '“Tatort” is on later. Press OK to open it.' : 'Press OK to set a reminder.';
      } else {
        title = 'Stadt Land Kunst'; art = 'stadt-land-kunst';
        options = progress === 2 ? ['Earlier'] : ['Watch in media library']; selection = 0;
        task = progress === 2 ? 'You have set a reminder for “Tatort”. Press OK to open the earlier show.' : 'Press OK to play the earlier show from the media library.';
        if (finished) { network = 'ARTE  ·  Media library'; options = []; }
      }
    }
    stage.dataset.cue = finished ? '' : cue;
    stage.style.setProperty('--ih-photo', 'url("assets/suche-' + art + '.jpg")');
    $('.ih-network').textContent = network;
    $('.ih-programme').textContent = title;
    const row = $('.ih-options');
    row.replaceChildren();
    options.forEach((text, i) => {
      const option = document.createElement('span');
      option.textContent = text;
      option.className = i === selection ? 'ih-chosen' : '';
      if (lesson === 2 && progress === 3) option.dataset.duration = String(i);
      row.append(option);
    });
    stage.classList.toggle('ih-finished', finished);
    stage.setAttribute('aria-label', 'Practice TV, ' + network + ', ' + title);
    status.classList.toggle('ih-success', finished);
    status.textContent = override || task;
  }
  function exercise(key) {
    if (progress === lastStep[lesson]) return;
    const press = key === 'Enter' || key === 'Click';
    if (lesson === 0 && (key === 'ArrowUp' || key === 'ArrowDown' || key === 'Click')) {
      const up = key === 'ArrowUp' || (key === 'Click' && !progress);
      station = (station + (up ? 1 : -1) + shows.length) % shows.length;
      if (!progress && up) progress = 1;
      else if (progress === 1 && !up) {progress = 2; done('You can now use Up and Down to change the channel.'); return;}
      render();
    } else if (lesson === 1) {
      if (key === 'EnterHold') {
        if (++progress === 2) done('Done. Playback has resumed.');
        else render();
      } else if (press) render('Keep holding OK until the picture ' + (progress ? 'resumes.' : 'pauses.'));
    } else if (lesson === 2) {
      if (progress === 1 && (key === 'ArrowRight' || key === 'Click')) {
        progress = 2;
        render();
      } else if (progress === 3 && (key === 'ArrowUp' || key === 'ArrowDown')) {
        duration = Math.max(0, Math.min(times.length - 1, duration + (key === 'ArrowDown' ? 1 : -1)));
        render();
      } else if (press && progress !== 1) {
        if (++progress === lastStep[lesson]) done('Done. Find your recording under “Recordings” on the home screen.');
        else render();
      }
    } else if (lesson === 3) {
      if (key === 'ArrowUpHold' || key === 'HomeDouble') {
        progress = 1;
        done('The TV guide is open. Press Back to return to the show.');
      } else if (key === 'ArrowUp' || key === 'Click') {
        station = (station + 1) % shows.length;
        render('A short press changes channels. Hold Up a little longer.');
      }
    } else if (lesson === 4 && press) {
      if (++progress === 4) done('Done. You set a reminder and opened an earlier show in the media library.');
      else render();
    }
  }
  let suppressClick = false;
  function holdTarget() {
    if (lesson === 1 && progress < 2) return 'Enter';
    if (lesson === 3 && progress === 0) return 'ArrowUp';
    return '';
  }
  function beginHold(key) {
    if (heldKey) return;
    heldKey = key;
    holdFired = false;
    holdTimer = setTimeout(() => {
      if (active && key === holdTarget() && document.activeElement === stage) {
        holdFired = true;
        suppressClick = true;
        exercise(key + 'Hold');
      }
    }, 550);
  }
  function endHold() {
    clearTimeout(holdTimer);
    heldKey = '';
    holdFired = false;
  }
  function handleKey(key) {
    if (!active) return false;
    const here = document.activeElement;
    const topics = Array.from(nav.children), topic = topics.indexOf(here);
    if (key === 'Escape' || key === 'Back' || key === 'Backspace') {
      if (topic !== -1 || here === $('.ih-close')) close();
      else focus(topics[lesson]);
    } else if (key === 'HomeDouble') {
      if (lesson === 3 && progress === 0) exercise(key);
    } else if (here === stage) {
      if (key === 'ArrowLeft') focus(topics[lesson]);
      else if (key === 'ArrowDown' && lesson !== 0 && !(lesson === 2 && progress === 3)) focus($('.ih-next'));
      else if (key === 'ArrowRight' && !(lesson === 2 && progress === 1)) focus($('.ih-next'));
      else exercise(key);
    } else if (topic !== -1) {
      if (key === 'Enter' || key === 'ArrowRight') choose(topic);
      else if (key === 'ArrowUp') focus(topic ? topics[topic-1] : $('.ih-close'));
      else if (key === 'ArrowDown') focus(topics[Math.min(topic+1, topics.length-1)]);
    } else if (here === $('.ih-close')) {
      if (key === 'Enter') close();
      else if (key.startsWith('Arrow')) focus(topics[lesson]);
    } else if (footer.contains(here)) {
      if (key === 'Enter') here.click();
      else if (key === 'ArrowUp') focus(stage);
      else if (key === 'ArrowLeft') focus(here === $('.ih-next') ? $('.ih-repeat') : topics[lesson]);
      else if (key === 'ArrowRight') focus($('.ih-next'));
    } else focus(stage);
    return true;
  }
  document.addEventListener('keydown', e => {
    if (!active || e.altKey || e.metaKey || e.ctrlKey) return;
    if (e.key === 'Tab') {
      const buttons = Array.from(shell.querySelectorAll('button'));
      let next = (buttons.indexOf(document.activeElement) + (e.shiftKey ? -1 : 1) + buttons.length) % buttons.length;
      focus(buttons[next]);
    } else if (e.key === heldKey || (document.activeElement === stage && e.key === holdTarget())) {
      if (!e.repeat) beginHold(e.key);
    } else if (!e.repeat || e.key.startsWith('Arrow')) handleKey(e.key);
    e.preventDefault();
    e.stopImmediatePropagation();
  }, true);
  document.addEventListener('keyup', e => {
    if (!active || e.key !== heldKey) return;
    if (!holdFired && document.activeElement === stage) exercise(e.key);
    endHold();
    suppressClick = false;
    e.preventDefault();
    e.stopImmediatePropagation();
  }, true);
  document.addEventListener('visibilitychange', () => { if (document.hidden) clearTimers(); });
  window.SeniorHelp = {open, close, isOpen: () => active, handleKey};
})();
