# Immich Wallpaper Provider per Projectivy Launcher

Plugin **wallpaper provider** per [Projectivy Launcher](https://play.google.com/store/apps/details?id=com.spocky.projengmenu)
che mostra le foto del tuo server [Immich](https://immich.app) self-hosted come sfondo del launcher
su Android TV / Fire TV (FireOS 7.x, Android 9, userspace a 32 bit).

## Perché serve un plugin dedicato

Projectivy scarica da sé l'URL del wallpaper e **non può inviare header personalizzati**.
Gli URL degli asset di Immich richiedono l'header `x-api-key`, quindi né Aerial Views né
Immich-Android-TV riescono a usare Immich come *wallpaper* (lo fanno solo come *screensaver*).

Questo plugin risolve il problema scaricando **lui stesso** le foto con la API key e
servendole a Projectivy come file locali tramite un `ContentProvider` (vedi sotto).

## Come funziona

1. Projectivy si collega al servizio del plugin e chiama `getWallpapers()`.
2. Il plugin interroga Immich (header `x-api-key`) per ottenere un elenco di asset
   (album scelto / preferiti / recenti / casuali).
3. Scarica la versione **`preview`** di ogni foto (non l'originale da molti MB) e la salva
   nella cache su disco dell'app.
4. Restituisce a Projectivy una lista di `content://` URI serviti da un `ContentProvider`
   locale; Projectivy li ruota col **suo** intervallo.

### Perché `content://` e non `file://` o URL diretti

| Opzione | Problema |
|---|---|
| URL Immich diretto | richiede `x-api-key`, che Projectivy non può mandare |
| `file://` nella cache dell'app | la cache è privata dell'app; un'altra app (Projectivy) non può leggerla (SELinux/UID) |
| `androidx FileProvider` | rifiuta di essere esportato e richiede grant via intent, che Projectivy non fa |
| **`content://` da un ContentProvider custom esportato** | funziona via `ContentResolver`, esattamente come lo schema `android.resource://` usato dall'esempio ufficiale |

**Trade-off documentato**: il `ContentProvider` è esportato (`exported=true`), quindi
qualsiasi app sul device può leggere le foto in cache. Sono **solo i file JPEG in cache**,
mai la API key (che resta nelle `SharedPreferences` dell'app).

## Requisiti

- **Projectivy Launcher Premium** (necessario per i plugin).
- Un server **Immich** (v3) raggiungibile dal Fire TV sulla rete locale.
- Una **API key Immich** con questi permessi: **`album.read`**, **`asset.read`**,
  **`asset.view`**, **`asset.download`**.

### Creare la API key

1. Immich → Impostazioni utente → **API Keys** → *New API Key*.
2. Concedi i permessi: `album.read`, `asset.read`, `asset.view`, `asset.download`.
3. Copia la chiave; servirà nella schermata di configurazione del plugin.

## Installazione

Compila con GitHub Actions (vedi sotto) oppure scarica l'APK dalla release:

```
https://github.com/<TUO-UTENTE>/<TUO-REPO>/releases/latest/download/immich-provider-debug.apk
```

Installa sul Fire TV:

```
adb install -r immich-provider-debug.apk
```

Poi, in Projectivy: **Impostazioni → Aspetto → Wallpaper → Plugin → Immich Wallpaper Provider → Impostazioni**.

## Impostazioni

| Campo | Significato |
|---|---|
| **Server URL** | es. `https://immich.example.com` oppure `http://192.168.1.10:2283` (senza `/api`) |
| **API key** | mascherata a schermo (`••••abcd`); modificabile |
| **Test connection** | verifica URL + chiave; elenca il numero di album e carica l'elenco album |
| **Sorgente** | Recenti / Preferiti / Casuali / Album |
| **Choose album** | apre l'elenco album (richiede *Test connection* o una connessione valida) |
| **Images to cache** | quante foto restituire a Projectivy a ogni refresh (default 20) |
| **Seconds per image** | vedi sotto — **solo indicativo** |
| **Cache size limit (MB)** | limite di spazio su disco per le anteprime (default 200 MB) |
| **Trust self-signed certificates** | abilita solo se il tuo server usa un certificato autofirmato |

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
- `GET  /api/assets/{id}/thumbnail?size=preview` — anteprima (JPEG) per lo sfondo

Autenticazione: header **`x-api-key`** su ogni richiesta. Parsing con `org.json` (nessuna
dipendenza extra). HTTP con **OkHttp** (puro Java/Kotlin, nessuna libreria nativa `.so` →
compatibile con l'userspace a 32 bit del Fire TV).

## Errori comuni

| Errore | Causa probabile |
|---|---|
| `Invalid API key (401)` | chiave errata o revocata |
| `API key lacks permission (403)` | manca uno dei permessi `album.read` / `asset.read` / `asset.view` |
| `Not found (404)` | URL del server errato |
| `Cannot reach server` | server spento / rete / porta sbagliata |
| handshake SSL fallito | certificato autofirmato → attiva *Trust self-signed certificates* |

## Compilazione

### GitHub Actions (consigliato — nessun SDK richiesto in locale)

Il workflow `.github/workflows/build.yml`:

1. imposta **JDK 17** e **Gradle 8.9** (`gradle/actions/setup-gradle`);
2. compila un APK firmato in **debug** (`:app:assembleDebug` — si installa senza keystore);
3. lo pubblica come **artifact del workflow** e come **asset di una GitHub Release**.

Su ogni push al ramo principale crea/aggiorna una release **`latest`**; su un tag `v*` crea
una release col nome del tag. Il link diretto è quindi:

```
https://github.com/<utente>/<repo>/releases/latest/download/immich-provider-debug.apk
```

### In locale

Serve JDK 17 + Android SDK (platform 35). Poi:

```
./gradlew :app:assembleDebug
```

## Note e limiti noti

- **Nessuna prova su dispositivo**: questo codice è stato scritto senza poterlo compilare o
  eseguire su Fire TV; il primo test reale lo fa l'utente. Verificare soprattutto:
  1. che Projectivy accetti `content://` URI per il tipo `IMAGE` (atteso: sì, come da
     `android.resource://` dell'esempio; in caso negativo servirebbe un piccolo server HTTP
     locale incorporato nel plugin o i link di condivisione pubblici di Immich);
  2. il flusso di configurazione con il telecomando (input testuale via IME leanback).
- **`itemsCacheDurationMillis` = 30 min**: statico (dal manifest), non modificabile a runtime.
- La **API key è esportata in chiaro** da `getPreferences()` (per il backup/ripristino delle
  impostazioni di Projectivy) e nelle `SharedPreferences` del device: stesso livello di
  esposizione di qualsiasi app che salva una chiave. Il repo è pubblico: **nessun URL del
  server né API key nel codice**.
- Il device è debole: il download delle anteprime è sequenziale con timeout brevi e la cache
  su disco ha un limite configurabile; su rete lenta il primo `getWallpapers()` può richiedere
  qualche secondo (le chiamate successive colpiscono la cache).
