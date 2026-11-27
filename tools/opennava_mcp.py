#!/usr/bin/env python3
"""Model Context Protocol server that lets the viewer's AI agent manage OpenNava libraries and streams."""
import base64
import json
import os
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
FORMAT = 'opennava.mediathek/1'
INLINE_LIMIT = 60_000
RUST = 'content://site.navatv.rustpreview.companion'
LIVE = 'content://site.navatv.rustpreview.live'


class Problem(Exception):
    pass


def web(value):
    if not isinstance(value, str) or len(value) > 1500 or any(c.isspace() for c in value.strip()):
        return ''
    parts = urlsplit(value.strip())
    ok = parts.scheme in ('http', 'https') and parts.hostname and '@' not in parts.netloc
    return value.strip() if ok else ''


def parse_library(text):
    """Return (name, items); mirrors the rules of the TV and the companion."""
    text = text.lstrip('﻿').strip()
    if text.startswith('{'):
        try:
            root = json.loads(text)
        except ValueError as error:
            raise Problem(f'Invalid JSON: {error}')
        if root.get('format') != FORMAT:
            raise Problem(f'The document needs "format": "{FORMAT}".')
        rows = [item for section in root.get('sections') or [] for item in section.get('items') or []]
        items = [dict(title=str(r.get('title', '')).strip(), url=web(r.get('url'))) for r in rows]
        name = str(root.get('name', '')).strip()
    else:
        if '<!DOCTYPE' in text or '<!ENTITY' in text:
            raise Problem('Document type declarations are not supported.')
        try:
            root = ElementTree.fromstring(text)
        except ElementTree.ParseError as error:
            raise Problem(f'Neither an OpenNava document nor a video feed: {error}')
        local = lambda element: element.tag.rsplit('}', 1)[-1]
        first = next((e for e in root.iter() if local(e) == 'title'), None)
        name = (first.text or '').strip() if first is not None else ''
        items = []
        for entry in (e for e in root.iter() if local(e) in ('item', 'entry')):
            title = next(((c.text or '').strip() for c in entry if local(c) == 'title'), '')
            url = ''
            for child in entry.iter():
                kind = child.get('type', '').lower()
                video = kind.startswith('video/') or 'mpegurl' in kind or child.get('medium') == 'video'
                if local(child) in ('enclosure', 'content') and video:
                    url = web(child.get('url'))
                    if url:
                        break
            items.append(dict(title=title, url=url))
    items = [item for item in items if item['title'] and item['url']]
    if not items:
        raise Problem('No playable videos were found. Each item needs a title and a video address.')
    return name, items[:400]


def fetch(url):
    if not web(url):
        raise Problem('Use an http:// or https:// address.')
    request = urllib.request.Request(url, headers={'User-Agent': 'OpenNava-MCP'})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            data = response.read(4 * 1024 * 1024 + 1)
    except OSError as error:
        raise Problem(f'The address could not be read: {error}')
    if len(data) > 4 * 1024 * 1024:
        raise Problem('This library is larger than 4 MB.')
    return data.decode('utf-8', 'replace')


def load(arguments):
    url, document = arguments.get('url', ''), arguments.get('document')
    if isinstance(document, dict):
        document = json.dumps(document, ensure_ascii=False, separators=(',', ':'))
    if bool(url) == bool(document):
        raise Problem('Give either "url" or "document".')
    name, items = parse_library(fetch(url) if url else document)
    return url, document or '', name, items


def adb(*args, stdin=None):
    try:
        result = subprocess.run(['adb', *args], input=stdin, capture_output=True, text=True, timeout=60)
    except FileNotFoundError:
        raise Problem('adb was not found. Install Android SDK Platform Tools.')
    except subprocess.TimeoutExpired:
        raise Problem('The TV did not answer in time.')
    if result.returncode:
        raise Problem((result.stderr or result.stdout).strip() or 'adb failed.')
    return result.stdout


def television():
    wanted = os.environ.get('OPENNAVA_TV')
    if wanted:
        return wanted
    found = []
    for line in adb('devices').splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == 'device':
            features = adb('-s', fields[0], 'shell', 'pm list features')
            if 'android.software.leanback' in features:
                found.append(fields[0])
    if len(found) != 1:
        raise Problem('Connect exactly one authorized TV with adb, or set OPENNAVA_TV. Found: ' + (', '.join(found) or 'none'))
    return found[0]


def call(authority, method, arg=None):
    command = f'content call --user "$(am get-current-user)" --uri {authority} --method {method}'
    if arg is None:
        output = adb('-s', television(), 'shell', command)
    else:
        # Long values exceed adbd's command limit, so they travel through stdin.
        output = adb('-s', television(), 'shell', f'IFS= read -r value && {command} --arg "$value"', stdin=arg + '\n')
    if 'Result: Bundle' not in output:
        raise Problem('The TV app did not answer. Install the current OpenNava release. ' + output.strip()[:300])
    return output


def encode(value):
    return base64.urlsafe_b64encode(value.encode()).decode().rstrip('=')


def decoded(output, key):
    found = re.search(key + r'=([A-Za-z0-9_=-]*)', output)
    if not found:
        raise Problem('The TV app did not answer as expected. Update OpenNava on the TV.')
    value = found.group(1)
    return json.loads(base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)).decode() or '[]')


def require_handled(output, message):
    if 'handled=true' not in output:
        raise Problem(message)


