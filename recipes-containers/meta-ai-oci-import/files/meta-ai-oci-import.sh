#!/bin/sh
# Imports one AI-runtime OCI image archive, baked into the rootfs at build
# time (never fetched over the network), into Docker at boot. "$1" is the
# app name; the archive lives at /usr/share/meta-ai/${APP}-latest-oci.tar,
# installed there by this package's own do_install.
#
# Idempotency is content-addressed: a sha256 of the ARCHIVE (not just "does
# the tag exist") is stamped at /var/lib/meta-ai-oci-import/${APP}.sha256
# once the import that produced it has fully succeeded. Without this, every
# boot re-loaded and re-tagged all four images unconditionally, even when
# nothing had changed. That directory is also this script's own persistent
# log location (the rootfs here is plain read-write, no volatile-binds
# override, so /var/lib is genuinely persistent -- unlike /var/log on some
# images). One rotation: this boot's log plus the last.
APP="$1"
if [ -z "${APP}" ]; then
    echo "usage: $0 <app-name>" >&2
    exit 1
fi

STAMP_DIR=/var/lib/meta-ai-oci-import
ARCHIVE="/usr/share/meta-ai/${APP}-latest-oci.tar"
STAMP="${STAMP_DIR}/${APP}.sha256"
TAG="meta-ai-${APP}:latest"
LOCKFILE=/run/meta-ai-oci-import.lock

mkdir -p "${STAMP_DIR}"
[ -s "${STAMP_DIR}/${APP}.log" ] && mv -f "${STAMP_DIR}/${APP}.log" "${STAMP_DIR}/${APP}.log.1"
exec >>"${STAMP_DIR}/${APP}.log" 2>&1

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

log "starting import for ${APP}"

[ -f "${ARCHIVE}" ] || { log "archive not found: ${ARCHIVE}"; exit 1; }

# sha256sum here is busybox's applet (CONFIG_SHA256SUM=y, unconditionally
# merged into oe-core's busybox defconfig via sha256sum.cfg) -- no extra
# RDEPENDS needed, same as the mkdir/sed/awk/date already used below.
WANT=$(sha256sum "${ARCHIVE}" | awk '{print $1}')
log "archive=${ARCHIVE} size=$(wc -c <"${ARCHIVE}") sha256=${WANT}"

# The stamp alone is not enough: a wiped Docker storage dir plus a surviving
# stamp must not look like "done". Only stamp-matches-AND-image-present
# counts as satisfied. Checked both before and after the lock -- another
# instance may finish while this one is waiting to acquire it.
guard_ok() {
    [ -r "${STAMP}" ] && [ "$(cat "${STAMP}")" = "${WANT}" ] && docker image inspect "${TAG}" >/dev/null 2>&1
}

if guard_ok; then
    log "stamp matches and ${TAG} present, nothing to do"
    exit 0
fi

set -e

cleanup() {
    [ -n "${HB_PID:-}" ] && kill "${HB_PID}" 2>/dev/null
    [ -n "${HB_OUT:-}" ] && rm -f "${HB_OUT}"
}
trap cleanup EXIT INT TERM HUP

# All four app instances run concurrently at boot (separate systemd template
# units with no ordering between each other) and would otherwise race on
# Docker's shared content store. busybox's flock (CONFIG_FLOCK=y in oe-core's
# busybox defconfig, confirmed default) gives a kernel-released lock: unlike
# a mkdir-based lock, it cannot be left stale by a killed holder, so one
# crashed instance can't wedge the rest of this boot's imports. Bounded
# wait: a timeout is a logged failure, not a silent spin.
exec 9>"${LOCKFILE}"
flock -w 1800 9 || { log "lock wait timed out after 1800s"; exit 1; }

if guard_ok; then
    log "stamp matches and ${TAG} present (settled while we waited for the lock), nothing to do"
    exit 0
fi

# Capture before anything changes, so the superseded image can be removed
# precisely by ID once the new one is in place.
OLD_ID=$(docker image inspect --format '{{.Id}}' "${TAG}" 2>/dev/null) || OLD_ID=""

