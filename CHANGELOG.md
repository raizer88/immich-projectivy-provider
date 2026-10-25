# Changelog

## 0.0.4

Thanks to @WildFireFlum (PR #4) for the full-resolution support, the server-side random sampling, the `asset.download` permission check, and the boolean-toggle fix.

- New "Photo quality" selector (3 modes): **Preview (max compatibility, default)**, **Full resolution — JPEG/PNG only**, and **Full resolution — all formats**. HEIC/RAW originals fall back to the JPEG preview unless "all formats" is chosen.
- Wallpapers are now served as `content://` URIs backed by the plugin's own cache: the API key stays in the plugin process instead of travelling in the wallpaper URL's query string (readable in Projectivy's cache and in reverse-proxy access logs).
- Cached files are keyed by quality and stored with their real extension/MIME type, so switching quality never serves stale previews and PNG originals are served correctly.
- Assets archived or hidden in Immich are excluded from the rotation by default; a new "Include archived & hidden" toggle opts back in (e.g. for a dedicated wallpaper archive). The locked folder stays excluded either way.
- The binder service now rejects calls from any app other than Projectivy, and `allowBackup` is disabled so the API key is not in platform backups.
- Album and Favorites sources now draw a random sample from the server on every refresh (`/api/search/random`) instead of always listing the newest files.
- Fix boolean settings ("Trust self-signed certificates", "Landscape photos only") not unchecking: they now use real checkboxes instead of single-member radio groups.
- Test connection warns when the API key lacks the `asset.download` permission (checked only when a full-resolution mode is selected).
- Non-decodable originals are skipped: in "Full resolution — JPEG/PNG only", only JPEG/PNG are served as originals; everything else (HEIC, RAW, etc.) uses the JPEG preview.

## 0.0.3

- Allow selecting multiple albums: the album picker is now multi-select with a "Save selection" action, the album source searches all selected albums, and an existing single-album choice is migrated automatically.

## 0.0.2

- Allow unencrypted HTTP connections (`android:usesCleartextTraffic="true"`) so the plugin can talk to a local Immich server over `http://` (thanks to @WildFireFlum, PR #1).
- Warn before saving an unencrypted `http://` server URL: the Immich API key travels in the wallpaper URLs' query string, so over HTTP it would be readable by anyone on the network. The user can accept the risk or cancel.

## 0.0.1

- Initial versioned release: direct Immich URLs with the `apiKey` query parameter, landscape-only photo filter, versioned APK naming.
