#!/bin/bash
set -e

SERVER_NAME="${SERVER_NAME:-$(basename "$(pwd)")}"
DRIVE_ROOT="mcworlds:minecraft/$SERVER_NAME"
JAVA_HEAP="${JAVA_HEAP:-10G}"
BACKUP_KEEP=3
BACKUP_INTERVAL=3600
PAPER_VERSION="${PAPER_VERSION:-1.21.1}"
VELOCITY_VERSION="${VELOCITY_VERSION:-3.5.1}"
VELOCITY_BUILD="${VELOCITY_BUILD:-615}"

mkdir -p ~/.config/rclone

echo "==> Server: $SERVER_NAME"
echo "==> Drive root: $DRIVE_ROOT"

# EULA
echo "eula=true" > eula.txt

# Download Paper if not present
if [ ! -f server.jar ]; then
  echo "==> Downloading Paper $PAPER_VERSION..."
  curl -fsSL -H "User-Agent: mc-bot (https://github.com/EssamIdres)" "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds" > /tmp/builds.json
  URL=$(jq -r 'first(.[] | select(.channel == "STABLE") | .downloads."server:default".url)' /tmp/builds.json)
  echo "==> Downloading jar: $URL"
  curl -fsSL -o server.jar "$URL"
fi

# Start playit tunnel
if [ -n "$PLAYIT_SECRET" ]; then
  echo "==> Starting playit tunnel..."
  curl -fsSL -o playit https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-amd64
  chmod +x playit
  printf 'secret_key = "%s"\n' "$PLAYIT_SECRET" > playit.toml
  ./playit --socket-path="$PWD/playitd.sock" --secret-path="$PWD/playit.toml" > playit.log 2>&1 &
  sleep 10
  echo "==> Playit log (find your tunnel address here):"
  cat playit.log
fi

# Velocity proxy (only on the proxy host, e.g. mc-lobby)
if [ "$PROXY_HOST" = "true" ]; then
  echo "==> Setting up Velocity proxy..."
  if [ ! -f velocity.jar ]; then
    VEL_URL=$(curl -fsSL -H "User-Agent: mc-bot (https://github.com/EssamIdres)" \
      "https://fill.papermc.io/v3/projects/velocity/versions/${VELOCITY_VERSION}/builds/${VELOCITY_BUILD}" | \
      jq -r '.downloads."server:default".url')
    echo "==> Downloading Velocity: $VEL_URL"
    curl -fsSL -o velocity.jar "$VEL_URL"
  fi

  if [ -z "$VELOCITY_SECRET" ]; then
    echo "==> ERROR: VELOCITY_SECRET not set. Cannot start proxy."
    exit 1
  fi
  printf '%s' "$VELOCITY_SECRET" > forwarding.secret

  cat > velocity.toml <<'EOF'
config-version = "2.8"
bind = "0.0.0.0:25565"
motd = "<#09add3>MC Network"
show-max-players = 100
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"
enable-player-address-logging = true

[servers]
lobby = "127.0.0.1:25566"
survival = "tissues-economy.tun.ply.gg:25565"
minigames = "tissues-heard.tun.ply.gg:25565"
creative = "tissues-individuals.tun.ply.gg:25565"

