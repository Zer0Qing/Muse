#!/usr/bin/env sh
# Muse compute node bootstrap.
#
# Four idempotent phases: PROBE -> DAEMON -> PACKS -> VERIFY.
# Progress is emitted as one machine-readable JSON line per event on stdout:
#   @@MUSE_BOOT {"phase":"PROBE","step":"os_check","percent":15,...}
# Human-readable logs go to stderr so the SSH bootstrap parser only sees JSON.
#
# Usage:
#   bootstrap.sh [--mode probe|install|pack] [--bind 127.0.0.1|0.0.0.0]
#                [--packs core,browser] [--create-swap 0|2048] [--port 18765]
#
# The script only writes inside the current user's HOME:
#   ~/.muse-node and ~/muse-workspace.
set -u

MUSE_HOME="${HOME:?HOME must be set}"
MUSE_DIR="$MUSE_HOME/.muse-node"
WORKSPACE="$MUSE_HOME/muse-workspace"
DEFAULT_PORT=18765
NODE_VERSION="v22.14.0"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)

MODE="install"
BIND_ADDR="127.0.0.1"
BIND_SET=false
PACKS="core"
CREATE_SWAP=0
REQUESTED_PORT=""

OS_ID="unknown"
OS_VERSION=""
OS_LABEL="unknown"
ARCH=""
MEM_MB=0
DISK_FREE_MB=0
SWAP_MB=0
HAS_SWAP=false
HAS_NODE=false
HAS_PYTHON=false
HAS_DOCKER=false
HAS_SYSTEMD=false
CAN_SUDO=false
PYTHON_BIN=""
PYTHON_VERSION=""
NODE_BIN=""
NODE_RUNTIME_VERSION=""
TOKEN=""
PORT="$DEFAULT_PORT"
TLS_ENABLED=false
TLS_FP=""
SERVER_CHANGED=false
BROWSER_PACK_OK=false

log() {
  printf '[muse-bootstrap] %s\n' "$*" >&2
}

root_exec() {
  if [ "$(id -u 2>/dev/null || printf 1)" -eq 0 ]; then
    "$@"
  else
    sudo -n "$@"
  fi
}

emit() {
  phase=$1
  step=$2
  percent=$3
  status=$4
  message=$5
  data=${6:-}
  printf '@@MUSE_BOOT {"phase":"%s","step":"%s","percent":%s,"status":"%s","msg":"%s"%s}\n' \
    "$phase" "$step" "$percent" "$status" "$message" "$data"
}

fail_phase() {
  phase=$1
  step=$2
  message=$3
  emit "$phase" "$step" 0 error "$message" ""
  log "ERROR: $message"
  exit 1
}

usage() {
  cat <<'USAGE'
Muse compute node bootstrap

Options:
  --mode probe|install|pack   Probe only, full install, or install capability packs
  --bind 127.0.0.1|0.0.0.0    Loopback (SSH tunnel mode) or all interfaces (DIRECT_TLS)
  --packs core,browser        Capability packs to install (core is always implied)
  --create-swap 0|2048        Create a swap file in MB when the host has no swap
  --port 18765                Preferred daemon port (auto-increments when busy)
  -h, --help                  Show this help

Progress lines on stdout:
  @@MUSE_BOOT {"phase":"PROBE|DAEMON|PACKS|VERIFY","step":"...","percent":0-100,"status":"running|ok|warn|error","data":{...}}
USAGE
}

is_number() {
  case "${1:-}" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --mode)
        [ $# -ge 2 ] || fail_phase PROBE args "missing value for --mode"
        MODE=$2
        shift 2
        ;;
      --mode=*)
        MODE=${1#*=}
        shift
        ;;
      --bind)
        [ $# -ge 2 ] || fail_phase PROBE args "missing value for --bind"
        BIND_ADDR=$2
        BIND_SET=true
        shift 2
        ;;
      --bind=*)
        BIND_ADDR=${1#*=}
        BIND_SET=true
        shift
        ;;
      --packs)
        [ $# -ge 2 ] || fail_phase PROBE args "missing value for --packs"
        PACKS=$2
        shift 2
        ;;
      --packs=*)
        PACKS=${1#*=}
        shift
        ;;
      --create-swap)
        [ $# -ge 2 ] || fail_phase PROBE args "missing value for --create-swap"
        CREATE_SWAP=$2
        shift 2
        ;;
      --create-swap=*)
        CREATE_SWAP=${1#*=}
        shift
        ;;
      --port)
        [ $# -ge 2 ] || fail_phase PROBE args "missing value for --port"
        REQUESTED_PORT=$2
        shift 2
        ;;
      --port=*)
        REQUESTED_PORT=${1#*=}
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        fail_phase PROBE args "unknown argument: $1"
        ;;
    esac
  done

  case "$MODE" in
    probe|install|pack) ;;
    *) fail_phase PROBE args "unsupported mode: $MODE" ;;
  esac
  case "$BIND_ADDR" in
    127.0.0.1|0.0.0.0) ;;
    *) fail_phase PROBE args "unsupported bind address: $BIND_ADDR" ;;
  esac
  case "$PACKS" in
    ''|*[!a-z,]*)
      fail_phase PROBE args "unsupported packs value: $PACKS"
      ;;
  esac
  case ",$PACKS," in
    *,core,*|*,browser,*) ;;
    *) fail_phase PROBE args "packs must contain core or browser" ;;
  esac
  if ! is_number "$CREATE_SWAP"; then
    fail_phase PROBE args "--create-swap must be a number of megabytes"
  fi
  if [ -n "$REQUESTED_PORT" ] && ! is_number "$REQUESTED_PORT"; then
    fail_phase PROBE args "--port must be a number"
  fi
}

