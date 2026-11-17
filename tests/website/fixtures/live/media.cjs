const {readFileSync} = require('node:fs');
const {join} = require('node:path');

const segments = readFileSync(join(__dirname, 'stream.mp4'));
const playlist = readFileSync(join(__dirname, 'stream.m3u8'), 'utf8');
const fragmentGroups = playlist.match(/#EXTINF:[^\n]+\n#EXT-X-BYTERANGE:[^\n]+\nstream\.mp4/g);
const header = playlist.split('#EXTINF:')[0];

// Keep HLS playback, decoding and seekable ranges real while avoiding a dependency
// on an external broadcaster or the network. This clip is generated from a solid colour.
function liveMedia(openedAt, origin) {
  const first = Math.min(6, Math.floor((Date.now() - openedAt) / 2000));
  const body = header.replace('#EXT-X-MEDIA-SEQUENCE:0', '#EXT-X-MEDIA-SEQUENCE:' + first)
    + fragmentGroups.slice(first, first + 9).join('\n') + '\n';
  return body.replaceAll('stream.mp4', origin + '/opennava-test-stream.mp4');
}

async function interceptHls(page) {
  const openedAt = Date.now();
  const cors = {'access-control-allow-origin': '*', 'access-control-allow-headers': 'Range', 'access-control-expose-headers': 'Content-Range,Content-Length'};
  await page.route(/\.m3u8(?:[?#]|$)/i, route => route.fulfill({
    status: 200, headers: cors, contentType: 'application/vnd.apple.mpegurl',
    body: liveMedia(openedAt, new URL(route.request().url()).origin)
  }));
  await page.route('**/opennava-test-stream.mp4', route => {
    const range = route.request().headers().range;
    if (!range) return route.fulfill({contentType: 'video/mp4', headers: cors, body: segments});
    const match = /^bytes=(\d+)-(\d*)$/.exec(range);
    if (!match) return route.fulfill({status: 416, headers: cors});
    const start = Number(match[1]);
    const end = match[2] ? Math.min(Number(match[2]), segments.length - 1) : segments.length - 1;
    if (start > end || start >= segments.length) return route.fulfill({status: 416, headers: cors});
    return route.fulfill({status: 206, contentType: 'video/mp4',
      headers: {...cors, 'accept-ranges': 'bytes', 'content-range': `bytes ${start}-${end}/${segments.length}`},
      body: segments.subarray(start, end + 1)
    });
  });
}

module.exports = {interceptHls};
