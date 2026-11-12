#!/usr/bin/env bash
# Connects a Linux MeshAgent (in a throwaway Debian container) to the test server's "Test group".
# Conecta un MeshAgent de Linux (en un contenedor Debian desechable) al «Test group» del servidor de pruebas.
#
# Usage / Uso:  start-test-agent.sh <server-container> <network> <agent-name> <work-dir> <group-id>
set -euo pipefail
SERVER=${1:?server}; NET=${2:?network}; NAME=${3:?agent name}; WORK=${4:?work dir}; GROUP=${5:?group id}
IP=$(docker inspect "$SERVER" --format "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}")
mkdir -p "$WORK"
MID=${GROUP#mesh//}
docker exec "$SERVER" wget -q --no-check-certificate -O - "https://localhost/meshagents?id=6" > "$WORK/meshagent"
docker exec "$SERVER" wget -q --no-check-certificate -O - "https://localhost/meshsettings?id=$MID" > "$WORK/meshagent.msh"
chmod +x "$WORK/meshagent"
grep -q '^MeshServer=' "$WORK/meshagent.msh"
# A screen for the remote desktop: an X server (Xvfb) with an xterm. The agent offers the desktop only with libX11, libXtst
# and libXext and an "X" process that ps sees (monitor-info.js), so procps too. / Una pantalla para el escritorio remoto: un
# servidor X (Xvfb) con una xterm. El agente solo ofrece el escritorio con libX11, libXtst y libXext y un proceso «X» que vea ps.
docker run -d --name "$NAME" --hostname test-device --network "$NET" --add-host "mesh.test:$IP" \
  --cpus 1 --memory 768m -v "$WORK:/agent" -w /agent debian:12-slim sh -c "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends xvfb xterm x11-xserver-utils procps libxtst6 libxext6 libxfixes3 openssl >/dev/null 2>&1; Xvfb :0 -screen 0 1024x768x24 & sleep 2; DISPLAY=:0 xterm -geometry 80x24+20+20 & exec ./meshagent connect" >/dev/null
echo "$NAME"
