/*
 * Liquid Navbar "Waterline" – dependency-free DOM port for liquid-nav.css
 *
 * Based on Nimbus UI · Liquid Navbar "Waterline" (21st.dev, TSX/React):
 *   "A pill of water. The active drop stretches toward the item you pick, splits into droplets on
 *    long jumps and merges back with a ripple. The cursor lifts a meniscus, every tap sends a wave
 *    along the pill, and each icon plays its own little scene."
 * Original design, WebGL water shader and spring physics by guglielmogiannattasio.it – the
 * shader, the physics constants, the droplet/ripple logic and the CSS drop fallback are kept as
 * in the original. Changes for this port:
 *   - plain DOM instead of React: the product keeps its real buttons, ARIA, focus and listeners;
 *     this module only decorates a group and never handles activation or arrow keys itself
 *   - groups are found by [data-liquid-nav], also when added or removed later
 *   - the active drop follows aria-current="page" / aria-selected / aria-pressed / aria-checked
 *     (or a checked radio); focus is separate and gets its own visible ring (see CSS)
 *   - one shared WebGL context for all groups (blitted into each group's 2D canvas)
 *   - idle is free: ambient water only runs ~3 s after an interaction, then the frame is static
 *   - low-power mode (TV WebViews): 30 fps, smaller canvas, no SVG refraction
 *   - no audio at all; reduced motion via media query, body.reduced or data-liquid-motion
 *
 * Usage:  <link rel="stylesheet" href="liquid-nav.css"><script src="liquid-nav.js" defer></script>
 *         <div data-liquid-nav> <button aria-current="page">…</button> … </div>
 * API:    LiquidNav.scan(root) · LiquidNav.upgrade(el) · LiquidNav.destroy(el) · LiquidNav.refresh(el)
 */