detect_probe() {
  if [ -r /etc/os-release ]; then
    OS_ID=$(sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"')
    OS_VERSION=$(sed -n 's/^VERSION_ID=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"')
  fi
  [ -n "$OS_ID" ] || OS_ID=$(uname -s 2>/dev/null | tr '[:upper:]' '[:lower:]')
  OS_LABEL=$(printf '%s-%s' "$OS_ID" "$OS_VERSION" | sed 's/-$//' | tr -cd 'A-Za-z0-9._-')
  [ -n "$OS_LABEL" ] || OS_LABEL="unknown"

  ARCH=$(uname -m 2>/dev/null || printf 'unknown')

  MEM_MB=0
  if [ -r /proc/meminfo ]; then
    value=$(awk '/^MemTotal:/{print int($2/1024)}' /proc/meminfo 2>/dev/null || true)
    is_number "$value" && MEM_MB=$value
  fi

  DISK_FREE_MB=0
  value=$(df -Pk "$MUSE_HOME" 2>/dev/null | awk 'NR==2{print int($4/1024)}' || true)
  is_number "$value" && DISK_FREE_MB=$value

  SWAP_MB=0
  if [ -r /proc/meminfo ]; then
    value=$(awk '/^SwapTotal:/{print int($2/1024)}' /proc/meminfo 2>/dev/null || true)
    is_number "$value" && SWAP_MB=$value
  fi
  if [ "$SWAP_MB" -gt 0 ]; then HAS_SWAP=true; else HAS_SWAP=false; fi

  HAS_NODE=false
  NODE_RUNTIME_VERSION=""
  if command -v node >/dev/null 2>&1; then
    NODE_RUNTIME_VERSION=$(node -v 2>/dev/null || true)
    major=${NODE_RUNTIME_VERSION#v}
    major=${major%%.*}
    if is_number "$major" && [ "$major" -ge 20 ]; then
      HAS_NODE=true
    fi
  fi

  HAS_PYTHON=false
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN=$(command -v python3)
    PYTHON_VERSION=$("$PYTHON_BIN" --version 2>&1 | awk '{print $2}' || true)
    HAS_PYTHON=true
  elif command -v python >/dev/null 2>&1; then
    candidate_version=$(python --version 2>&1 || true)
    case "$candidate_version" in
      Python\ 3.*)
        PYTHON_BIN=$(command -v python)
        PYTHON_VERSION=$(printf '%s' "$candidate_version" | awk '{print $2}')
        HAS_PYTHON=true
        ;;
    esac
  fi

  HAS_DOCKER=false
  command -v docker >/dev/null 2>&1 && HAS_DOCKER=true

  HAS_SYSTEMD=false
  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
    HAS_SYSTEMD=true
  fi

  CAN_SUDO=false
  if [ "$(id -u 2>/dev/null || printf 1)" -eq 0 ]; then
    CAN_SUDO=true
  elif command -v sudo >/dev/null 2>&1 && root_exec true >/dev/null 2>&1; then
    CAN_SUDO=true
  fi
}

phase_probe() {
  emit PROBE os_check 5 running "Checking OS, architecture, memory and disk..." ""
  detect_probe

  if [ "$MEM_MB" -gt 0 ] && [ "$MEM_MB" -lt 1024 ]; then
    emit PROBE memory 12 warn "Less than 1024 MB RAM; expect constrained builds" ""
  fi
  if [ "$DISK_FREE_MB" -gt 0 ] && [ "$DISK_FREE_MB" -lt 5120 ]; then
    emit PROBE disk 15 warn "Less than 5 GB free disk; clean up before installing packs" ""
  fi
  if [ "$MEM_MB" -gt 0 ] && [ "$MEM_MB" -le 1200 ] && [ "$HAS_SWAP" = false ] && [ "$CREATE_SWAP" -eq 0 ]; then
    emit PROBE swap_hint 20 warn "1C1G host without swap; rerun with --create-swap 2048 to add swap" ""
  fi

  data=$(printf ',"data":{"os":"%s","arch":"%s","memMb":%s,"diskFreeMb":%s,"hasSwap":%s,"hasNode":%s,"hasPython":%s,"hasDocker":%s,"hasSystemd":%s,"canSudo":%s}' \
    "$OS_LABEL" "$ARCH" "$MEM_MB" "$DISK_FREE_MB" "$HAS_SWAP" "$HAS_NODE" "$HAS_PYTHON" "$HAS_DOCKER" "$HAS_SYSTEMD" "$CAN_SUDO")
  emit PROBE done 25 ok "System probe finished" "$data"
}

create_swap_if_requested() {
  [ "$CREATE_SWAP" -gt 0 ] || return 0
  emit DAEMON swap 28 running "Preparing swap file..." ""
  if [ "$HAS_SWAP" = true ]; then
    emit DAEMON swap 30 ok "Swap already active; keeping existing swap" ""
    return 0
  fi
  if [ "$CAN_SUDO" != true ]; then
    emit DAEMON swap 30 warn "No passwordless sudo; skipping swap creation" ""
    return 0
  fi
  swapfile="$MUSE_DIR/swapfile"
  if [ -f "$swapfile" ]; then
    size_mb=$(du -m "$swapfile" 2>/dev/null | awk '{print $1}' || true)
    if is_number "$size_mb" && [ "$size_mb" -ge "$CREATE_SWAP" ]; then
      root_exec swapon "$swapfile" >/dev/null 2>&1 || true
      emit DAEMON swap 30 ok "Existing swap file reused" ""
      return 0
    fi
    root_exec swapoff "$swapfile" >/dev/null 2>&1 || true
    root_exec rm -f "$swapfile" >/dev/null 2>&1 || true
  fi
  if root_exec fallocate -l "${CREATE_SWAP}M" "$swapfile" >/dev/null 2>&1 \
    || root_exec dd if=/dev/zero of="$swapfile" bs=1M count="$CREATE_SWAP" >/dev/null 2>&1; then
    root_exec chmod 600 "$swapfile" >/dev/null 2>&1 || true
    if root_exec mkswap "$swapfile" >/dev/null 2>&1 && root_exec swapon "$swapfile" >/dev/null 2>&1; then
      if ! grep -q "$swapfile" /etc/fstab 2>/dev/null; then
        printf '%s none swap sw 0 0\n' "$swapfile" | root_exec tee -a /etc/fstab >/dev/null 2>&1 || true
      fi
      emit DAEMON swap 30 ok "Swap file created" ""
      return 0
    fi
  fi
  emit DAEMON swap 30 warn "Swap file creation failed; continuing without swap" ""
}

ensure_node_runtime() {
  if [ "$HAS_NODE" = true ]; then
    NODE_BIN=$(command -v node)
    NODE_RUNTIME_VERSION=$(node -v 2>/dev/null || true)
    return 0
  fi
  if [ -x "$MUSE_DIR/runtime/current/bin/node" ]; then
    NODE_BIN="$MUSE_DIR/runtime/current/bin/node"
    candidate_version=$("$NODE_BIN" -v 2>/dev/null || true)
    major=${candidate_version#v}
    major=${major%%.*}
    if is_number "$major" && [ "$major" -ge 20 ]; then
      NODE_RUNTIME_VERSION=$candidate_version
      return 0
    fi
  fi

  case "$ARCH" in
    x86_64|amd64) node_arch="x64" ;;
    aarch64|arm64) node_arch="arm64" ;;
    *) fail_phase DAEMON node_runtime "unsupported architecture: $ARCH" ;;
  esac

  mkdir -p "$MUSE_DIR/runtime" || fail_phase DAEMON node_runtime "cannot create runtime directory"
  archive="$MUSE_DIR/runtime/node-$NODE_VERSION-linux-$node_arch.tar.gz"
  url="https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-$node_arch.tar.gz"
  log "downloading Node runtime: $url"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --connect-timeout 20 "$url" -o "$archive" || fail_phase DAEMON node_runtime "Node runtime download failed"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$url" -O "$archive" || fail_phase DAEMON node_runtime "Node runtime download failed"
  else
    fail_phase DAEMON node_runtime "curl or wget is required to download Node"
  fi
  tar -xzf "$archive" -C "$MUSE_DIR/runtime" || fail_phase DAEMON node_runtime "Node runtime extraction failed"
  extracted="$MUSE_DIR/runtime/node-$NODE_VERSION-linux-$node_arch"
  [ -x "$extracted/bin/node" ] || fail_phase DAEMON node_runtime "Node runtime archive is incomplete"
  ln -sfn "$extracted" "$MUSE_DIR/runtime/current"
  NODE_BIN="$MUSE_DIR/runtime/current/bin/node"
  NODE_RUNTIME_VERSION=$("$NODE_BIN" -v 2>/dev/null || true)
}

