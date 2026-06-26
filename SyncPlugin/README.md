# CloudStream Sync Plugin

CloudStream 3 plugin for cross-device synchronization. Pairs with [CloudStream Sync Server](../cs-sync-server/).

## Features

- **Repository sync** — syncs extension repository URLs across devices
- **Bookmarks** — syncs favorites
- **Resume watching** — syncs playback position and progress
- **Search history** — syncs recent searches
- **Settings** — syncs app preferences

## How It Works

1. **Backup** — reads SharedPreferences, serializes to sorted JSON, pushes to server
2. **Restore** — pulls from server, writes to SharedPreferences, fires reload events
3. **Merge** — timestamp-based conflict resolution (newest wins per key)
4. **Polling** — checks for changes every 30 seconds
5. **Lifecycle** — syncs on app resume (pull) and app exit (push)

## Setup

1. Deploy the [sync server](../cs-sync-server/)
2. In CloudStream: Settings → Extensions → CloudStream Sync
3. Click the gear icon, enter:
   - **Server URL** — your server address (e.g. `https://sync.example.com`)
   - **Token** — any unique string (auto-registers if server allows open registration)
   - **Device name** — friendly name for this device
4. Toggle "Backup" and/or "Restore"
5. Select which categories to sync

## Build

```bash
./gradlew SyncPlugin:make
```

Output: `SyncPlugin/build/outputs/plugin/cloudsync.cs3`

## Architecture

```
SyncPlugin/
  src/main/kotlin/com/lagradost/sync/
    CloudSyncPlugin.kt   # Plugin entry: polling, push/pull/merge, lifecycle
    SyncNetwork.kt       # HTTP client, gzip, Gson parsing
    SyncBackup.kt        # Backup/restore, key classification, merge logic
    SyncStorage.kt       # Credentials + per-category state (timestamps, hashes)
    SyncSettings.kt      # Bottom sheet UI (BottomSheetDialog)
    SyncData.kt          # Data classes (Gson @SerializedName)
  src/main/res/
    layout/              # settings.xml, sync_cat_row.xml, sync_device.xml, sync_creds.xml
    drawable/            # icons
```
