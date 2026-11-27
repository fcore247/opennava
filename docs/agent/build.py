#!/usr/bin/env python3
"""Write agent/index.html from agent.md, so that people and agents read the very same text."""
import html
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)
    text = re.sub(r'&lt;(https://[^&\s]+)&gt;', r'<a href="\1">\1</a>', text)
    return text


def convert(source):
    out, lines, i = [], source.split('\n'), 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('```'):
            block = []
            i += 1
            while not lines[i].startswith('```'):
                block.append(lines[i])
                i += 1
            out.append('<pre><code>' + html.escape('\n'.join(block)) + '</code></pre>')
        elif line.startswith('#'):
            level = len(line) - len(line.lstrip('#'))
            title = line[level:].strip()
            anchor = re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')
            out.append(f'<h{level} id="{anchor}">{inline(title)}</h{level}>')
        elif line.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].startswith('|'):
                cells = [c.strip() for c in lines[i].strip('|').split('|')]
                if not all(re.fullmatch(r':?-+:?', c) for c in cells):
                    rows.append(cells)
                i += 1
            head, body = rows[0], rows[1:]
            out.append('<div class="table"><table><thead><tr>' + ''.join(f'<th>{inline(c)}</th>' for c in head) + '</tr></thead><tbody>'
                       + ''.join('<tr>' + ''.join(f'<td>{inline(c)}</td>' for c in r) + '</tr>' for r in body) + '</tbody></table></div>')
            continue
        elif re.match(r'(- |\d+\. )', line):
            tag = 'ul' if line.startswith('- ') else 'ol'
            items = []
            while i < len(lines) and re.match(r'(- |\d+\. )', lines[i]):
                items.append(re.sub(r'^(- |\d+\. )', '', lines[i]))
                i += 1
            out.append(f'<{tag}>' + ''.join(f'<li>{inline(x)}</li>' for x in items) + f'</{tag}>')
            continue
        elif line.strip():
            paragraph = [line]
            while i + 1 < len(lines) and lines[i + 1].strip() and not re.match(r'(#|```|\||- |\d+\. )', lines[i + 1]):
                i += 1
                paragraph.append(lines[i])
            out.append('<p>' + '<br>'.join(inline(p) for p in paragraph) + '</p>')
        i += 1
    return '\n'.join(out)


PAGE = '''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>OpenNava · Instructions for AI agents</title>
<meta name="description" content="Step-by-step instructions an AI agent can follow to set up OpenNava on a person's own TV.">
<link rel="canonical" href="https://open-nava.com/agent/">
<link rel="alternate" type="text/markdown" href="./agent.md" title="Markdown for agents">
<style>
:root{color-scheme:dark}body{margin:0;background:#101115;color:#e9e8f2;font:16px/1.65 system-ui,sans-serif}
main{max-width:820px;margin:0 auto;padding:28px 20px 80px}a{color:#cabaff}
.hint{border:1px solid #cabaff66;background:#cabaff14;border-radius:14px;padding:14px 18px;font-size:14.5px}
h1{font-size:30px;line-height:1.2}h2{margin-top:38px;border-top:1px solid #ffffff1f;padding-top:22px;font-size:21px}
code{background:#ffffff14;border-radius:5px;padding:1px 5px;font-size:.9em}
pre{background:#07080b;border:1px solid #ffffff1f;border-radius:12px;padding:14px 16px;overflow-x:auto}pre code{background:none;padding:0}
.table{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:14.5px}th,td{border:1px solid #ffffff24;padding:8px 10px;text-align:left;vertical-align:top}
</style>
</head>
<body>
<main>
<p class="hint" id="de"><strong>Für Menschen:</strong> Gib deinem KI-Agenten diesen Satz: <code>Richte OpenNava auf meinem Fernseher ein. Folge der Anleitung unter https://open-nava.com/agent</code><br>
<strong>For people:</strong> Give your AI agent this sentence: <code>Set up OpenNava on my TV. Follow the instructions at https://open-nava.com/agent</code><br>
<strong>For agents:</strong> the same instructions as plain Markdown: <a href="./agent.md">agent.md</a></p>
__BODY__
<p><a href="../">← Live demo</a></p>
</main>
</body>
</html>
'''

(HERE / 'index.html').write_text(PAGE.replace('__BODY__', convert((HERE / 'agent.md').read_text(encoding='utf-8'))), encoding='utf-8')
print('agent/index.html written')