copy_payload() {
  mkdir -p "$MUSE_DIR/bin"
  source_server="$SCRIPT_DIR/server.mjs"
  if [ -f "$source_server" ]; then
    if [ -f "$MUSE_DIR/bin/server.mjs" ] && cmp -s "$source_server" "$MUSE_DIR/bin/server.mjs"; then
      SERVER_CHANGED=false
    else
      cp "$source_server" "$MUSE_DIR/bin/server.mjs.tmp" || fail_phase DAEMON assets "cannot stage server.mjs"
      mv "$MUSE_DIR/bin/server.mjs.tmp" "$MUSE_DIR/bin/server.mjs"
      SERVER_CHANGED=true
    fi
  elif [ ! -f "$MUSE_DIR/bin/server.mjs" ]; then
    fail_phase DAEMON assets "server.mjs not found next to bootstrap.sh"
  fi
  if [ -f "$SCRIPT_DIR/bootstrap.sh" ] && [ "$SCRIPT_DIR/bootstrap.sh" != "$MUSE_DIR/bin/bootstrap.sh" ]; then
    cp "$SCRIPT_DIR/bootstrap.sh" "$MUSE_DIR/bin/bootstrap.sh" 2>/dev/null || true
  fi
  chmod 700 "$MUSE_DIR/bin/server.mjs" 2>/dev/null || true
}