try = ["lobby"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false
enable-reuse-port = false
command-rate-limit = 50
forward-commands-if-rate-limited = true
kick-after-rate-limited-commands = 0
tab-complete-rate-limit = 10
kick-after-rate-limited-tab-completes = 0

[query]
enabled = false
port = 25565
map = "Velocity"
show-plugins = false
EOF

  # Lobby Paper backend must listen on 25566 (internal), proxy takes 25565
  sed -i 's/^server-port=.*/server-port=25566/' server.properties

  echo "==> Starting Velocity proxy..."
  java -Xms512M -Xmx1G -jar velocity.jar > velocity.log 2>&1 &
  VELOCITY_PID=$!
  echo "==> Velocity PID: $VELOCITY_PID"
  sleep 10
  if kill -0 $VELOCITY_PID 2>/dev/null; then
    echo "==> Velocity is UP"
  else
    echo "==> VELOCITY DIED. velocity.log:"
    tail -50 velocity.log
    exit 1
  fi
  echo "==> velocity.log:"
  tail -20 velocity.log
fi

# Configure Velocity modern forwarding on the Paper backend (only if proxy is enabled)
if [ "$PROXY_HOST" = "true" ] && [ -n "$VELOCITY_SECRET" ]; then
  echo "==> Configuring Velocity forwarding for Paper (proxy ON)..."
  mkdir -p config
  cat > config/paper-global.yml <<EOF
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "$VELOCITY_SECRET"
EOF
else
  echo "==> Velocity forwarding DISABLED (direct join, no proxy)..."
  mkdir -p config
  cat > config/paper-global.yml <<EOF
proxies:
  velocity:
    enabled: false
EOF
  # Also ensure server doesn't require proxy forwarding
  rm -f config/paper-global.yml 2>/dev/null || true
  mkdir -p config
  echo "proxies: { velocity: { enabled: false } }" > config/paper-global.yml
fi

# Restore latest backup (persistent world) if one exists
if rclone lsf "$DRIVE_ROOT/backups" --dirs-only 2>/dev/null | grep -q .; then
  NEWEST=$(rclone lsf "$DRIVE_ROOT/backups" --dirs-only 2>/dev/null | sort -r | head -1)
  echo "==> Restoring latest backup: $NEWEST"
  rclone copy "$DRIVE_ROOT/backups/$NEWEST/" . 2>&1 | tail -3 || true
else
  echo "==> No backups found — generating a fresh world."
fi

# Debug plugins before restore
echo "==> Plugins BEFORE restore:"
ls -lh plugins/ 2>&1 | head -30
# Re-build CustomBlockGUI after restore (restore may have overwritten with old backup) + ensure Oraxen/ProtocolLib present
if [ -f plugin-src/CustomBlockGUI/pom.xml ]; then
  echo "==> Re-building CustomBlockGUI after restore (ensure latest)..."
  (cd plugin-src/CustomBlockGUI && mvn -B package -DskipTests 2>&1 | tail -20 && mkdir -p ../../plugins && cp -f target/CustomBlockGUI-*.jar ../../plugins/ && ls -lh ../../plugins/CustomBlockGUI*.jar && echo "Rebuild success")
else
  echo "==> plugin-src not found, skipping rebuild"
fi
# Restore Oraxen pack & settings from repo (restore overwrote with old backup without DiscoveryLab textures)
echo "==> Restoring Oraxen pack from repo (for texture-only)..."
git checkout -- plugins/Oraxen/settings.yml plugins/Oraxen/pack/ 2>&1 | head -20 || echo "git checkout Oraxen failed, trying restore"
echo "==> Plugins AFTER restore+rebuild:"
ls -lh plugins/ 2>&1 | head -30
echo "==> Plugins folder contents (detailed):"
ls -lh plugins/*.jar 2>&1 | head -20
# Also check for paper remapped plugins
ls -lh .paper-remapped/ 2>&1 | head -20 || echo "no paper-remapped yet"
# Ensure DiscoveryLab assets are in Oraxen pack (if missing, copy from repo zip)
if [ ! -f plugins/Oraxen/pack/assets/minecraft/models/item/smithing_table.json ]; then
  echo "==> Oraxen smithing_table.json missing, copying from DiscoveryLab pack..."
  mkdir -p plugins/Oraxen/pack/assets/minecraft/models/item
  unzip -o -q DiscoveryLab-pack-1.21.1.zip -d /tmp/oraxen_restore 2>&1 | head -5 || true
  # Actually DiscoveryLab-pack-1.21.1.zip is at repo root, contains DiscoveryLab-pack folder
  if [ -d /tmp/oraxen_restore/DiscoveryLab-pack/assets ]; then
    cp -r /tmp/oraxen_restore/DiscoveryLab-pack/assets/minecraft/* plugins/Oraxen/pack/assets/minecraft/ 2>&1 | head -10
  fi
  # Also ensure our custom smithing_table.json with 15 overrides is there
  if [ ! -f plugins/Oraxen/pack/assets/minecraft/models/item/smithing_table.json ]; then
    echo "==> Creating smithing_table.json for 15 machines..."
    mkdir -p plugins/Oraxen/pack/assets/minecraft/models/item
    cat > plugins/Oraxen/pack/assets/minecraft/models/item/smithing_table.json <<'JSON'
{
  "parent": "item/generated",
  "textures": {"layer0": "item/smithing_table"},
  "overrides": [
    {"predicate": {"custom_model_data": 1001}, "model": "block/altar"},
    {"predicate": {"custom_model_data": 1002}, "model": "block/autocrafter"},
    {"predicate": {"custom_model_data": 1003}, "model": "block/belt_machine"},
    {"predicate": {"custom_model_data": 1004}, "model": "block/crusher"},
    {"predicate": {"custom_model_data": 1005}, "model": "block/drill"},
    {"predicate": {"custom_model_data": 1006}, "model": "block/druglab"},
    {"predicate": {"custom_model_data": 1007}, "model": "block/generator"},
    {"predicate": {"custom_model_data": 1008}, "model": "block/packaging"},
    {"predicate": {"custom_model_data": 1009}, "model": "block/pipe"},
    {"predicate": {"custom_model_data": 1010}, "model": "block/press"},
    {"predicate": {"custom_model_data": 1011}, "model": "block/sorter"},
    {"predicate": {"custom_model_data": 1012}, "model": "block/spawnercore"},
    {"predicate": {"custom_model_data": 1013}, "model": "block/splitter"},
    {"predicate": {"custom_model_data": 1014}, "model": "block/totem_machine"},
    {"predicate": {"custom_model_data": 1015}, "model": "block/copper_forge"}
  ]
}
JSON
  fi
fi
ls -lh plugins/Oraxen/pack/assets/minecraft/models/item/smithing_table.json 2>&1 | head -5
ls -lh plugins/Oraxen/settings.yml 2>&1 | head -5
# Ensure Oraxen and ProtocolLib from repo are present (rclone copy doesn't delete, but restore may have old backup without them)
if [ ! -f plugins/Oraxen.jar ]; then
  echo "==> Oraxen.jar missing after restore, restoring from repo..."
  git checkout -- plugins/Oraxen.jar 2>&1 | head -5 || echo "git checkout Oraxen.jar failed"
  ls -lh plugins/Oraxen.jar 2>&1 | head -5 || echo "still missing"
fi
if [ ! -f plugins/ProtocolLib.jar ]; then
  echo "==> ProtocolLib.jar missing after restore, restoring from repo..."
  git checkout -- plugins/ProtocolLib.jar 2>&1 | head -5 || echo "git checkout ProtocolLib failed"
  ls -lh plugins/ProtocolLib.jar 2>&1 | head -5 || echo "still missing"
fi
echo "==> Plugins after restore+rebuild:"
ls -lh plugins/ 2>&1 | head -30
# Debug Oraxen load check
if [ -f plugins/Oraxen.jar ]; then echo "Oraxen present for texture"; else echo "Oraxen STILL missing!"; fi

# Named pipe so we can send server commands (save-all) reliably
PIPE=/tmp/mc-in
rm -f "$PIPE"
mkfifo "$PIPE"

# Start server reading commands from the pipe
tail -f "$PIPE" | java -Xms2G -Xmx${JAVA_HEAP} -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
     -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
     -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
     -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
     -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
     -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem \
     -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs \
     -Daikars.new.flags=true -jar server.jar nogui > server.log 2>&1 &
SERVER_PID=$!
echo "==> Server PID: $SERVER_PID"

# Open the pipe for writing
exec 3> "$PIPE"

# Wait for startup
echo "==> Waiting for server startup..."
for i in $(seq 1 300); do
  if grep -q "Done (" server.log 2>/dev/null; then
    echo "==> Server is UP!"
    break
  fi
  sleep 2
done

if ! kill -0 $SERVER_PID 2>/dev/null; then
  echo "==> SERVER FAILED TO START. Log:"
  tail -50 server.log
  exit 1
fi

# Telegram live console: stream server.log (and velocity.log on the proxy) to chat every 5s
if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ]; then
  echo "==> Telegram console streaming enabled"
  (
    last=$(wc -c < server.log 2>/dev/null || echo 0)
    vlast=$(wc -c < velocity.log 2>/dev/null || echo 0)
    while kill -0 $SERVER_PID 2>/dev/null; do
      sleep 5
      now=$(wc -c < server.log 2>/dev/null || echo 0)
      if [ "$now" -gt "$last" ]; then
        msg=$(tail -c +$((last+1)) server.log 2>/dev/null | tail -c 3000)
        last=$now
        if [ -n "$msg" ]; then
          curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
            -d chat_id="$TELEGRAM_CHAT_ID" -d text="$msg" >/dev/null 2>&1 || true
        fi
      fi
      if [ -f velocity.log ]; then
        vnow=$(wc -c < velocity.log 2>/dev/null || echo 0)
        if [ "$vnow" -gt "$vlast" ]; then
          vmsg=$(tail -c +$((vlast+1)) velocity.log 2>/dev/null | tail -c 3000)
          vlast=$vnow
          if [ -n "$vmsg" ]; then
            curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
              -d chat_id="$TELEGRAM_CHAT_ID" -d text="[proxy] $vmsg" >/dev/null 2>&1 || true
          fi
        fi
      fi
    done
  ) &
  TG_STREAM_PID=$!
fi

# Telegram command channel: poll repo for console-cmd.txt and feed to server pipe
if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ] && [ -n "$GITHUB_TOKEN" ]; then
  (
    while kill -0 $SERVER_PID 2>/dev/null; do
      sleep 4
      line=$(curl -fsSL "https://raw.githubusercontent.com/$GITHUB_REPOSITORY/main/console-cmd.txt" 2>/dev/null | head -1 || true)
      if [ -n "$line" ]; then
        echo "==> TG command: $line"
        echo "$line" >&3
        SHA=$(curl -fsSL -H "Authorization: token $GITHUB_TOKEN" \
          "https://api.github.com/repos/$GITHUB_REPOSITORY/contents/console-cmd.txt" 2>/dev/null | jq -r '.sha' || true)
        if [ -n "$SHA" ] && [ "$SHA" != "null" ]; then
          curl -s -X DELETE -H "Authorization: token $GITHUB_TOKEN" \
            -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" \
            -d "{\"message\":\"delete console cmd\",\"sha\":\"$SHA\"}" \
            "https://api.github.com/repos/$GITHUB_REPOSITORY/contents/console-cmd.txt" >/dev/null 2>&1 || true
        fi
      fi
    done
  ) &
fi

# Backup loop: every 60s, save + upload, keep only newest N
echo "==> Starting backup loop (every ${BACKUP_INTERVAL}s, keep ${BACKUP_KEEP})..."
BACKUP_ROOT="/tmp/backups"
mkdir -p "$BACKUP_ROOT"

while kill -0 $SERVER_PID 2>/dev/null; do
  sleep "$BACKUP_INTERVAL"

  echo "==> Sending save-all"
  echo "save-all" >&3
  sleep 5

  TS=$(date +%Y%m%d-%H%M%S)
  BDIR="$BACKUP_ROOT/$TS"
  mkdir -p "$BDIR"

  for w in world world_nether world_the_end; do
    if [ -d "$w" ]; then
      cp -r "$w" "$BDIR/$w" 2>/dev/null || true
    fi
  done

  if [ -d plugins ]; then
    cp -r plugins "$BDIR/plugins" 2>/dev/null || true
  fi

  echo "==> Uploading backup $TS ..."
  rclone copy "$BDIR" "$DRIVE_ROOT/backups/$TS" 2>&1 | tail -3 || true
  rm -rf "$BDIR"

  # Prune: keep only the newest $BACKUP_KEEP dirs on Drive (PERMANENT delete, not trash)
  for d in $(rclone lsf "$DRIVE_ROOT/backups" --dirs-only 2>/dev/null | sort -r | tail -n +$((BACKUP_KEEP+1))); do
    echo "==> Pruning old backup: $d (permanent, not trash)"
    rclone purge "$DRIVE_ROOT/backups/$d" --drive-use-trash=false 2>&1 | tail -1 || true
  done
  # Also empty Drive trash to free storage (old backups were trashed, not deleted)
  rclone cleanup "mcworlds:" --drive-use-trash=false 2>&1 | tail -1 || echo "cleanup: no trash or already empty"
done

echo "==> Server stopped. Final log:"
tail -30 server.log