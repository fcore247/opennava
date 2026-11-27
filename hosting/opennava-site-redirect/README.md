# Redirect opennava.site to open-nava.com

Static GitHub Pages site that takes over `opennava.site` after the main site has moved to `open-nava.com`.
It keeps path, query and fragment, so links built into released apps (such as `/handy/`) keep working.

1. Check that `open-nava.com` and `www.open-nava.com` point to GitHub Pages.
2. Set the main repository's custom domain to `open-nava.com` (merge the change of `docs/CNAME`). This frees `opennava.site`.
3. Copy `docs/` into its own public repository, publish it with GitHub Pages from `main:/docs` and enforce HTTPS.

The DNS records of `opennava.site` already point to GitHub Pages and do not need to change.