# Runs "$@" in the background and logs progress every 15s (elapsed time,
# pid), so a stalled `docker load` (large image, slow flash) is visible in
# the log instead of silent until it either finishes or the unit times out.
with_heartbeat() {
    HB_OUT=$(mktemp /tmp/meta-ai-oci-import.XXXXXX)
    "$@" >"${HB_OUT}" &
    child=$!
    ( start=$(date +%s); n=0
      while kill -0 "${child}" 2>/dev/null; do
          sleep 1
          n=$((n + 1))
          [ "${n}" -lt 15 ] && continue
          n=0
          log "heartbeat: elapsed=$(( $(date +%s) - start ))s pid=${child}"
      done
    ) &
    HB_PID=$!
    if wait "${child}"; then rc=0; else rc=$?; fi
    kill "${HB_PID}" 2>/dev/null; wait "${HB_PID}" 2>/dev/null
    HB_PID=""
    return "${rc}"
}

if with_heartbeat docker load -i "${ARCHIVE}"; then load_rc=0; else load_rc=$?; fi
LOAD_OUTPUT=$(cat "${HB_OUT}"); rm -f "${HB_OUT}"; HB_OUT=""
log "${LOAD_OUTPUT}"
[ "${load_rc}" -ne 0 ] && { log "docker load failed rc=${load_rc}"; exit 1; }

IMAGE_ID=$(echo "${LOAD_OUTPUT}" | sed -n 's/^Loaded image ID: sha256:\(.*\)$/\1/p')

if [ -z "${IMAGE_ID}" ]; then
    LOADED_REF=$(echo "${LOAD_OUTPUT}" | sed -n 's/^Loaded image: //p')
    IMAGE_ID=$(docker images --format '{{.ID}} {{.Repository}}:{{.Tag}}' 2>/dev/null \
        | awk -v ref="${LOADED_REF}" '$2 == ref { print $1; exit }')
fi

if [ -z "${IMAGE_ID}" ]; then
    IMAGE_ID=$(docker images --format '{{.ID}} {{.Repository}}:{{.Tag}}' 2>/dev/null \
        | awk '$2 == "latest:latest" { print $1; exit }')
fi

if [ -z "${IMAGE_ID}" ]; then
    log "could not resolve the image ID loaded from ${ARCHIVE}"
    exit 1
fi

# Tag the immutable version name first, then move :latest onto it. That
# order means a failure mid-sequence never leaves :latest naming nothing --
# the image is already reachable by its version tag before :latest moves.
# Version is derived from the archive hash, not the recipe PV, so it can't
# go stale relative to content the way a PV-keyed tag could.
VERSION_TAG="meta-ai-${APP}:sha256-$(echo "${WANT}" | cut -c1-12)"
docker tag "${IMAGE_ID}" "${VERSION_TAG}"
docker tag "${IMAGE_ID}" "${TAG}"
log "tagged ${IMAGE_ID} as ${VERSION_TAG} and ${TAG}"

# A bare OCI archive has no repository field, so a load can leave it as
# "latest:latest". Clean up any stray bare tag under the lock, then assert
# it is actually gone.
docker rmi latest:latest >/dev/null 2>&1 || true
if docker image inspect latest:latest >/dev/null 2>&1; then
    log "stray bare 'latest:latest' tag survived cleanup"
    exit 1
fi

# Remove the superseded image now that the new one is fully in place. Not
# -f: a container may still be running against it (no-reboot upgrade case),
# and a failed rmi there is expected, not fatal.
if [ -n "${OLD_ID}" ] && [ "${OLD_ID}" != "${IMAGE_ID}" ]; then
    docker rmi "${OLD_ID}" || log "superseded image ${OLD_ID} not removed (in use?)"
fi

# Written last, atomically, only once every step above succeeded. A crash
# or timeout anywhere before this point leaves no stamp, so the next boot
# re-imports -- writing it any earlier would reproduce the original
# always-reload bug in a new form (skipped forever after a half-finished
# import).
mkdir -p "${STAMP_DIR}"
printf '%s\n' "${WANT}" >"${STAMP}.new" && mv "${STAMP}.new" "${STAMP}"
log "done: ${TAG} -> ${IMAGE_ID}"
