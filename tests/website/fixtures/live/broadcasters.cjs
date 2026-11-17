const {interceptHls} = require('./media.cjs');

const ardId = Buffer.from('crid://daserste.de/live/clip/abca07a3-3476-4851-b162-fde8f646d4c4').toString('base64url');
const ardStream = 'https://daserste-live.ard-mcdn.de/daserste/live/hls/de/master.m3u8';
const zdfStream = 'https://zdf-hls-15.akamaized.net/hls/live/2016498/de/high/master.m3u8';
const berlin = new Intl.DateTimeFormat('de-DE', {timeZone: 'Europe/Berlin', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'});

function entries(channel) {
  // Give each test an earlier, current and upcoming programme at any time of day.
  const now = Math.floor(Date.now() / 60000) * 60000;
  return ['Early hours', 'Morning', 'Afternoon', 'Evening', 'Late night'].map((period, i) => ({
    title: channel + ' ' + period, start: now + (i * 60 - 150) * 60000, end: now + (i * 60 - 90) * 60000
  }));
}

function send(route, json) {
  return route.fulfill({status: 200, headers: {'access-control-allow-origin': '*'}, json});
}

async function broadcasters(page, {offline = false} = {}) {
  await interceptHls(page);
  await page.route('https://api.ardmediathek.de/page-gateway/pages/ard/item/**', route => {
    if (offline) return route.abort();
    const id = new URL(route.request().url()).pathname.split('/').pop();
    return send(route, {widgets: [{type: 'player_live', id, mediaCollection: {embedded: {
      streams: [{kind: 'main', media: [{mimeType: 'application/vnd.apple.mpegurl', url: ardStream}]}]
    }}}]});
  });
  await page.route('https://programm-api.ard.de/program/api/program?**', route => send(route, {channels: [{
    crid: ardId, timeSlots: [entries('ARD').map(p => ({title: p.title, broadcastedOn: new Date(p.start).toISOString(),
      broadcastEnd: new Date(p.end).toISOString(), links: {target: {partner: 'ard', urlId: 'testReplay'}}}))]
  }]}));
  await page.route('https://zdf-prod-futura.zdf.de/mediathekV2/document/**', route => {
    if (offline) return route.abort();
    const id = new URL(route.request().url()).pathname.split('/').pop();
    return send(route, {document: {id, type: 'livevideo', formitaeten: [{
      class: 'main', language: 'deu', quality: 'auto', mimeType: 'application/vnd.apple.mpegurl', url: zdfStream
    }]}});
  });
  await page.route('https://zdf-prod-futura.zdf.de/mediathekV2/live-tv/**', route => send(route, {epgCluster: [{
    name: 'ZDF', teaser: entries('ZDF').map(p => ({titel: p.title, airtime: berlin.format(p.start).replace(', ', ' '),
      airtimeEnd: berlin.format(p.end).replace(', ', ' '), type: 'video', hasVideo: true, sharingUrl: 'https://www.zdf.de/test-replay'}))
  }]}));
}

module.exports = {broadcasters};
