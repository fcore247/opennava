// Public broadcaster metadata and HLS playback for the website TV.
(() => {
  "use strict";

  const libraryURL = new URL("vendor/hls-1.7.4.min.js", document.currentScript.src);
  const ardItem = "https://api.ardmediathek.de/page-gateway/pages/ard/item/";
  const ardGuide = "https://programm-api.ard.de/";
  const zdf = "https://zdf-prod-futura.zdf.de/mediathekV2/";
  const arteGuide = "https://api.arte.tv/api/emac/v4/de/web/pages/TV_GUIDE/?day=";
  const hlsMime = "application/vnd.apple.mpegurl";
  const guideRequests = new Map();
  const berlinDate = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Europe/Berlin", year: "numeric", month: "2-digit", day: "2-digit"
  });
  const berlinOffset = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Berlin", timeZoneName: "shortOffset", hour: "2-digit"
  });
  let libraryRequest;

  function station(id) {
    return (window.openNavaStations || []).find(channel => channel.id === id);
  }

  async function json(url, signal) {
    const response = await fetch(url, {signal, credentials: "omit"});
    if (!response.ok) throw new Error("The broadcaster returned HTTP " + response.status + ".");
    return response.json();
  }

  function hlsURL(variant) {
    return typeof variant?.url === "string" && variant.url.startsWith("https://") &&
      /mpegurl/i.test(variant.mimeType);
  }

  async function source(channel, signal) {
    const id = channel.sourceId;
    if (id.endsWith("-live-beitrag-100")) {
      const {document: item} = await json(zdf + "document/" + encodeURIComponent(id), signal);
      if (item?.id === id && item.type === "livevideo") {
        const streams = (item.formitaeten || []).filter(v =>
          v.class === "main" && (v.language || "deu") === "deu" && hlsURL(v));
        return (streams.find(v => v.quality === "auto") || streams[0])?.url;
      }
      return;
    }
    // This also covers the German ARTE simulcast and each regional edition by its own CRID.
    const page = await json(ardItem + encodeURIComponent(id) + "?embedded=true&mcV6=true", signal);
    const live = page.widgets?.find(w => w.type === "player_live" && w.id === id && !w.blockedByFsk);
    const tracks = live?.mediaCollection?.embedded?.streams || [];
    return tracks.filter(t => (t.kind || "main") === "main").flatMap(t => t.media || []).find(hlsURL)?.url;
  }

  function loadHls() {
    if (!libraryRequest) {
      libraryRequest = new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = libraryURL.href;
        script.onload = () => resolve(window.Hls);
        script.onerror = () => {
          libraryRequest = null;
          script.remove();
          reject(new Error("The browser player could not load."));
        };
        document.head.append(script);
      });
    }
    return libraryRequest;
  }

  function attach(video, channelId, {onStatus = () => {}} = {}) {
    let cancelAttempt = () => {};
    let destroyed = false;

    function start() {
      if (destroyed) return;
      cancelAttempt();
      const abort = new AbortController();
      const {signal} = abort;
      let hls;
      let mediaRecovery = false;
      let available = false;
      const timer = setTimeout(() => fail("The broadcaster did not start in time."), 20000);
      function ready() {
        if (signal.aborted || available) return;
        available = true;
        clearTimeout(timer);
        onStatus("live", "");
      }
      function fail(reason) {
        if (signal.aborted) return;
        cancelAttempt();
        onStatus("unavailable", reason || "This broadcast could not be played here.");
      }
      function mediaFailure() {
        fail("The broadcast could not be decoded or is unavailable in your region.");
      }
      const stop = () => {
        abort.abort();
        clearTimeout(timer);
        video.removeEventListener("loadeddata", ready);
        video.removeEventListener("error", mediaFailure);
        hls?.destroy();
        video.pause();
        video.removeAttribute("src");
        video.load();
      };
      cancelAttempt = () => { if (!signal.aborted) stop(); };
      onStatus("loading", "");
      const channel = station(channelId);
      if (!channel?.sourceId) {
        fail("The broadcaster has no public stream for this channel.");
        return;
      }
      video.addEventListener("loadeddata", ready);
      video.addEventListener("error", mediaFailure);
      (async () => {
        const url = await source(channel, signal);
        if (signal.aborted) return;
        if (!url) throw new Error("This channel is currently unavailable from the broadcaster.");
        const nativeHls = Boolean(video.canPlayType(hlsMime));
        const preferNative = nativeHls && (navigator.vendor === "Apple Computer, Inc." || !window.MediaSource);
        let Hls;
        if (!preferNative) {
          try { Hls = await loadHls(); }
          catch (error) { if (!nativeHls) throw error; }
        }
        if (signal.aborted) return;
        if (preferNative || (nativeHls && !Hls?.isSupported())) {
          video.src = url;
        } else {
          if (!Hls?.isSupported()) throw new Error("This browser does not support this live stream.");
          hls = new Hls({capLevelToPlayerSize: true, backBufferLength: 120});
          hls.on(Hls.Events.ERROR, (_event, data) => {
            if (!data.fatal || signal.aborted) return;
            if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !mediaRecovery) {
              mediaRecovery = true;
              hls.recoverMediaError();
              return;
            }
            mediaFailure();
          });
          hls.loadSource(url);
          hls.attachMedia(video);
        }
        // Browser media policy can reserve sound until the visitor presses Play.
        video.play().catch(error => {
          if (!signal.aborted && error.name !== "NotAllowedError" && error.name !== "AbortError") mediaFailure();
        });
      })().catch(error => { if (!signal.aborted) fail(error.message); });
    }
    queueMicrotask(start);
    return {retry: start, destroy() { destroyed = true; cancelAttempt(); }};
  }

  function officialLink(value, host) {
    try {
      const url = new URL(value);
      if (url.protocol === "https:" && url.hostname === host) return url.href;
    } catch { /* Entries without a replay link can still appear in the guide. */ }
  }

  function event(title, start, end, url) {
    const from = Date.parse(start), to = Date.parse(end);
    if (typeof title !== "string" || !title.trim() || !Number.isFinite(from) || !(to > from)) return null;
    return {title: title.trim(), start: new Date(from).toISOString(), end: new Date(to).toISOString(), ...(url && {url})};
  }

  function cachedJSON(url) {
    const now = Date.now();
    for (const [key, entry] of guideRequests) if (entry.expires < now) guideRequests.delete(key);
    const saved = guideRequests.get(url);
    if (saved) return saved.promise;
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 6500);
    const promise = json(url, abort.signal).finally(() => clearTimeout(timer)).catch(error => {
      if (guideRequests.get(url)?.promise === promise) guideRequests.delete(url);
      throw error;
    });
    guideRequests.set(url, {promise, expires: now + 150000});
    return promise;
  }

  // ZDF sends local Berlin wall-clock values without a timezone. Resolve them independently
  // of the visitor's timezone, including the UTC offset in effect on the date of broadcast.
  function zdfTime(raw) {
    const match = /^(\d\d)\.(\d\d)\.(\d{4}) (\d\d):(\d\d)$/.exec(raw);
    if (!match) return "";
    const wall = Date.UTC(+match[3], +match[2] - 1, +match[1], +match[4], +match[5]);
    let time = wall;
    for (let i = 0; i < 2; i++) {
      const offset = berlinOffset.formatToParts(time).find(p => p.type === "timeZoneName").value;
      time = wall - Number(offset.replace("GMT", "")) * 3600000;
    }
    return new Date(time).toISOString();
  }

  async function dayGuide(channel, day) {
    if (channel.id === "arte") {
      const data = await cachedJSON(arteGuide + day);
      const rows = data.zones?.find(z => z.code === "listing_TV_GUIDE")?.content?.data || [];
      return rows.map((r, i) => {
        const start = r.availability?.upcomingDate;
        const end = rows[i + 1]?.availability?.upcomingDate || new Date(Date.parse(start) + Number(r.duration) * 1000).toJSON();
        const title = [r.title, r.subtitle].filter(Boolean).join(" — ");
        const replay = r.availability?.hasVideoStreams && r.stickers?.some(s => s.code === "FULL_VIDEO");
        return event(title, start, end, replay && officialLink(r.url, "www.arte.tv"));
      }).filter(Boolean);
    }
    if (channel.sourceId.endsWith("-live-beitrag-100") || channel.id === "3sat") {
      const data = await cachedJSON(zdf + "live-tv/" + day);
      const rows = data.epgCluster?.find(c => c.name?.toLowerCase() === channel.name.toLowerCase())?.teaser || [];
      return rows.map(r => event(r.titel, zdfTime(r.airtime), zdfTime(r.airtimeEnd),
        r.type === "video" && r.hasVideo && officialLink(r.sharingUrl, "www.zdf.de"))).filter(Boolean);
    }
    const data = await cachedJSON(ardGuide + "program/api/program?channelIds=" + encodeURIComponent(channel.id) + "&day=" + day);
    const slots = data.channels?.find(c => c.crid === channel.sourceId)?.timeSlots || [];
    return slots.flat().map(r => {
      const target = r.links?.target;
      const id = target?.partner === "ard" && !r.video?.isTrailer && r.video?.isPublished !== false && target.urlId;
      const url = id && /^[a-z\d_=-]+$/i.test(id) && "https://www.ardmediathek.de/video/" + id;
      return event(r.title, r.broadcastedOn, r.broadcastEnd, url);
    }).filter(Boolean);
  }

  async function currentRegional(channel) {
    const result = await cachedJSON(ardGuide + "nownext/api/channel?channel=" + encodeURIComponent(channel.sourceId) + "&pastHours=4&futureEvents=12");
    if (result.id !== channel.sourceId) return [];
    return (result.events || []).map(r => event(r.title?.short, r.currentStartDate || r.startDate, r.endDate)).filter(Boolean);
  }

  function windowAround(rows, now) {
    const sorted = [...new Map(rows.map(r => [r.start, r])).values()].sort((a,b) => a.start.localeCompare(b.start));
    return {
      previous: sorted.filter(r => Date.parse(r.end) <= now).pop(),
      now: sorted.find(r => Date.parse(r.start) <= now && now < Date.parse(r.end)),
      next: sorted.find(r => Date.parse(r.start) > now),
      entries: sorted
    };
  }

  async function guide(id) {
    const channel = station(id);
    if (!channel?.sourceId) return null;
    try {
      const now = Date.now();
      const regional = channel.regional || channel.id === "dw";
      if (regional) {
        const slots = windowAround(await currentRegional(channel), now);
        return slots.entries.length ? slots : null;
      }
      const day = berlinDate.format(now);
      let rows = await dayGuide(channel, day).catch(() => []);
      let slots = windowAround(rows, now);
      // ARD's daily grid can omit consecutive episodes with identical titles.
      // Its exact-channel feed lists each playout slot separately, including those gaps.
      if (!slots.now && !channel.sourceId.endsWith("-live-beitrag-100")) {
        const playout = await currentRegional(channel).catch(() => []);
        rows = playout.concat(rows);
        slots = windowAround(rows, now);
      }
      const extra = [];
      if (!slots.previous || !slots.now) extra.push(berlinDate.format(now - 86400000));
      if (!slots.next) extra.push(berlinDate.format(now + 86400000));
      if (extra.length) {
        const adjacent = await Promise.all(extra.map(d => dayGuide(channel, d).catch(() => [])));
        slots = windowAround(rows.concat(...adjacent), now);
      }
      return slots.entries.length ? slots : null;
    } catch {
      return null;
    }
  }

  window.OpenNavaLive = {attach, guide};
})();