ensure_token() {
  token_file="$MUSE_DIR/auth.token"
  if [ -f "$token_file" ]; then
    existing=$(tr -d ' \r\n' < "$token_file" 2>/dev/null || true)
    case "$existing" in
      *[!0-9a-fA-F]*|'') existing="" ;;
    esac
    if [ ${#existing} -eq 64 ]; then
      TOKEN=$existing
      chmod 600 "$token_file" 2>/dev/null || true
      return 0
    fi
  fi
  TOKEN=""
  if command -v od >/dev/null 2>&1; then
    TOKEN=$(od -An -N32 -tx1 /dev/urandom 2>/dev/null | tr -d ' \r\n')
  fi
  case "$TOKEN" in
    *[!0-9a-fA-F]*|'') TOKEN="" ;;
  esac
  if [ ${#TOKEN} -ne 64 ]; then
    if command -v openssl >/dev/null 2>&1; then
      TOKEN=$(openssl rand -hex 32 2>/dev/null || true)
    elif [ -n "$NODE_BIN" ]; then
      TOKEN=$("$NODE_BIN" -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))" 2>/dev/null || true)
    fi
  fi
  case "$TOKEN" in
    *[!0-9a-fA-F]*|'') fail_phase DAEMON token "cannot generate auth token" ;;
  esac
  printf '%s' "$TOKEN" > "$token_file" || fail_phase DAEMON token "cannot write auth.token"
  chmod 600 "$token_file" 2>/dev/null || true
}

port_in_use() {
  candidate_port=$1
  "$NODE_BIN" - "$candidate_port" <<'NODE'
const net = require('net');
const port = Number(process.argv[2]);
const socket = net.createConnection({ host: '127.0.0.1', port });
socket.setTimeout(300);
socket.on('connect', () => { socket.destroy(); process.exit(0); });
socket.on('timeout', () => { socket.destroy(); process.exit(1); });
socket.on('error', () => process.exit(1));
NODE
}

pick_port() {
  if [ -n "$REQUESTED_PORT" ]; then
    PORT=$REQUESTED_PORT
  fi
  if [ -z "$REQUESTED_PORT" ] && [ -f "$MUSE_DIR/node.json" ]; then
    saved_port=$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$MUSE_DIR/node.json" 2>/dev/null | head -n 1)
    if is_number "$saved_port" && [ "$saved_port" -gt 0 ]; then
      PORT=$saved_port
    fi
  fi
  if health_ok; then
    return 0
  fi
  attempts=0
  while [ "$attempts" -lt 30 ]; do
    if ! port_in_use "$PORT"; then
      return 0
    fi
    PORT=$((PORT + 1))
    attempts=$((attempts + 1))
  done
  fail_phase DAEMON port "no free port found near $PORT"
}

