# TV launcher browser tests

This optional suite exercises the embedded TV launcher on the published website in Chromium. It requires Node.js 20 or newer.

```sh
cd tests/website
npm ci
npx playwright install chromium
npm test
```

To test a checkout, run `python3 -m http.server 8000 --directory docs` from the project root. In the tests directory, run `OPENNAVA_URL=http://127.0.0.1:8000/ npm test`. The base URL can also point to another static deployment.

The desktop test uses keyboard navigation in the full launcher, plays a sample and switches to senior view from inside the TV. The mobile test taps the senior launcher's ZDF library, rotates the viewport, plays and pauses the sample stream, and returns to the full launcher. Both check the profile selector, the absence of a virtual phone, and the Android companion download link. No ADB or Android device is required.
