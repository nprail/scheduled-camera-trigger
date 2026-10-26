# Kairos website

A one-page static site: `index.html`, styled with [Tailwind](https://tailwindcss.com) (loaded from its CDN, version pinned in the page) and the Kairos brand tokens. There is no build step.

Preview it locally:

```sh
cd site
python3 -m http.server 8000
```

Then open http://localhost:8000. It follows the system light and dark setting.

- Docs: the documentation lives in `docs/` and is built with docmd into `website/docs/` (see `docmd.config.json` at the repo root). Run `npm ci && npm run build` at the repo root first if you want `/docs/` locally. GitHub Actions does this on deploy.
- Brand: colors, type and logo rules are in [brand/README.md](../brand/README.md). The page uses `signal` once per section, and borders instead of shadows.
- Fonts (Space Grotesk, IBM Plex Sans, JetBrains Mono) load from Google Fonts.
- The app screenshots in `img/` come from the apps' demo mode (the `recording` scene).
- Links to `docs/` assume the docs are on the `main` branch of the repository.