ensure_tls_certificate() {
  if [ "$BIND_ADDR" != "0.0.0.0" ]; then
    TLS_ENABLED=false
    return 0
  fi
  TLS_ENABLED=true
  mkdir -p "$MUSE_DIR/certs" || fail_phase DAEMON tls "cannot create certs directory"
  cert="$MUSE_DIR/certs/cert.pem"
  key="$MUSE_DIR/certs/key.pem"
  if [ ! -s "$cert" ] || [ ! -s "$key" ]; then
    command -v openssl >/dev/null 2>&1 || fail_phase DAEMON tls "openssl is required for DIRECT_TLS mode"
    openssl ecparam -name prime256v1 -genkey -noout -out "$key" 2>/dev/null || fail_phase DAEMON tls "cannot generate ECDSA key"
    openssl req -new -x509 -key "$key" -out "$cert" -days 825 -subj "/CN=muse-node" 2>/dev/null || fail_phase DAEMON tls "cannot generate self-signed certificate"
    chmod 600 "$key" 2>/dev/null || true
  fi
  fingerprint=""
  if command -v sha256sum >/dev/null 2>&1; then
    fingerprint=$(openssl x509 -in "$cert" -outform der 2>/dev/null | sha256sum | awk '{print $1}')
  fi
  if [ -z "$fingerprint" ] && command -v openssl >/dev/null 2>&1; then
    fingerprint=$(openssl x509 -in "$cert" -outform der 2>/dev/null | openssl dgst -sha256 2>/dev/null | awk '{print $NF}')
  fi
  [ -n "$fingerprint" ] || fail_phase DAEMON tls "cannot compute certificate fingerprint"
  TLS_FP="SHA256:$fingerprint"
  printf '%s\n' "$TLS_FP" > "$MUSE_DIR/certs/sha256.fp"
}

write_node_json() {
  packs_json='"core"'
  if [ -f "$MUSE_DIR/node.json" ]; then
    if grep -q '"browser"' "$MUSE_DIR/node.json" 2>/dev/null; then
      packs_json='"core","browser"'
    fi
  fi
  if [ "$BROWSER_PACK_OK" = true ]; then
    packs_json='"core","browser"'
  fi
  case "$TLS_ENABLED" in
    true) tls_json=true ;;
    *) tls_json=false ;;
  esac
  temporary="$MUSE_DIR/node.json.tmp"
  cat > "$temporary" <<EOF
{"version":1,"port":$PORT,"bind":"$BIND_ADDR","packs":[$packs_json],"tls":$tls_json,"updatedAt":$(date +%s)}
EOF
  mv "$temporary" "$MUSE_DIR/node.json" || fail_phase DAEMON config "cannot write node.json"
  chmod 600 "$MUSE_DIR/node.json" 2>/dev/null || true
}

health_ok() {
  [ -n "$NODE_BIN" ] || return 1
  MUSE_HEALTH_TOKEN="$TOKEN" "$NODE_BIN" - "$PORT" "$TLS_ENABLED" <<'NODE'
const http = require('http');
const https = require('https');
const port = Number(process.argv[2]);
const tls = process.argv[3] === 'true';
const token = process.env.MUSE_HEALTH_TOKEN || '';
const client = tls ? https : http;
const request = client.get({
  host: '127.0.0.1',
  port,
  path: '/health',
  headers: { Authorization: 'Bearer ' + token },
  rejectUnauthorized: false,
  timeout: 3000,
}, (response) => {
  let body = '';
  response.on('data', (chunk) => { body += chunk; });
  response.on('end', () => {
    try {
      const parsed = JSON.parse(body);
      process.exit(parsed.ok === true ? 0 : 1);
    } catch {
      process.exit(1);
    }
  });
});
request.on('error', () => process.exit(1));
request.on('timeout', () => { request.destroy(); process.exit(1); });
NODE
}

wait_health() {
  attempts=0
  while [ "$attempts" -lt 20 ]; do
    if health_ok; then
      return 0
    fi
    sleep 1
    attempts=$((attempts + 1))
  done
  return 1
}

write_run_script() {
  run_script="$MUSE_DIR/bin/run-node.sh"
  cat > "$run_script" <<EOF
#!/bin/sh
cd "$MUSE_HOME" || exit 1
exec "$NODE_BIN" "$MUSE_DIR/bin/server.mjs" --config "$MUSE_DIR/node.json"
EOF
  chmod 700 "$run_script"
}