def channel_rows():
    guide = (ROOT / 'android/tv/src/main/java/site/navatv/launcher/LiveGuide.java').read_text(encoding='utf-8')
    german = re.findall(r'new Channel\("([a-z0-9-]+)", "([^"]+)"', guide)
    american = re.findall(r'us\("([a-z0-9-]+)", "([^"]+)"', guide)
    return [dict(id=i, name=n) for i, n in german + american]


def library_format(arguments):
    return (ROOT / 'guide/MEDIATHEK_FORMAT.md').read_text(encoding='utf-8')


def check_library(arguments):
    _, _, name, items = load(arguments)
    return dict(name=name, videos=len(items), first=items[:3])


def list_libraries(arguments):
    rows = decoded(call(RUST, 'library_sources_get'), 'sources64')
    return [dict(id=r['id'], name=r['name'], url=r['url'] or None, inline=bool(r['document'])) for r in rows]


def add_library(arguments):
    url, document, found_name, items = load(arguments)
    name = str(arguments.get('name') or found_name or (urlsplit(url).hostname if url else '')).strip()
    if not name:
        raise Problem('Give the library a "name".')
    if len(document.encode()) > INLINE_LIMIT:
        raise Problem(f'Documents above {INLINE_LIMIT} bytes must be published at a web address.')
    entry = dict(name=name, color=arguments.get('color', ''), url=url, document=document)
    output = call(RUST, 'library_source_put', encode(json.dumps(entry)))
    require_handled(output, 'The TV did not accept the library. It keeps up to 20.')
    return dict(added=name, videos=len(items))


def remove_library(arguments):
    identifier = str(arguments.get('id', ''))
    if not re.fullmatch(r'lib-[0-9a-f]{12}', identifier):
        raise Problem('Use an id from list_libraries.')
    require_handled(call(RUST, 'library_source_remove', identifier), 'No library with this id.')
    return dict(removed=identifier)


def list_streams(arguments):
    return decoded(call(LIVE, 'channels_get'), 'channels64')


def add_stream(arguments):
    url = web(arguments.get('url'))
    if not url:
        raise Problem('Use an http:// or https:// stream address.')
    entry = [dict(name=str(arguments.get('name', '')), url=url)]
    require_handled(call(LIVE, 'channels_add', encode(json.dumps(entry))), 'The TV did not save the stream.')
    return dict(added=url)


def remove_stream(arguments):
    identifier = str(arguments.get('id', ''))
    if not re.fullmatch(r'custom-[0-9a-f]{12}', identifier):
        raise Problem('Use an id from list_streams.')
    require_handled(call(LIVE, 'channels_remove', identifier), 'The TV could not remove it.')
    return dict(removed=identifier)


def schema(properties=None, required=()):
    return dict(type='object', properties=properties or {}, required=list(required))


TEXT = dict(type='string')
SOURCE = dict(url=dict(TEXT, description='Address of a video feed or OpenNava document'),
              document=dict(type=['object', 'string'], description='An inline OpenNava library document'))
TOOLS = {
    'library_format': (library_format, 'Read how to describe an OpenNava media library. Read this first.', schema()),
    'check_library': (check_library, 'Validate a feed or document without changing the TV.', schema(SOURCE)),
    'list_libraries': (list_libraries, "List the viewer's libraries on the TV.", schema()),
    'add_library': (add_library, 'Add or replace a library on the TV. The same address or name replaces it.',
                    schema(dict(SOURCE, name=TEXT, color=dict(TEXT, description='#rrggbb')))),
    'remove_library': (remove_library, 'Remove a library from the TV.', schema(dict(id=TEXT), ['id'])),
    'list_channels': (lambda a: channel_rows(), 'List built-in live channel IDs, used for catch-up.', schema()),
    'list_streams': (list_streams, "List the viewer's own live channels.", schema()),
    'add_stream': (add_stream, 'Add a live stream as a channel.', schema(dict(url=TEXT, name=TEXT), ['url'])),
    'remove_stream': (remove_stream, 'Remove an own live channel.', schema(dict(id=TEXT), ['id'])),
}


def handle(message):
    """Return the JSON-RPC response, or None for notifications."""
    method, identifier = message.get('method'), message.get('id')
    if identifier is None:
        return None
    reply = lambda result: dict(jsonrpc='2.0', id=identifier, result=result)
    if method == 'initialize':
        version = (message.get('params') or {}).get('protocolVersion') or '2025-06-18'
        return reply(dict(protocolVersion=version, capabilities=dict(tools={}),
                          serverInfo=dict(name='opennava', version='1.0.0')))
    if method == 'ping':
        return reply({})
    if method == 'tools/list':
        return reply(dict(tools=[dict(name=n, description=d, inputSchema=s) for n, (_, d, s) in TOOLS.items()]))
    if method == 'tools/call':
        params = message.get('params') or {}
        tool = TOOLS.get(params.get('name'))
        if not tool:
            return dict(jsonrpc='2.0', id=identifier, error=dict(code=-32602, message='Unknown tool'))
        try:
            result, failed = tool[0](params.get('arguments') or {}), False
        except (Problem, OSError, ValueError, KeyError, AttributeError, TypeError) as error:
            result, failed = str(error), True
        text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, indent=2)
        return reply(dict(content=[dict(type='text', text=text)], isError=failed))
    return dict(jsonrpc='2.0', id=identifier, error=dict(code=-32601, message='Method not found'))


def main():
    for line in sys.stdin:
        try:
            message = json.loads(line)
        except ValueError:
            continue
        response = handle(message)
        if response is not None:
            print(json.dumps(response), flush=True)


if __name__ == '__main__':
    main()
