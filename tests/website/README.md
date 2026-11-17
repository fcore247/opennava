# Virtual companion browser tests

This optional suite checks the companion and embedded TV launcher together in Chromium. It uses the published website by default. It requires Node.js 20 or newer.

```sh
cd tests/website
npm ci
npx playwright install chromium
npm test
```

To test a checkout, run `python3 -m http.server 8000 --directory docs` from the project root. In the tests directory, run `OPENNAVA_URL=http://127.0.0.1:8000/ npm test`. The base URL can also point to another static deployment.

The full launcher runs in a desktop viewport and senior view runs in a narrow viewport. Each starts in a fresh browser context. The suite uses the visible controls to tune the TV, pause and resume video, search the ZDF library, save and delete a programme, and reconnect after a channel change. It checks the TV picture's media state and both screens' visible results. All media and connections stay inside the browser demo. No ADB or Android device is required.