(() => {
  'use strict';
  if (window.LiquidNav) return;

  const PAD = 14; // canvas bleed around the pill, for shadows and ripples (same as the original)
  const SELECTOR = '[data-liquid-nav]';
  const ITEMS = 'button,a[href],[role="tab"],[role="radio"],[role="menuitem"],label';
  const ACTIVE_FOR = 3200; // ms of ambient water after the last interaction
  const NS = 'http://www.w3.org/2000/svg';

  /* ---------- the water shader (original; only the precision header is made WebView-safe) ---------- */
  const VERT = 'attribute vec2 a;void main(){gl_Position=vec4(a,0.,1.);}';
  const FRAG = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;uniform float uDpr;uniform float uTime;uniform vec4 uPill;
uniform vec4 uBlob[6];uniform float uSy[6];uniform vec4 uRip[6];uniform vec3 uMouse;uniform float uDark;uniform float uAmb;
float sdRB(vec2 p,vec2 b,float r){vec2 q=abs(p)-b+r;return length(max(q,0.))+min(max(q.x,q.y),0.)-r;}
float sdCap(vec2 p,vec4 B,float sy){p-=B.xy;p.y/=sy;p.x=max(abs(p.x)-B.z,0.);return(length(p)-B.w)*min(1.,sy);}
float smin(float a,float b,float k){float h=clamp(.5+.5*(b-a)/k,0.,1.);return mix(b,a,h)-k*h*(1.-h);}
float dropD(vec2 p){float d=1e4;for(int i=0;i<6;i++){if(uBlob[i].w>.5){d=smin(d,sdCap(p,uBlob[i],uSy[i]),13.);}}return d;}
float pillD(vec2 p){vec2 h=uPill.zw*.5;return sdRB(p-uPill.xy-h,h,h.y);}
float lens(float d,float w){float t=clamp(-d/w,0.,1.);return sqrt(1.-(1.-t)*(1.-t));}
float waves(vec2 p){float w=0.;
 for(int i=0;i<6;i++){vec4 R=uRip[i];if(R.w>0.){float x=length(p-R.xy)-R.z*240.;w+=R.w*exp(-R.z*1.7)*sin(x*.3)*exp(-x*x/700.);}}
 vec2 m=p-uMouse.xy;w+=uMouse.z*3.6*exp(-dot(m,m)/1000.);
 w+=uAmb*(sin(p.x*.05+uTime*1.2)*.45+sin(p.x*.13-uTime*.85+p.y*.2)*.25);return w;}
float H(vec2 p){float dp=pillD(p);return lens(dp,uPill.w*.36)*2.4+lens(dropD(p),11.)*7.5+waves(p)*smoothstep(0.,7.,-dp);}
float caus(vec2 p,float t){vec2 q=p;float c=0.;for(int i=0;i<3;i++){q+=vec2(sin(q.y*1.1+t),cos(q.x*1.3-t*.7))*.6;c+=1./(1.+16.*abs(sin(q.x)*sin(q.y)));}return c/3.;}
void main(){
 vec2 p=vec2(gl_FragCoord.x,uRes.y*uDpr-gl_FragCoord.y)/uDpr;
 float dp=pillD(p);float dd=dropD(p);
 float h0=H(p);vec3 n=normalize(vec3(h0-H(p+vec2(1.,0.)),h0-H(p+vec2(0.,1.)),1.));
 vec3 V=vec3(0.,0.,1.);
 float spec=pow(max(dot(reflect(-normalize(vec3(-.45,-.8,.85)),n),V),0.),70.);
 float spec2=pow(max(dot(reflect(-normalize(vec3(.6,.75,.7)),n),V),0.),22.)*.3;
 float fres=clamp((1.-n.z)*4.,0.,1.);
 vec3 irid=.5+.5*cos(6.2831*(vec3(0.,.33,.67)+fres*.85+p.x*.0028+uTime*.035));
 float pm=smoothstep(1.,-1.,dp);float dm=smoothstep(1.,-1.,dd)*smoothstep(1.,-2.,dp);
 float sh=smoothstep(9.,-9.,dropD(p-vec2(0.,5.)))*(1.-dm)*pm*(.11+.07*uDark);
 vec3 c=vec3(0.,.14,.36)*sh;float a=sh;
 float pa=clamp(pm*(fres*.5+spec*.9+spec2),0.,1.);
 c=mix(vec3(1.),irid,.5*fres)*pa+c*(1.-pa);a=pa+a*(1.-pa);
 float th=lens(dd,11.);
 float cs=caus((p+n.xy*th*18.)*.085,uTime*.9);
 vec3 tint=mix(vec3(.80,.95,1.),vec3(.55,.82,1.),uDark);
 float rim=dm*(1.-smoothstep(0.,8.,-dd));
 float cres=dm*smoothstep(.12,.55,dot(n.xy,normalize(vec2(.55,.85))))*smoothstep(0.,3.,-dd);
 float body=.5+.5*clamp(-n.y*3.,-1.,1.);
 float da=dm*(.26+.14*body)*(1.-.35*uDark);vec3 dc=mix(tint,vec3(1.),body*.6);
 float la=rim*.22;vec3 lc=mix(vec3(0.,.42,.78),vec3(.1,.55,.95),uDark);dc=lc*la+dc*da*(1.-la);da=la+da*(1.-la);
 la=clamp(cres*.7+smoothstep(.6,1.,cs)*.28*th*dm,0.,1.);dc=vec3(1.)*la+dc*(1.-la);da=la+da*(1.-la);
 la=clamp(dm*(spec*1.2+spec2*.5),0.,1.);dc=vec3(1.)*la+dc*(1.-la);da=la+da*(1.-la);
 la=dm*smoothstep(-2.,0.,dd)*.4;dc=mix(vec3(1.),irid,.35)*la+dc*(1.-la);da=la+da*(1.-la);
 c=dc+c*(1.-da);a=da+a*(1.-da);
 gl_FragColor=vec4(c,a);
}`;

  /* ---------- environment ---------- */
  const ua = navigator.userAgent || '';
  // TV sticks and WebViews: cheaper rendering unless a group asks for data-liquid-quality="high".
  const LOW_POWER = /; wv\)|Android|AFT[A-Z]|Fire ?TV|SmartTV|Tizen|Web0S/i.test(ua) || !!window.NavaTV || (navigator.hardwareConcurrency || 8) <= 4;
  const CHROME_DESKTOP = /Chrome\/|Chromium\/|Edg\//.test(ua) && !/Firefox\/|; wv\)|Android/.test(ua);
  const motionQuery = window.matchMedia ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  let inputMode = 'key'; // D-pad and keyboard are the default; a pointer hides the focus ring until the next key
  const instances = new Map();

  /* ---------- one shared WebGL context for every group ---------- */
  let renderer; // undefined = not tried, null = unavailable
  function getRenderer() {
    if (renderer !== undefined) return renderer;
    renderer = null;
    try {
      const cv = document.createElement('canvas');
      const gl = cv.getContext('webgl', {premultipliedAlpha: true, alpha: true, antialias: false});
      if (!gl) return null;
      const compile = (type, src) => {
        const shader = gl.createShader(type);
        gl.shaderSource(shader, src);
        gl.compileShader(shader);
        return gl.getShaderParameter(shader, gl.COMPILE_STATUS) ? shader : null;
      };
      const vs = compile(gl.VERTEX_SHADER, VERT), fs = compile(gl.FRAGMENT_SHADER, FRAG);
      if (!vs || !fs) return null;
      const program = gl.createProgram();
      gl.attachShader(program, vs);
      gl.attachShader(program, fs);
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return null;
      gl.useProgram(program);
      gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
      const loc = gl.getAttribLocation(program, 'a');
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      const u = {};
      for (const name of ['uRes', 'uDpr', 'uTime', 'uPill', 'uBlob', 'uSy', 'uRip', 'uMouse', 'uDark', 'uAmb']) u[name] = gl.getUniformLocation(program, name);
      cv.addEventListener('webglcontextlost', event => {
        event.preventDefault();
        renderer = null;
        instances.forEach(instance => instance.noGL());
      });
      renderer = {cv, gl, u};
    } catch { renderer = null; }
    return renderer;
  }

  let refractionReady = false;
  function ensureRefractionFilter() {
    if (refractionReady || document.getElementById('liquid-nav-refract')) { refractionReady = true; return; }
    refractionReady = true;
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('width', '0'); svg.setAttribute('height', '0');
    svg.setAttribute('aria-hidden', 'true');
    svg.style.cssText = 'position:absolute;width:0;height:0;pointer-events:none';
    svg.innerHTML = '<filter id="liquid-nav-refract" x="0" y="0" width="100%" height="100%" color-interpolation-filters="sRGB">' +
      '<feTurbulence type="fractalNoise" baseFrequency="0.006 0.02" numOctaves="2" seed="7" result="noise"/>' +
      '<feGaussianBlur in="noise" stdDeviation="2" result="soft"/>' +
      '<feDisplacementMap in="SourceGraphic" in2="soft" scale="60" xChannelSelector="R" yChannelSelector="G"/></filter>';
    document.body.append(svg);
  }

  /* ---------- helpers ---------- */
  const isActive = el => {
    const attr = name => el.getAttribute(name);
    if (attr('aria-current') === 'page' || attr('aria-current') === 'true' || attr('aria-selected') === 'true' || attr('aria-pressed') === 'true' || attr('aria-checked') === 'true') return true;
    const radio = el.tagName === 'LABEL' ? el.querySelector('input[type="radio"],input[type="checkbox"]') : null;
    return !!radio?.checked;
  };
  function itemsOf(nav) {
    const list = [...nav.querySelectorAll(ITEMS)].filter(el => !el.classList.contains('lq-fx') &&
      (el.tagName !== 'LABEL' || el.querySelector('input[type="radio"],input[type="checkbox"]')));
    return list.filter(el => !list.some(other => other !== el && other.contains(el)));
  }
  const isLayer = node => node.nodeType === 1 && (node.classList.contains('lq-layer') || node.classList.contains('lq-fx'));

  /* ---------- one group ---------- */
  function create(nav) {
    const s = {
      a: {x: 0, v: 0}, b: {x: 0, v: 0}, sy: {s: 1, v: 0}, lead: 0, lagFrom: 0,
      sats: [], rips: [], t: {x: 0, cy: 0, q: 0, r: 21}, q: 0,
      mouse: {x: 0, y: 0, s: 0, on: false, t: 0},
      moving: false, split: false, ready: false, has: false, w: 0, h: 0,
      visible: true, reduce: false, raf: 0, last: 0, lastDraw: 0, time: 0, amb: 0, awakeUntil: 0, dirty: false, gl: true
    };
    const opt = name => nav.dataset['liquid' + name];
    const low = () => opt('Quality') === 'low' || (opt('Quality') !== 'high' && LOW_POWER);

    nav.classList.add('liquid-nav');
    if (getComputedStyle(nav).position === 'static') nav.style.position = 'relative';
    const glass = Object.assign(document.createElement('span'), {className: 'lq-layer lq-glass'});
    const canvas = Object.assign(document.createElement('canvas'), {className: 'lq-layer lq-water'});
    const drop = Object.assign(document.createElement('span'), {className: 'lq-layer lq-drop'});
    [glass, canvas, drop].forEach(layer => layer.setAttribute('aria-hidden', 'true'));
    const ctx2d = canvas.getContext('2d');
    const layers = () => { if (!nav.contains(glass) || glass.parentNode !== nav) nav.prepend(glass, canvas, drop); };

    function motion() {
      s.reduce = !!motionQuery?.matches || opt('Motion') === 'reduce' || !!document.body?.classList.contains('reduced');
      nav.dataset.lqMotion = s.reduce ? 'reduce' : 'full';
    }
    function setup() {
      layers();
      if (!nav.dataset.lqInput) nav.dataset.lqInput = inputMode;
      const wantsRefraction = opt('Refract') === 'on' || (opt('Refract') !== 'off' && CHROME_DESKTOP && !low());
      if (wantsRefraction) ensureRefractionFilter();
      nav.dataset.lqRefract = wantsRefraction ? 'on' : 'off';
      nav.dataset.lqGl = s.gl && getRenderer() ? 'on' : 'off';
      s.gl = nav.dataset.lqGl === 'on';
    }

    /* ---- drawing ---- */
    function draw() {
      const T = s.t;
      const sep = Math.abs(s.a.x - s.b.x);
      const thin = Math.min(sep / (2 * (s.q + T.r) || 1), 1);
      const r = T.r * (1 - 0.3 * thin);
      const R = s.gl && getRenderer();
      if (R) {
        const {gl, u, cv} = R;
        const maxDpr = low() ? 1.5 : 2;
        const dpr = Math.min(window.devicePixelRatio || 1, maxDpr);
        const W = s.w + PAD * 2, Hh = s.h + PAD * 2;
        const pw = Math.round(W * dpr), ph = Math.round(Hh * dpr);
        if (canvas.width !== pw || canvas.height !== ph) {
          canvas.width = pw; canvas.height = ph;
          canvas.style.width = W + 'px'; canvas.style.height = Hh + 'px';
        }
        if (cv.width < pw) cv.width = pw;
        if (cv.height < ph) cv.height = ph;
        gl.viewport(0, 0, pw, ph);
        gl.enable(gl.SCISSOR_TEST);
        gl.scissor(0, 0, pw, ph);
        gl.uniform2f(u.uRes, W, Hh);
        gl.uniform1f(u.uDpr, pw / W);
        gl.uniform1f(u.uTime, s.time);
        gl.uniform4f(u.uPill, PAD, PAD, s.w, s.h);
        const blobs = new Float32Array(24);
        const sy = new Float32Array(6).fill(1);
        if (s.has) {
          const head = s.lead === 0 ? s.a : s.b, tail = s.lead === 0 ? s.b : s.a;
          blobs.set([head.x, T.cy, s.q * (1 - 0.25 * thin), T.r * (1 - 0.12 * thin), tail.x, T.cy, s.q * (1 - 0.8 * thin), T.r * (1 - 0.5 * thin)], 0);
          sy[0] = sy[1] = s.sy.s;
          s.sats.slice(0, 4).forEach((d, i) => blobs.set([d.x, d.y, 0, d.r], 8 + i * 4));
        }
        gl.uniform4fv(u.uBlob, blobs);
        gl.uniform1fv(u.uSy, sy);
        const rips = new Float32Array(24);
        s.rips.slice(-6).forEach((p, i) => rips.set([p.x, p.y, p.age, p.amp], i * 4));
        gl.uniform4fv(u.uRip, rips);
        gl.uniform3f(u.uMouse, s.mouse.x, s.mouse.y, s.mouse.s);
        gl.uniform1f(u.uDark, nav.dataset.liquidTone === 'light' ? 0 : 1);
        gl.uniform1f(u.uAmb, s.amb);
        gl.clearColor(0, 0, 0, 0);
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        ctx2d.clearRect(0, 0, pw, ph);
        ctx2d.drawImage(cv, 0, cv.height - ph, pw, ph, 0, 0, pw, ph); // GL origin is bottom-left
      } else if (s.has) {
        const left = Math.min(s.a.x, s.b.x) - s.q - r - PAD;
        const width = sep + 2 * (s.q + r);
        drop.style.width = width + 'px';
        drop.style.transform = `translateX(${left}px) scaleY(${s.sy.s})`;
      }
    }

    /* ---- physics (original constants) ---- */
    function step(dt, now) {
      const T = s.t;
      s.time += dt;
      s.q += (T.q - s.q) * Math.min(1, dt * 12);
      const bodies = [s.a, s.b];
      let still = true;
      if (s.reduce || !s.has) {
        s.a.x = s.b.x = T.x; s.a.v = s.b.v = 0; s.sy.s = 1; s.sats = [];
      } else {
        bodies.forEach((B, i) => {
          const lead = i === s.lead;
          const k = lead ? 210 : 72;
          const c = lead ? 25 : 13;
          const f = !lead && now < s.lagFrom ? 0.08 : 1;
          B.v += (k * (T.x - B.x) - c * B.v) * dt * f;
          B.x += B.v * dt;
          const lo = PAD + 6 + s.q + T.r - 3, hi = PAD + s.w - 6 - s.q - T.r + 3;
          if (B.x < lo || B.x > hi) {
            s.sy.v -= Math.min(Math.abs(B.v), 900) * 0.0016;
            B.x = Math.max(lo, Math.min(hi, B.x));
            B.v *= -0.25;
          }
          if (Math.abs(T.x - B.x) > 0.3 || Math.abs(B.v) > 3) still = false;
        });
        const L = bodies[s.lead];
        const syT = 1 - 0.2 * Math.min(Math.abs(L.v) / 1500, 1);
        s.sy.v += (280 * (syT - s.sy.s) - 9 * s.sy.v) * dt;
        s.sy.s += s.sy.v * dt;
        if (Math.abs(s.sy.s - 1) > 0.002 || Math.abs(s.sy.v) > 0.02) still = false;
        // break the neck: spawn droplets between head and tail
        const sep = Math.abs(s.a.x - s.b.x);
        const r = T.r * (1 - 0.3 * Math.min(sep / (2 * (T.q + T.r) || 1), 1));
        if (!s.split && s.moving && sep - 2 * T.q - 2 * r > -6) {
          s.split = true;
          const lo = bodies[1 - s.lead], hi = bodies[s.lead];
          [0.3, 0.5, 0.72].forEach((t, j) => {
            s.sats.push({
              x: lo.x + (hi.x - lo.x) * t + (Math.random() - 0.5) * 6,
              y: T.cy + (Math.random() - 0.5) * 10,
              vx: (lo.v + hi.v) * 0.45,
              vy: (Math.random() - 0.5) * 60,
              r: 5 + Math.random() * 3 - j * 0.5,
              k: 70 + Math.random() * 90
            });
          });
        }
        s.sats = s.sats.filter(d => {
          d.vx += (d.k * (T.x - d.x) - 9 * d.vx) * dt;
          d.vy += (90 * (T.cy - d.y) - 7 * d.vy) * dt;
          d.x += d.vx * dt;
          d.y += d.vy * dt;
          d.x = Math.max(PAD + 8 + d.r, Math.min(PAD + s.w - 8 - d.r, d.x));
          d.y = Math.max(T.cy - T.r + d.r, Math.min(T.cy + T.r - d.r, d.y));
          if (Math.abs(T.x - d.x) < T.q + T.r * 0.5) d.r -= dt * 26;
          return d.r > 0.6;
        });
        if (s.sats.length) still = false;
      }
      if (s.moving && still) {
        s.moving = false;
        if (s.split || !s.reduce) s.rips.push({x: T.x, y: T.cy, age: 0, amp: s.split ? 1.2 : 0.7});
      }
      s.rips.forEach(p => (p.age += dt));
      s.rips = s.rips.filter(p => p.age < 2.4);
      if (s.mouse.on && now - s.mouse.t > 1200) s.mouse.on = false; // a resting cursor must not keep the loop alive
      s.mouse.s += ((s.mouse.on ? 1 : 0) - s.mouse.s) * Math.min(1, dt * 7);
      // Ambient water fades in on interaction and out again: an idle group draws nothing.
      const awake = !s.reduce && performance.now() < s.awakeUntil;
      s.amb += ((awake ? 1 : 0) - s.amb) * Math.min(1, dt * 2.5);
      if (!awake && s.amb < 0.01) s.amb = 0;
    }

    function loop() {
      if (s.raf || !instances.has(nav)) return;
      s.last = performance.now();
      const frame = 1000 / 30;
      const tick = now => {
        if (low() && now - s.lastDraw < frame - 2) { s.raf = requestAnimationFrame(tick); return; }
        const dt = Math.min((now - s.last) / 1000, 1 / 30);
        s.last = s.lastDraw = now;
        step(dt, now);
        draw();
        const busy = s.moving || s.rips.length || s.sats.length || s.mouse.s > 0.01 || s.amb > 0 || performance.now() < s.awakeUntil;
        s.raf = s.visible && s.w && busy ? requestAnimationFrame(tick) : 0;
      };
      s.raf = requestAnimationFrame(tick);
    }
    function wake(ms = ACTIVE_FOR) {
      motion();
      s.awakeUntil = Math.max(s.awakeUntil, performance.now() + ms);
      loop();
    }

    /* ---- layout ---- */
    function measure() {
      if (!nav.isConnected) return;
      setup();
      motion();
      s.w = nav.offsetWidth;
      s.h = nav.offsetHeight;
      if (!s.w || !s.h) { s.ready = false; return; } // hidden: nothing to draw until it is shown again
      const list = itemsOf(nav);
      list.forEach(el => { if (!el.classList.contains('liquid-nav-item')) el.classList.add('liquid-nav-item'); }); // setting an unchanged class still queues a mutation
      const activeEl = list.find(el => isActive(el) && el.getClientRects().length);
      list.forEach(el => { if (el === activeEl) el.dataset.lqActive = 'true'; else delete el.dataset.lqActive; });
      nav.dataset.lqHasActive = activeEl ? 'true' : 'false';
      const had = s.has;
      s.has = !!activeEl;
      if (!activeEl) { s.moving = false; draw(); loop(); return; }
      const T = s.t;
      const nr = nav.getBoundingClientRect(), er = activeEl.getBoundingClientRect();
      const kx = nr.width / s.w || 1, ky = nr.height / s.h || 1; // iframes and TV scaling may transform us
      const ew = er.width / kx, eh = er.height / ky;
      T.r = eh / 2;
      T.q = Math.max(0, ew / 2 - T.r);
      T.cy = PAD + (er.top - nr.top) / ky - nav.clientTop + eh / 2;
      const nx = PAD + (er.left - nr.left) / kx - nav.clientLeft + ew / 2;
      if (!s.ready || !had) {
        s.a.x = s.b.x = nx;
        s.q = T.q;
        s.ready = true;
      } else if (Math.abs(nx - T.x) > 0.5) {
        s.lead = Math.abs(nx - s.a.x) <= Math.abs(nx - s.b.x) ? 0 : 1;
        s.lagFrom = performance.now() + 70;
        s.moving = true;
        s.split = false;
      }
      T.x = nx;
      wake(s.moving ? ACTIVE_FOR : 900);
    }
    function schedule() {
      if (s.dirty) return;
      s.dirty = true;
      requestAnimationFrame(() => { s.dirty = false; measure(); });
    }

    /* ---- interaction (decoration only; the product handles activation) ---- */
    const local = (x, y) => {
      const r = nav.getBoundingClientRect();
      return {x: (x - r.left) / (r.width / s.w || 1) + PAD, y: (y - r.top) / (r.height / s.h || 1) + PAD};
    };
    function ripple(x, y, amp) {
      if (s.reduce) return;
      s.rips.push({x, y, age: 0, amp});
      wake();
    }
    const itemOf = target => target?.closest ? itemsOf(nav).find(el => el.contains(target)) : null;
    const timers = new WeakMap();
    function clearFx(el) {
      el.querySelectorAll(':scope > .lq-fx').forEach(node => node.remove());
      el.removeAttribute('data-lq-fx');
    }
    function play(el) {
      motion();
      if (s.reduce) return;
      const svg = el.querySelector('svg');
      const kind = el.dataset.liquidFx || (svg ? 'pop' : 'none');
      if (!svg || kind === 'none') return;
      clearTimeout(timers.get(el));
      clearFx(el);
      void el.offsetWidth; // restart the animation
      const er = el.getBoundingClientRect(), ir = svg.getBoundingClientRect();
      const kx = er.width / el.offsetWidth || 1, ky = er.height / el.offsetHeight || 1;
      const cx = (ir.left + ir.width / 2 - er.left) / kx, cy = (ir.top + ir.height / 2 - er.top) / ky;
      el.style.setProperty('--lq-ix', cx + 'px');
      el.style.setProperty('--lq-iy', cy + 'px');
      el.style.setProperty('--lq-ih', ir.height / ky + 'px');
      const add = cls => { const span = document.createElement('span'); span.className = 'lq-fx ' + cls; span.setAttribute('aria-hidden', 'true'); el.append(span); };
      if (kind === 'plane') add('lq-trail');
      if (kind === 'drop') { add('lq-splash l'); add('lq-splash r'); }
      el.setAttribute('data-lq-fx', kind);
      const p = local(ir.left + ir.width / 2, ir.top + ir.height / 2);
      if (kind === 'drop') setTimeout(() => ripple(p.x, s.t.cy, 1.4), 430);
      if (kind === 'plane') ripple(p.x, p.y, 0.6);
      timers.set(el, setTimeout(() => clearFx(el), 1300));
    }

    const on = (target, type, handler, options) => { target.addEventListener(type, handler, options); cleanups.push(() => target.removeEventListener(type, handler, options)); };
    const cleanups = [];
    on(nav, 'click', event => {
      const el = itemOf(event.target);
      if (!el) return;
      play(el);
      wake();
      schedule(); // the product usually flips aria-* in its own handler; measure once more afterwards
    });
    on(nav, 'change', schedule);
    on(nav, 'pointerdown', event => {
      inputMode = 'pointer';
      document.querySelectorAll(SELECTOR).forEach(group => { group.dataset.lqInput = 'pointer'; });
      const p = local(event.clientX, event.clientY);
      ripple(p.x, p.y, 0.8);
    });
    on(nav, 'pointermove', event => {
      if (event.pointerType !== 'mouse') return;
      const p = local(event.clientX, event.clientY);
      s.mouse.x = p.x; s.mouse.y = p.y; s.mouse.on = true; s.mouse.t = performance.now();
      wake(1200);
    });
    on(nav, 'pointerleave', () => { s.mouse.on = false; wake(1200); });
    on(nav, 'focusin', event => {
      const el = itemOf(event.target);
      if (!el) return;
      el.dataset.lqFocus = 'true';
      if (nav.dataset.lqInput === 'key') {
        const r = el.getBoundingClientRect();
        const p = local(r.left + r.width / 2, r.top + r.height / 2);
        ripple(p.x, s.t.cy || p.y, 0.5); // a soft wave under the focus, never moving the drop
      }
    });
    on(nav, 'focusout', event => { const el = itemOf(event.target); if (el) delete el.dataset.lqFocus; });

    const mutations = new MutationObserver(records => {
      if (records.every(r => r.type === 'childList' && [...r.addedNodes, ...r.removedNodes].every(n => isLayer(n)))) return;
      layers();
      schedule();
    });
    mutations.observe(nav, {subtree: true, childList: true, attributes: true,
      attributeFilter: ['aria-current', 'aria-selected', 'aria-pressed', 'aria-checked', 'hidden', 'disabled', 'class', 'data-liquid-tone', 'data-liquid-quality', 'data-liquid-refract', 'data-liquid-motion']});
    let lastW = -1;
    const resize = new ResizeObserver(() => {
      const w = nav.offsetWidth;
      if (w !== lastW) { lastW = w; s.ready = false; schedule(); }
    });
    resize.observe(nav);
    const io = new IntersectionObserver(([entry]) => { s.visible = entry.isIntersecting; if (s.visible) { wake(900); } });
    io.observe(nav);
    const onVisibility = () => { s.visible = document.visibilityState === 'visible'; if (s.visible) wake(900); };
    on(document, 'visibilitychange', onVisibility);
    const onMotion = () => { motion(); schedule(); };
    motionQuery?.addEventListener?.('change', onMotion);

    const api = {
      measure: schedule,
      noGL() { s.gl = false; nav.dataset.lqGl = 'off'; schedule(); },
      destroy() {
        cancelAnimationFrame(s.raf);
        s.raf = 0;
        mutations.disconnect(); resize.disconnect(); io.disconnect();
        motionQuery?.removeEventListener?.('change', onMotion);
        cleanups.forEach(fn => fn());
        [glass, canvas, drop].forEach(layer => layer.remove());
        nav.classList.remove('liquid-nav');
        ['lqGl', 'lqRefract', 'lqHasActive', 'lqInput', 'lqMotion'].forEach(key => delete nav.dataset[key]);
        itemsOf(nav).forEach(el => { delete el.dataset.lqActive; delete el.dataset.lqFocus; clearFx(el); el.classList.remove('liquid-nav-item'); });
      }
    };
    instances.set(nav, api);
    measure();
    schedule();
    return api;
  }

  /* ---------- discovery: groups appear and disappear dynamically ---------- */
  function upgrade(nav) { return nav && !instances.has(nav) ? create(nav) : instances.get(nav); }
  function destroy(nav) { const instance = instances.get(nav); if (instance) { instances.delete(nav); instance.destroy(); } }
  function scan(root = document) {
    if (root.nodeType === 1 && root.matches(SELECTOR)) upgrade(root);
    root.querySelectorAll?.(SELECTOR).forEach(upgrade);
  }
  function sweep() {
    instances.forEach((instance, nav) => { if (!nav.isConnected || !nav.matches(SELECTOR)) destroy(nav); });
  }
  new MutationObserver(records => {
    for (const record of records) {
      record.addedNodes.forEach(node => node.nodeType === 1 && scan(node));
      if (record.type === 'attributes' && record.target.matches(SELECTOR)) upgrade(record.target);
    }
    if (records.some(r => r.removedNodes.length || r.type === 'attributes')) sweep();
  }).observe(document.documentElement, {subtree: true, childList: true, attributes: true, attributeFilter: ['data-liquid-nav']});
  // Input modality: keyboard and D-pad show the focus ring, a pointer hides it until the next key.
  document.addEventListener('keydown', () => {
    if (inputMode === 'key') return;
    inputMode = 'key';
    instances.forEach((_, nav) => { nav.dataset.lqInput = 'key'; });
  }, true);
  const start = () => scan(document);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, {once: true}); else start();

  window.LiquidNav = Object.freeze({version: '1.0.0', scan, upgrade, destroy, refresh: nav => instances.get(nav)?.measure()});
})();