start_systemd_system() {
  unit_path="/etc/systemd/system/muse-node.service"
  user_name=$(id -un 2>/dev/null || printf 'root')
  temporary="$MUSE_DIR/muse-node.service.tmp"
  cat > "$temporary" <<EOF
[Unit]
Description=Muse Compute Node
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$user_name
Environment=HOME=$MUSE_HOME
WorkingDirectory=$MUSE_HOME
ExecStart="$NODE_BIN" "$MUSE_DIR/bin/server.mjs" --config "$MUSE_DIR/node.json"
Restart=always
RestartSec=3
StandardOutput=null
StandardError=null

[Install]
WantedBy=multi-user.target
EOF
  root_exec cp "$temporary" "$unit_path" >/dev/null 2>&1 || return 1
  rm -f "$temporary" 2>/dev/null || true
  root_exec systemctl daemon-reload >/dev/null 2>&1 || return 1
  root_exec systemctl enable muse-node >/dev/null 2>&1 || true
  root_exec systemctl restart muse-node >/dev/null 2>&1 || return 1
  wait_health
}

start_systemd_user() {
  command -v systemctl >/dev/null 2>&1 || return 1
  systemctl --user show-environment >/dev/null 2>&1 || return 1
  mkdir -p "$MUSE_HOME/.config/systemd/user" || return 1
  unit_path="$MUSE_HOME/.config/systemd/user/muse-node.service"
  cat > "$unit_path" <<EOF
[Unit]
Description=Muse Compute Node
After=network-online.target

[Service]
Type=simple
Environment=HOME=$MUSE_HOME
WorkingDirectory=$MUSE_HOME
ExecStart="$NODE_BIN" "$MUSE_DIR/bin/server.mjs" --config "$MUSE_DIR/node.json"
Restart=always
RestartSec=3
StandardOutput=null
StandardError=null

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload >/dev/null 2>&1 || return 1
  systemctl --user enable muse-node >/dev/null 2>&1 || true
  systemctl --user restart muse-node >/dev/null 2>&1 || return 1
  loginctl enable-linger "$(id -un 2>/dev/null || printf root)" >/dev/null 2>&1 || true
  wait_health
}

start_cron_nohup() {
  write_run_script
  if command -v crontab >/dev/null 2>&1; then
    current=$(crontab -l 2>/dev/null || true)
    filtered=$(printf '%s\n' "$current" | grep -v 'muse-node/bin/run-node.sh' || true)
    {
      printf '%s\n' "$filtered"
      printf '@reboot "%s"\n' "$MUSE_DIR/bin/run-node.sh"
    } | crontab - >/dev/null 2>&1 || true
  fi
  if [ -f "$MUSE_DIR/muse-node.pid" ]; then
    old_pid=$(cat "$MUSE_DIR/muse-node.pid" 2>/dev/null || true)
    if is_number "$old_pid" && kill -0 "$old_pid" >/dev/null 2>&1; then
      kill "$old_pid" >/dev/null 2>&1 || true
      sleep 1
    fi
  fi
  nohup "$MUSE_DIR/bin/run-node.sh" >> "$MUSE_DIR/logs/muse-node.log" 2>&1 &
  child_pid=$!
  printf '%s' "$child_pid" > "$MUSE_DIR/muse-node.pid"
  wait_health
}

start_daemon() {
  if health_ok && [ "$SERVER_CHANGED" = false ]; then
    emit DAEMON start 60 ok "muse-node already running" ",\"data\":{\"port\":$PORT,\"bind\":\"$BIND_ADDR\",\"token\":\"$TOKEN\",\"fp\":\"$TLS_FP\",\"manager\":\"already-running\"}"
    return 0
  fi

  if [ "$SERVER_CHANGED" = true ] && health_ok; then
    log "server.mjs changed; restarting daemon"
  fi

  manager="cron-nohup"
  started=false
  if [ "$HAS_SYSTEMD" = true ] && [ "$CAN_SUDO" = true ]; then
    if start_systemd_system; then
      manager="systemd"
      started=true
    fi
  fi
  if [ "$started" = false ] && command -v systemctl >/dev/null 2>&1; then
    if start_systemd_user; then
      manager="systemd-user"
      started=true
    fi
  fi
  if [ "$started" = false ]; then
    if start_cron_nohup; then
      manager="cron-nohup"
      started=true
    fi
  fi
  if [ "$started" = false ]; then
    fail_phase DAEMON start "daemon failed to become healthy"
  fi
  data=$(printf ',"data":{"port":%s,"bind":"%s","token":"%s","fp":"%s","manager":"%s"}' "$PORT" "$BIND_ADDR" "$TOKEN" "$TLS_FP" "$manager")
  emit DAEMON start 60 ok "muse-node daemon is running" "$data"
}

