# Redirect navatv.site to OpenNava

Visitors to `navatv.site` and `www.navatv.site` receive a permanent HTTP 308 redirect to `https://open-nava.com`. The destination keeps the path and query string, so an existing link to a particular launcher profile still works. The canonical website has its own [GitHub Pages source](../../docs/).

| File | Purpose |
| --- | --- |
| [vercel.json](vercel.json) | Production redirects for the old domain. The static output directory is `docs`. |
| [docs/index.html](docs/index.html) and [docs/404.html](docs/404.html) | Static hosting fallback for the root and nested paths. JavaScript preserves the path, query and fragment. The no-script link goes to the new homepage. |
| [docs/CNAME](docs/CNAME) | Custom domain for the standalone GitHub Pages fallback. |

The [standalone redirect repository](https://github.com/fcore247/navatv) also contains the deployment files. To deploy from this monorepo on Vercel, select `hosting/legacy-redirect` as the project's root directory and attach the two old hostnames to that project. Use the DNS records the host supplies at the registrar. When deploying through the standalone checkout, copy the reviewed `vercel.json` and `docs/` contents there.

Check the redirect and the destination separately:

```sh
curl -sSI 'https://navatv.site/?profile=senior'
curl -sSI 'https://www.navatv.site/demo/senior/index.html?from=old'
curl -fsSI 'https://open-nava.com/'
```

The first two responses should be 308 with a `Location` on `open-nava.com`; the final request should be 200.
