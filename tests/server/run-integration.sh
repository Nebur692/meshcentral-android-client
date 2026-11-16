#!/usr/bin/env bash
# Runs ALL the core tests (unit + integration) against a throwaway MeshCentral with a real Linux agent, and removes
# everything it created when it ends, even on failure. The same bench as the Windows app (meshcentral-windows-client).
# Pasa TODAS las pruebas del núcleo (unitarias + integración) contra un MeshCentral desechable con un agente Linux de
# verdad, y quita todo lo que creó al acabar, aunque falle. El mismo banco que la app de Windows.
#
# Usage / Uso:  tests/server/run-integration.sh [work-dir]
#   MESH_IMAGE (default ghcr.io/ylianst/meshcentral:latest), JDK_IMAGE (default eclipse-temurin:21-jdk)
#   GRADLE_CACHE: a docker volume or folder for Gradle's cache (default: a throwaway one, removed at the end)
#   GRADLE_CACHE: un volumen de docker o carpeta para la caché de Gradle (por defecto, uno desechable que se borra)
set -euo pipefail
# Test containers and networks carry a label, so the bench's cleaner finds them (PRUEBA = who runs the tests).
# Contenedores y redes de prueba llevan una etiqueta, para que el limpiador del banco los encuentre.
PRUEBA_L="--label prueba=${PRUEBA:-meshclient-bench}"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE="$ROOT/tests/server"
MESH_IMAGE=${MESH_IMAGE:-ghcr.io/ylianst/meshcentral:latest}
JDK_IMAGE=${JDK_IMAGE:-eclipse-temurin:21-jdk}
ID=$$
NET=mcatest-net-$ID; SRV=mcatest-srv-$ID; AGENT=mcatest-agent-$ID; SINK=mcatest-sink-$ID
OWN_CACHE=""
if [ -z "${GRADLE_CACHE:-}" ]; then GRADLE_CACHE=mcatest-gradle-$ID; OWN_CACHE=$GRADLE_CACHE; fi
WORK=${1:-$(mktemp -d)}
mkdir -p "$WORK"

cleanup() {
  docker stop "$AGENT" "$SRV" "$SINK" >/dev/null 2>&1 || true
  # The agent is not --rm, so its state survives a crash: removed here by name.
  # El agente no es --rm, para que su estado sobreviva a una caída: se borra aquí por su nombre.
  docker rm -fv "$AGENT" >/dev/null 2>&1 || true  # -v: the image's volumes too / también sus volúmenes
  for _ in $(seq 1 20); do docker inspect "$SRV" >/dev/null 2>&1 || docker inspect "$AGENT" >/dev/null 2>&1 || break; sleep 1; done
  docker network rm "$NET" >/dev/null 2>&1 || true
  [ -n "$OWN_CACHE" ] && docker volume rm "$OWN_CACHE" >/dev/null 2>&1 || true
  # Files written by root inside the containers: removed from a container too.
  # Ficheros que escribió root dentro de los contenedores: se borran también desde un contenedor.
  docker run --rm -v "$WORK:/w" --entrypoint sh "$JDK_IMAGE" -c 'rm -rf /w/meshdata /w/agent /w/agentcache /w/sinkca' >/dev/null 2>&1 || true
  rmdir "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

docker network create $PRUEBA_L "$NET" >/dev/null
# The mailbox for email and SMS codes (sink.mjs), before the server that sends to it. / El buzón de correo y SMS, antes que el servidor.
# Its certificate, made for this run, makes it an ntfy server too (messaging codes). / Su certificado lo hace también servidor ntfy.
mkdir -p "$WORK/sinkca"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=$SINK" -addext "subjectAltName=DNS:$SINK" \
  -keyout "$WORK/sinkca/sink.key" -out "$WORK/sinkca/sink.crt" >/dev/null 2>&1
chmod 644 "$WORK/sinkca/sink.key"
docker run -d --rm $PRUEBA_L --name "$SINK" --network "$NET" --cpus 0.25 --memory 128m -v "$HERE/sink.mjs:/sink.mjs:ro" -v "$WORK/sinkca:/certs:ro" node:22-alpine node /sink.mjs >/dev/null
SINK_HOST="$SINK" SINK_CA="$WORK/sinkca" "$HERE/start-test-server.sh" "$SRV" "$WORK/meshdata" "$NET" "$MESH_IMAGE" >/dev/null
SRV_IP=$(docker inspect "$SRV" --format "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}")
read -r OTP GROUP < <(docker exec -i "$SRV" node - < "$HERE/setup-test-data.js")
[ -n "${OTP:-}" ] && [ -n "${GROUP:-}" ]
"$HERE/start-test-agent.sh" "$SRV" "$NET" "$AGENT" "$WORK/agent" "$GROUP" >/dev/null
CERT=$(docker exec "$SRV" node -e "const c=require('crypto'),f=require('fs');console.log(new c.X509Certificate(f.readFileSync('/opt/meshcentral/meshcentral-data/webserver-cert-public.crt')).fingerprint256.replace(/:/g,''))")
echo "server $SRV · agent $AGENT · cert ${CERT:0:16}…"

# The tests call the server by its certificate name, as the app would. / Las pruebas llaman al servidor por el nombre de su certificado.
docker run --rm $PRUEBA_L --network "$NET" --add-host "mesh.test:$SRV_IP" --cpus 2 --memory 3g \
  -e GRADLE_USER_HOME=/gradle -e MESHTEST_URL="https://mesh.test/" -e MESHTEST_CERT_SHA256="$CERT" \
  -e MESHTEST_OTP_SECRET="$OTP" -e MESHTEST_GROUP_ID="$GROUP" -e MESHTEST_DESKTOP=1 -e MESHTEST_AGENT_HOST="$AGENT" -e MESHTEST_SINK="http://$SINK:8025" \
  -v "$ROOT:/src" -v "$GRADLE_CACHE:/gradle" -w /src "$JDK_IMAGE" \
  ./gradlew -p core test --no-daemon --rerun-tasks || {
  rc=$?
  # On failure, what the agent and the server were doing. / Si falla, qué hacían el agente y el servidor.
  echo "== agent $AGENT: $(docker inspect "$AGENT" --format 'status={{.State.Status}} exit={{.State.ExitCode}}' 2>&1)"
  docker logs --tail 40 "$AGENT" 2>&1 | sed 's/^/  agent| /'
  docker logs --tail 60 "$SRV" 2>&1 | sed 's/^/  server| /'
  exit "$rc"
}