phase_daemon() {
  emit DAEMON layout 27 running "Creating isolated directories..." ""
  mkdir -p "$MUSE_DIR/certs" "$MUSE_DIR/bin" "$MUSE_DIR/runtime" "$MUSE_DIR/venv" "$MUSE_DIR/tools" "$MUSE_DIR/outbox" "$MUSE_DIR/watchers" "$MUSE_DIR/logs" "$WORKSPACE" || fail_phase DAEMON layout "cannot create muse directories"
  chmod 700 "$MUSE_DIR" "$WORKSPACE" 2>/dev/null || true

  load_existing_identity
  create_swap_if_requested

  emit DAEMON node_runtime 34 running "Checking Node.js runtime..." ""
  ensure_node_runtime
  node_safe=$(printf '%s' "$NODE_BIN" | sed 's/\\/\\\\/g')
  emit DAEMON node_runtime 42 ok "Node.js runtime ready" ",\"data\":{\"node\":\"$node_safe\",\"version\":\"$NODE_RUNTIME_VERSION\"}"

  emit DAEMON assets 46 running "Installing daemon payload..." ""
  copy_payload
  emit DAEMON assets 48 ok "Daemon payload installed" ""

  ensure_token
  pick_port
  ensure_tls_certificate
  write_node_json

  start_daemon
}

install_python_if_missing() {
  [ -n "$PYTHON_BIN" ] && return 0
  [ "$CAN_SUDO" = true ] || return 1
  case "$OS_ID" in
    ubuntu|debian)
      root_exec apt-get update -y >/dev/null 2>&1 || true
      root_exec apt-get install -y python3 python3-venv >/dev/null 2>&1 || return 1
      ;;
    alpine)
      root_exec apk add --no-cache python3 py3-pip >/dev/null 2>&1 || return 1
      ;;
    *)
      return 1
      ;;
  esac
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN=$(command -v python3)
    PYTHON_VERSION=$("$PYTHON_BIN" --version 2>&1 | awk '{print $2}' || true)
    HAS_PYTHON=true
    return 0
  fi
  return 1
}

pack_core() {
  emit PACKS core 62 running "Preparing Python virtual environment and npm tools..." ""
  install_python_if_missing || true
  if [ -n "$PYTHON_BIN" ]; then
    if [ -x "$MUSE_DIR/venv/bin/python" ] || [ -x "$MUSE_DIR/venv/Scripts/python.exe" ]; then
      :
    else
      if ! "$PYTHON_BIN" -m venv "$MUSE_DIR/venv" >/dev/null 2>&1; then
        if [ "$CAN_SUDO" = true ]; then
          case "$OS_ID" in
            ubuntu|debian) root_exec apt-get install -y python3-venv python3-pip >/dev/null 2>&1 || true ;;
            alpine) root_exec apk add --no-cache py3-virtualenv py3-pip >/dev/null 2>&1 || true ;;
          esac
        fi
        "$PYTHON_BIN" -m venv "$MUSE_DIR/venv" >/dev/null 2>&1 || log "WARN: python venv creation failed"
      fi
    fi
  else
    log "WARN: python3 not available; venv skipped"
  fi
  mkdir -p "$MUSE_DIR/tools" || fail_phase PACKS core "cannot create tools directory"
  if [ ! -f "$MUSE_DIR/tools/package.json" ]; then
    printf '{"name":"muse-node-tools","private":true,"version":"1.0.0"}\n' > "$MUSE_DIR/tools/package.json"
  fi
  printf 'prefix=%s\n' "$MUSE_DIR/tools" > "$MUSE_DIR/tools/.npmrc"
  emit PACKS core 75 ok "Core pack ready" ',"data":{"packs":["core"]}'
}

pack_browser() {
  emit PACKS browser 77 running "Installing browser pack..." ""
  npm_bin="npm"
  if ! command -v npm >/dev/null 2>&1; then
    if [ -x "$MUSE_DIR/runtime/current/bin/npm" ]; then
      npm_bin="$MUSE_DIR/runtime/current/bin/npm"
    else
      log "WARN: npm not available; browser pack skipped"
      emit PACKS browser 82 warn "npm not available; browser pack skipped" ""
      return 0
    fi
  fi
  if ! "$npm_bin" install --prefix "$MUSE_DIR/tools" --no-audit --no-fund playwright-core >/dev/null 2>&1; then
    emit PACKS browser 82 warn "playwright-core install failed; retry later from the node UI" ""
    return 0
  fi
  BROWSER_PACK_OK=true
  cli="$MUSE_DIR/tools/node_modules/playwright-core/cli.js"
  if [ -f "$cli" ]; then
    PLAYWRIGHT_BROWSERS_PATH="$MUSE_DIR/tools/ms-playwright" "$NODE_BIN" "$cli" install chromium >/dev/null 2>&1 || log "WARN: chromium download failed"
  fi
  if [ "$CAN_SUDO" = true ]; then
    case "$OS_ID" in
      ubuntu|debian) root_exec apt-get install -y fonts-noto-cjk >/dev/null 2>&1 || true ;;
      alpine) root_exec apk add --no-cache font-noto-cjk >/dev/null 2>&1 || true ;;
    esac
  fi
  emit PACKS browser 83 ok "Browser pack ready" ',"data":{"packs":["browser"]}'
}

