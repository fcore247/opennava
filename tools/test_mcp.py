import json
import unittest
from unittest import mock

import opennava_mcp as server

DOCUMENT = dict(format=server.FORMAT, name='Test', sections=[dict(title='A', items=[
    dict(title='One', url='https://example.org/one.m3u8'),
    dict(title='Local file', url='file:///etc/passwd')])])


class LibraryTests(unittest.TestCase):
    def test_document_keeps_only_playable_web_videos(self):
        name, items = server.parse_library(json.dumps(DOCUMENT))
        self.assertEqual(('Test', ['One']), (name, [i['title'] for i in items]))

    def test_feed_needs_video_and_rejects_doctype(self):
        feed = ('<rss><channel><title>F</title><item><title>V</title>'
                '<enclosure url="https://example.org/v.mp4" type="video/mp4"/></item></channel></rss>')
        self.assertEqual('V', server.parse_library(feed)[1][0]['title'])
        with self.assertRaises(server.Problem):
            server.parse_library('<!DOCTYPE x><rss/>')
        with self.assertRaises(server.Problem):
            server.parse_library(feed.replace('video/mp4', 'audio/mpeg'))

    def test_protocol_lists_tools_and_reports_tool_errors(self):
        listed = server.handle(dict(id=1, method='tools/list'))['result']['tools']
        self.assertIn('add_library', [tool['name'] for tool in listed])
        failed = server.handle(dict(id=2, method='tools/call', params=dict(name='check_library', arguments={})))
        self.assertTrue(failed['result']['isError'])
        self.assertIsNone(server.handle(dict(method='notifications/initialized')))

    def test_add_sends_the_entry_through_stdin(self):
        with mock.patch.object(server, 'call', return_value='Result: Bundle[{handled=true}]') as sent:
            self.assertEqual(dict(added='Test', videos=1), server.add_library(dict(document=DOCUMENT)))
        self.assertEqual('library_source_put', sent.call_args[0][1])

    def test_channels_come_from_the_tv_lineup(self):
        ids = [row['id'] for row in server.channel_rows()]
        self.assertIn('zdf', ids)
        self.assertIn('us-cbsnews', ids)


if __name__ == '__main__':
    unittest.main()
