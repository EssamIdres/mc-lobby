# MC Server (mc-lobby)

Runs a free Minecraft (Paper) server on GitHub Actions.

## Setup (one time)

1. In repo **Settings → Secrets and variables → Actions → New repository secret**:
   - `PLAYIT_SECRET` — your playit.gg agent secret (`secret_key` from your playit.toml)
   - `RCLONE_CONFIG` — your rclone config file contents (Google Drive remote named `mcworlds`)
2. Go to the **Actions** tab and click **Run workflow** (or push to `main`).

## How it works

- Fresh world every run (new world).
- Backs up the world to Google Drive (`mcworlds:minecraft/mc-lobby/backups/`) **every 1 minute**.
- Keeps only the **3 newest** backups; the oldest is deleted automatically.
- Server stops after 6 hours (GitHub limit). Re-run the workflow to start again.
- Find your server IP in the workflow log (playit tunnel address).

## Config

- `server.properties` — MOTD, max players, etc.
- `run.sh` — server + backup logic. Set `JAVA_HEAP` if you want more/less RAM (default 10G).