load_existing_identity() {
  [ -f "$MUSE_DIR/node.json" ] || return 0
  saved_port=$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$MUSE_DIR/node.json" 2>/dev/null | head -n 1)
  if [ -z "$REQUESTED_PORT" ] && is_number "$saved_port" && [ "$saved_port" -gt 0 ]; then PORT=$saved_port; fi
  if [ "$BIND_SET" != true ]; then
    saved_bind=$(sed -n 's/.*"bind"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$MUSE_DIR/node.json" 2>/dev/null | head -n 1)
    case "$saved_bind" in
      127.0.0.1|0.0.0.0) BIND_ADDR=$saved_bind ;;
    esac
  fi
  if grep -q '"tls":true' "$MUSE_DIR/node.json" 2>/dev/null; then
    TLS_ENABLED=true
    if [ -f "$MUSE_DIR/certs/sha256.fp" ]; then
      TLS_FP=$(cat "$MUSE_DIR/certs/sha256.fp" 2>/dev/null || true)
    fi
  else
    TLS_ENABLED=false
  fi
}

load_existing_config() {
  [ -f "$MUSE_DIR/node.json" ] || fail_phase PACKS config "node is not installed yet; run --mode install first"
  load_existing_identity
  ensure_token
  if [ "$HAS_NODE" = true ]; then
    NODE_BIN=$(command -v node)
    NODE_RUNTIME_VERSION=$(node -v 2>/dev/null || true)
  elif [ -x "$MUSE_DIR/runtime/current/bin/node" ]; then
    NODE_BIN="$MUSE_DIR/runtime/current/bin/node"
    NODE_RUNTIME_VERSION=$("$NODE_BIN" -v 2>/dev/null || true)
  else
    ensure_node_runtime
  fi
}

phase_packs() {
  emit PACKS start 61 running "Installing capability packs..." ""
  pack_core
  case ",$PACKS," in
    *,browser,*) pack_browser ;;
  esac
  packs_data='"core"'
  if [ "$BROWSER_PACK_OK" = true ]; then
    packs_data='"core","browser"'
  elif [ -f "$MUSE_DIR/node.json" ] && grep -q '"browser"' "$MUSE_DIR/node.json" 2>/dev/null; then
    packs_data='"core","browser"'
  fi
  if [ -f "$MUSE_DIR/node.json" ]; then
    write_node_json
  fi
  emit PACKS done 85 ok "Capability packs finished" ",\"data\":{\"packs\":[$packs_data]}"
}

phase_verify() {
  emit VERIFY health 90 running "Verifying daemon health and workspace write access..." ""
  if ! health_ok; then
    fail_phase VERIFY health "health check failed; inspect $MUSE_DIR/logs/muse-node.log"
  fi
  workspace_ok=false
  probe_file="$WORKSPACE/.muse-bootstrap-write-test.$$"
  if (printf 'ok' > "$probe_file") 2>/dev/null; then
    workspace_ok=true
    rm -f "$probe_file" 2>/dev/null || true
  fi
  [ "$workspace_ok" = true ] || fail_phase VERIFY workspace "workspace is not writable"
  [ -n "$NODE_BIN" ] || fail_phase VERIFY node "node runtime missing"
  [ -n "$PYTHON_BIN" ] || fail_phase VERIFY python "Python 3 is required for the core pack"
  python_version="$PYTHON_VERSION"
  [ -n "$python_version" ] || python_version=""
  data=$(printf ',"data":{"nodeVersion":"%s","pythonVersion":"%s","ready":true}' "$NODE_RUNTIME_VERSION" "$python_version")
  emit VERIFY health 100 ok "All checks passed; muse-node is ready" "$data"
  printf '[muse-bootstrap] ready: port=%s bind=%s node=%s\n' "$PORT" "$BIND_ADDR" "$NODE_RUNTIME_VERSION" >&2
}

run_mode() {
  case "$MODE" in
    probe)
      phase_probe
      ;;
    pack)
      detect_probe
      load_existing_config
      phase_packs
      phase_verify
      ;;
    install)
      phase_probe
      phase_daemon
      phase_packs
      phase_verify
      ;;
  esac
}

parse_args "$@"
[ -n "$SCRIPT_DIR" ] || fail_phase PROBE args "cannot resolve script directory"
run_mode
exit 0
