> 📖 This README is available in **English** (below) and in **Italiano** (scroll to "Versione italiana").
> 📖 Questo README è disponibile in **inglese** (qui sotto) e in **italiano** (in fondo, sezione "Versione italiana").

# Immich Wallpaper Provider for Projectivy Launcher

**Wallpaper provider** plugin for [Projectivy Launcher](https://play.google.com/store/apps/details?id=com.spocky.projengmenu)
that shows photos from your self-hosted [Immich](https://immich.app) server as the launcher wallpaper
on Android TV / Fire TV (FireOS 7.x, Android 9, 32-bit userspace).

## Why a dedicated plugin is needed

Projectivy downloads the wallpaper URL itself, but it **cannot send custom headers**.
Immich asset URLs normally require the `x-api-key` header, so neither Aerial Views nor
Immich-Android-TV can use Immich as a *wallpaper* (they only work as a *screensaver*).

This plugin solves the problem by **downloading the photos itself** with the `x-api-key` header and
handing Projectivy `content://` URIs instead of URLs. The API key therefore never leaves the plugin's
process: it is not in the wallpaper URIs, not in Projectivy's cache, and not in the access log of any
reverse proxy sitting in front of Immich.

## How it works

1. Projectivy binds to the plugin service and calls `getWallpapers()`.
2. The plugin queries Immich (`x-api-key` header) to obtain a list of assets
   (chosen albums / favorites / recent / random).
3. It downloads each photo into the app's private cache, authenticating with the
   `x-api-key` header: the ~1440 px preview by default, or the original file in a
   full-resolution mode (HEIC/RAW fall back to the preview unless "all formats" is chosen).
4. It returns a list of `content://` URIs served by a **non-exported** `ContentProvider`, granting
   read access on each one to Projectivy's package alone. Projectivy rotates them on its own
   interval, until the list expires after `itemsCacheDurationMillis`.

### Why `content://` and not a URL carrying the key

| Option | Verdict |
|---|---|
| Direct Immich URL without a key | requires `x-api-key`, which Projectivy cannot send |
| Immich URL with `?apiKey=<key>` | works, but puts a credential for the **whole library** into every caller of the binder, into Projectivy's own cache, and into the access log of any reverse proxy in front of Immich |
| `file://` in the app cache | the app cache is private; another app cannot read it |
| `content://` from an **exported** `ContentProvider` | works, but then every app on the device can read the cached photos |
| **`content://` from a non-exported provider + per-URI grant** | ✅ what this plugin does — readable by Projectivy alone |

Projectivy's `IMAGE` loader **does** accept `content://`; this was verified on Projectivy 4.72 /
Android 14 (SDK 34, armeabi-v7a) against Immich 3.3.3, serving 94 wallpapers from an album with no
loader or URI-permission errors.

A grant does not need an `Intent`: `context.grantUriPermission(pkg, uri, FLAG_GRANT_READ_URI_PERMISSION)`
works on any provider declaring `android:grantUriPermissions="true"`, exported or not. That is what
makes a non-exported provider usable here, and it is strictly better than exporting one. Ad-hoc grants
do not survive a reboot, so they are re-issued on every `getWallpapers()` call; since Projectivy
re-asks once `itemsCacheDurationMillis` expires, that self-heals.

Immich API keys cannot be scoped to a single album and have no short-lived variant, which is what
makes keeping the key in-process worth the extra code. The plugin needs `album.read`, `asset.read`
and `asset.view` for previews, plus `asset.download` for full-resolution originals.

## Requirements

- **Projectivy Launcher Premium** (required for plugins).
- An **Immich** server (v3) reachable from the Fire TV on the local network.
- An **Immich API key** with these permissions: **`album.read`**, **`asset.read`**,
  **`asset.view`** (add **`asset.download`** for full-resolution modes).

### Creating the API key

1. Immich → User settings → **API Keys** → *New API Key*.
2. Grant the permissions: `album.read`, `asset.read`, `asset.view` (and `asset.download` for full-resolution modes).
3. Copy the key; you'll need it in the plugin's settings screen.

## Installation

Build with GitHub Actions (see below) or download the APK from the release:

```
https://github.com/<YOUR-USER>/<YOUR-REPO>/releases/latest/download/immich-provider-<version>.apk
```

Install on the Fire TV:

```
adb install -r immich-provider-<version>.apk
```

Then, in Projectivy: **Settings → Appearance → Wallpaper → Plugin → Immich Wallpaper Provider → Settings**.

## Settings

| Field | Meaning |
|---|---|
| **Server URL** | e.g. `https://immich.example.com` or `http://192.168.1.10:2283` (without `/api`) |
| **API key** | masked on screen (`••••abcd`); editable |
| **Test connection** | verifies URL + key; lists the album count and loads the album list |
| **Source** | Recent / Favorites / Random / Album |
| **Choose albums** | opens the album list and lets you select one or more albums (requires *Test connection* or a valid connection) |
| **Images to cache** | how many photos to return to Projectivy on each refresh (default 20) |
| **Seconds per image** | see below — **advisory only** |
| **Trust self-signed certificates** | enable only if your server uses a self-signed certificate |
| **Landscape photos only** | skip portrait photos (they can break Projectivy's UI on some TVs); default: on |
| **Photo quality** | Preview (max compatibility, default) / Full resolution — JPEG/PNG only / Full resolution — all formats. Originals are paid for in device storage: Projectivy caches every wallpaper locally |
| **Include archived & hidden** | off by default: photos archived or hidden in Immich are excluded from the rotation. Enable to use an archive as a dedicated wallpaper source; the locked folder is always excluded server-side |

> **Storage note**: the plugin downloads nothing — Projectivy does, into its own image
> cache. The wallpaper *list* is kept for `itemsCacheDurationMillis` (30 min here), and the
> downloaded files are evicted by **cache size, not by refresh** (capped at ~50 MB since
> Projectivy 4.72; before that the cache could grow unbounded). Original photos from phone
> cameras are 3–10 MB each, so 20 images won't fit in 50 MB: Projectivy then re-downloads
> between rotations. Prefer **Images to cache ≈ 5–10** in a full-resolution mode,
> or stay on Preview if storage churn matters more than sharpness.

> **Self-signed certificate note**: *Trust self-signed certificates* covers **every** request,
> including the wallpaper downloads, because the plugin makes all of them. (This was not true while
> Projectivy downloaded the URLs itself: the setting could not reach that request, so self-signed
> servers got a working "Test connection" and no wallpapers.) Note that an `http://` URL is still
> not an option - with `targetSdk 34` and no `usesCleartextTraffic` declaration, Android blocks
> cleartext by platform default.

## The "seconds per image" question (honest take)

The rotation interval is **governed by Projectivy's timer**, not the plugin. The plugin only
controls:

- **which/how many** photos to return;
- `itemsCacheDurationMillis` (static manifest metadata) = how long Projectivy keeps the
  list before requesting it again.

This plugin's **"Seconds per image"** setting is therefore **advisory**: it is saved and shown, but
it cannot force a rotation faster than the launcher's minimum (**30 seconds** since v4.70). To align
them, set the same value in **Projectivy → Appearance → Wallpaper → change interval** (minimum 30 s).
Below 30 seconds the launcher's minimum still applies. *Nothing is simulated*: the value only takes
effect if aligned with Projectivy's setting.

## Immich APIs used (verified against the OpenAPI v3.x spec)

- `GET  /api/albums` — album list
- `POST /api/search/metadata` — asset search (recent, favorites, album; `filter` + `orderBy` + `size`)
- `POST /api/search/random` — random assets
- `GET  /api/assets/{id}/original` — original file (full-resolution modes)
- `GET  /api/assets/{id}/thumbnail?size=preview` — ~1440 px JPEG preview (Preview mode, and fallback for HEIC/RAW)

Authentication: the **`x-api-key`** header on all four, since the plugin makes every request
itself. Parsing with `org.json` (no extra dependency). HTTP with
**OkHttp** (pure Java/Kotlin, no native `.so` library → compatible with the Fire TV's 32-bit userspace).

## Common errors

| Error | Likely cause |
|---|---|
| `Invalid API key (401)` | wrong or revoked key |
| `API key lacks permission (403)` | missing one of the `album.read` / `asset.read` / `asset.view` / `asset.download` permissions (`asset.download` is required for full-resolution modes) |
| `Not found (404)` | wrong server URL |
| `Cannot reach server` | server off / network / wrong port |
| SSL handshake failed | self-signed certificate → enable *Trust self-signed*; it covers the wallpaper downloads too. `http://` is blocked by platform default (see note above) |

## Building

### GitHub Actions (recommended — no local SDK required)

The `.github/workflows/build.yml` workflow:

1. sets up **JDK 17** and **Gradle 8.9** (`gradle/actions/setup-gradle`);
2. builds a **debug**-signed APK (`:app:assembleDebug` — installs without a keystore);
3. publishes it as a workflow **artifact** and as a **GitHub Release** asset.

On every push to the main branch it creates/updates a **`latest`** release; on a `v*` tag it creates
a release named after the tag. The direct link is therefore:

```
https://github.com/<user>/<repo>/releases/latest/download/immich-provider-<version>.apk
```

### Locally

You need JDK 17 + Android SDK (platform 35). Then:

```
./gradlew :app:assembleDebug
```

## Known notes and limitations

- **Portrait wallpapers**: on some TVs a non-landscape wallpaper makes Projectivy mis-render its
  whole UI (garbled clock, icon trails). The plugin therefore returns landscape/square photos only
  by default ("Landscape photos only"); you can disable it in the settings.
- **`itemsCacheDurationMillis` = 30 min**: static (from the manifest), not changeable at runtime.
  With this value Projectivy reuses the same photo list for 30 minutes before requesting it again
  from the plugin.
- The **API key is exported in plain text** by `getPreferences()` (for Projectivy's settings
  backup/restore), in the device's `SharedPreferences` and in the wallpaper URLs' query string:
  the same exposure level as any app that stores a key.
- The device is weak: the asset search is a single fast call; the preview download is done by
  Projectivy with its own loader and cache.

---

# Immich Wallpaper Provider per Projectivy Launcher

Plugin **wallpaper provider** per [Projectivy Launcher](https://play.google.com/store/apps/details?id=com.spocky.projengmenu)
che mostra le foto del tuo server [Immich](https://immich.app) self-hosted come sfondo del launcher
su Android TV / Fire TV (FireOS 7.x, Android 9, userspace a 32 bit).

## Perché serve un plugin dedicato

Projectivy scarica da sé l'URL del wallpaper, ma **non può inviare header personalizzati**.
Gli URL degli asset di Immich richiedono normalmente l'header `x-api-key`, quindi né Aerial Views né
Immich-Android-TV riescono a usare Immich come *wallpaper* (lo fanno solo come *screensaver*).

Questo plugin risolve il problema **scaricando lui stesso le foto** con l'header `x-api-key` e
passando a Projectivy degli URI `content://` invece di URL. L'API key non esce quindi mai dal processo
del plugin: non sta negli URI dei wallpaper, non sta nella cache di Projectivy e non finisce nel log
degli accessi di un eventuale reverse proxy davanti a Immich.

## Come funziona

1. Projectivy si collega al servizio del plugin e chiama `getWallpapers()`.
2. Il plugin interroga Immich (header `x-api-key`) per ottenere un elenco di asset
   (album scelti / preferiti / recenti / casuali).
3. Scarica ogni foto nella cache privata dell'app, autenticandosi con l'header `x-api-key`:
   l'anteprima ~1440 px di default, o il file originale in una modalità a piena risoluzione
   (HEIC/RAW ricadono sull'anteprima a meno che non sia scelta "tutti i formati").
4. Restituisce una lista di URI `content://` serviti da un `ContentProvider` **non esportato**,
   concedendo il permesso di lettura su ciascuno al solo package di Projectivy. Projectivy li ruota
   col **suo** intervallo, finché la lista scade dopo `itemsCacheDurationMillis`.

### Perché `content://` e non un URL che porta la chiave

| Opzione | Verdetto |
|---|---|
| URL Immich diretto senza chiave | richiede `x-api-key`, che Projectivy non può mandare |
| URL Immich con `?apiKey=<chiave>` | funziona, ma mette una credenziale valida per **tutta la libreria** in ogni chiamante del binder, nella cache di Projectivy e nel log degli accessi di un eventuale reverse proxy davanti a Immich |
| `file://` nella cache dell'app | la cache è privata dell'app; un'altra app non può leggerla |
| `content://` da un `ContentProvider` **esportato** | funziona, ma così ogni app sul dispositivo può leggere le foto in cache |
| **`content://` da un provider non esportato + grant per URI** | ✅ quello che fa questo plugin — leggibile solo da Projectivy |

Il loader `IMAGE` di Projectivy **accetta** `content://`: verificato su Projectivy 4.72 /
Android 14 (SDK 34, armeabi-v7a) con Immich 3.3.3, servendo 94 wallpaper da un album senza alcun
errore del loader né di permessi sugli URI.

Un grant **non** richiede un `Intent`: `context.grantUriPermission(pkg, uri, FLAG_GRANT_READ_URI_PERMISSION)`
funziona su qualsiasi provider che dichiari `android:grantUriPermissions="true"`, esportato o no. È
questo che rende utilizzabile un provider non esportato, ed è strettamente meglio che esportarne uno.
I grant ad-hoc non sopravvivono a un riavvio, quindi vengono riemessi a ogni chiamata di
`getWallpapers()`; dato che Projectivy richiede di nuovo la lista alla scadenza di
`itemsCacheDurationMillis`, la cosa si ripara da sé.

Le API key di Immich non possono essere limitate a un singolo album e non esistono in versione a
scadenza breve: è questo che rende la chiave-in-processo degna del codice in più. Al plugin servono
`album.read`, `asset.read` e `asset.view` per le anteprime, più `asset.download` per gli originali a piena risoluzione.

## Requisiti

- **Projectivy Launcher Premium** (necessario per i plugin).
- Un server **Immich** (v3) raggiungibile dal Fire TV sulla rete locale.
- Una **API key Immich** con questi permessi: **`album.read`**, **`asset.read`**,
  **`asset.view`** (aggiungi **`asset.download`** per le modalità a piena risoluzione).

### Creare la API key

1. Immich → Impostazioni utente → **API Keys** → *New API Key*.
2. Concedi i permessi: `album.read`, `asset.read`, `asset.view` (e `asset.download` per le modalità a piena risoluzione).
3. Copia la chiave; servirà nella schermata di configurazione del plugin.

## Installazione

Compila con GitHub Actions (vedi sotto) oppure scarica l'APK dalla release:

```
https://github.com/<TUO-UTENTE>/<TUO-REPO>/releases/latest/download/immich-provider-<version>.apk
```

Installa sul Fire TV:

```
adb install -r immich-provider-<version>.apk
```

Poi, in Projectivy: **Impostazioni → Aspetto → Wallpaper → Plugin → Immich Wallpaper Provider → Impostazioni**.

## Impostazioni

| Campo | Significato |
|---|---|
| **Server URL** | es. `https://immich.example.com` oppure `http://192.168.1.10:2283` (senza `/api`) |
| **API key** | mascherata a schermo (`••••abcd`); modificabile |
| **Test connection** | verifica URL + chiave; elenca il numero di album e carica l'elenco album |
| **Sorgente** | Recenti / Preferiti / Casuali / Album |
| **Choose albums** | apre l'elenco album e ti fa selezionare uno o più album (richiede *Test connection* o una connessione valida) |
| **Images to cache** | quante foto restituire a Projectivy a ogni refresh (default 20) |
| **Seconds per image** | vedi sotto — **solo indicativo** |
| **Trust self-signed certificates** | abilita solo se il tuo server usa un certificato autofirmato |
| **Landscape photos only** | salta le foto verticali (possono rompere la UI di Projectivy su alcune TV); default: attivo |
| **Qualità foto** | Preview (massima compatibilità, default) / Full resolution — solo JPEG/PNG / Full resolution — tutti i formati. Gli originali si pagano in spazio di archiviazione: Projectivy mette in cache ogni sfondo sul dispositivo |
| **Includi archiviate e nascoste** | disattivato per default: le foto archiviate o nascoste in Immich sono escluse dalla rotazione. Attivalo se usi un archivio come fonte dedicata di sfondi; la cartella bloccata resta sempre esclusa lato server |

> **Nota di spazio**: il plugin non scarica nulla — scarica Projectivy, nella **sua** cache
> immagini. La *lista* di sfondi resta per `itemsCacheDurationMillis` (qui 30 min), mentre i
> file scaricati vengono espulsi per **dimensione cache, non a ogni refresh** (limite ~50 MB
> da Projectivy 4.72; prima la cache poteva crescere senza limite). Le foto originali delle
> fotocamere via telefono pesano 3–10 MB ciascuna: 20 immagini non entrano in 50 MB e
> Projectivy le ri-scaricherà tra una rotazione e l'altra. In una modalità a piena risoluzione
> conviene tenere **Immagini da mettere in cache ≈ 5–10**, oppure restare su Preview se lo
> spazio conta più della nitidezza.

> **Nota sul certificato autofirmato**: la voce *Trust self-signed certificates* agisce sul
> client HTTP **del plugin**, e quindi su **tutte** le richieste, download dei wallpaper
> compreso. Con un server
> HTTPS autofirmato i wallpaper si caricano regolarmente, perché tutte le richieste le fa il
> plugin. (Non era così finché gli URL li scaricava Projectivy: l'impostazione non poteva
> raggiungere quella richiesta, quindi con un certificato autofirmato "Test connection" funzionava
> e i wallpaper no.) Un URL `http://` resta comunque escluso: con `targetSdk 34` e senza dichiarare
> `usesCleartextTraffic`, Android blocca il traffico in chiaro per default.

## La questione dei "secondi per immagine" (lettura onesta)

L'intervallo di rotazione **lo governa il timer di Projectivy**, non il plugin.
Il plugin controlla solo:

- **quali/quante** foto restituire;
- `itemsCacheDurationMillis` (metadato statico nel manifest) = quanto Projectivy tiene la
  lista prima di richiederla di nuovo.

L'impostazione **"Seconds per image"** di questo plugin è quindi **advisory**: viene salvata
e mostrata, ma non può forzare una rotazione più veloce del minimo del launcher
(**30 secondi** dalla v4.70). Per farla combaciare, imposta lo stesso valore in
**Projectivy → Aspetto → Wallpaper → intervallo di cambio** (minimo 30 s). Sotto i 30 secondi
vale comunque il minimo del launcher. *Non viene simulato nulla*: il valore ha effetto solo
se allineato all'impostazione di Projectivy.

## API Immich usate (verificate sullo spec OpenAPI v3.x)

- `GET  /api/albums` — elenco album
- `POST /api/search/metadata` — ricerca asset (recenti, preferiti, album; filtro `filter` + `orderBy` + `size`)
- `POST /api/search/random` — asset casuali
- `GET  /api/assets/{id}/original` — file originale (modalità a piena risoluzione)
- `GET  /api/assets/{id}/thumbnail?size=preview` — anteprima JPEG ~1440 px (modalità Preview, e fallback per HEIC/RAW)

Autenticazione: header **`x-api-key`** su tutte e quattro, dato che ogni richiesta la fa il
plugin stesso. Parsing con `org.json` (nessuna dipendenza
extra). HTTP con **OkHttp** (puro Java/Kotlin, nessuna libreria nativa `.so` → compatibile con
l'userspace a 32 bit del Fire TV).

## Errori comuni

| Errore | Causa probabile |
|---|---|
| `Invalid API key (401)` | chiave errata o revocata |
| `API key lacks permission (403)` | manca uno dei permessi `album.read` / `asset.read` / `asset.view` / `asset.download` (`asset.download` serve per le modalità a piena risoluzione) |
| `Not found (404)` | URL del server errato |
| `Cannot reach server` | server spento / rete / porta sbagliata |
| handshake SSL fallito | certificato autofirmato → attiva *Trust self-signed*; copre anche il download dei wallpaper. `http://` è bloccato per default dalla piattaforma (vedi nota sopra) |

## Compilazione

### GitHub Actions (consigliato — nessun SDK richiesto in locale)

Il workflow `.github/workflows/build.yml`:

1. imposta **JDK 17** e **Gradle 8.9** (`gradle/actions/setup-gradle`);
2. compila un APK firmato in **debug** (`:app:assembleDebug` — si installa senza keystore);
3. lo pubblica come **artifact del workflow** e come **asset di una GitHub Release**.

Su ogni push al ramo principale crea/aggiorna una release **`latest`**; su un tag `v*` crea
una release col nome del tag. Il link diretto è quindi:

```
https://github.com/<utente>/<repo>/releases/latest/download/immich-provider-<version>.apk
```

### In locale

Serve JDK 17 + Android SDK (platform 35). Poi:

```
./gradlew :app:assembleDebug
```

## Note e limiti noti

- **Wallpaper verticali**: su alcune TV un wallpaper non orizzontale fa disegnare male l'intera UI
  di Projectivy (orologio sfasato, scie sulle icone). Per questo il plugin restituisce di default
  solo foto orizzontali/quadrate ("Landscape photos only"); puoi disattivarlo nelle impostazioni.
- **`itemsCacheDurationMillis` = 30 min**: statico (dal manifest), non modificabile a runtime.
  Con questo valore Projectivy riusa la stessa lista di foto per 30 minuti prima di chiederla
  di nuovo al plugin.
- La **API key è esportata in chiaro** da `getPreferences()` (per il backup/ripristino delle
  impostazioni di Projectivy), nelle `SharedPreferences` del device e nella query string degli
  URL dei wallpaper: stesso livello di esposizione di qualsiasi app che salva una chiave.
- Il device è debole: la ricerca asset è un'unica chiamata veloce; il download delle anteprime
  lo fa Projectivy col proprio loader e la propria cache.
