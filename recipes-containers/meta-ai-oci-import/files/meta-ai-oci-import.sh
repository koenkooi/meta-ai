#!/bin/sh
# A bare OCI archive has no repository field (only a tag ref), so it loads as
# "latest:latest"; retag it with a real name. Resolve the image ID rather
# than the "latest:latest" name itself, since that name can't be looked up
# reliably across docker versions/image-store backends.
#
# All app instances of this script run concurrently at boot (they're
# separate systemd template units with no ordering between each other).
# Running `docker load` concurrently against the shared base layer races on
# containerd's content store (confirmed: concurrent loads intermittently fail
# mid-unpack with "content digest ... not found", and the losing instances'
# retag ends up pointing at whichever image happened to finish last, not
# their own). Serialize with a simple mkdir-based lock instead of flock,
# since flock isn't guaranteed to be packaged into a minimal rootfs.
LOCKDIR=/run/meta-ai-oci-import.lock
while ! mkdir "${LOCKDIR}" 2>/dev/null; do
    sleep 0.2
done
trap 'rmdir "${LOCKDIR}"' EXIT

set -e

APP="$1"
if [ -z "${APP}" ]; then
    echo "usage: $0 <app-name>" >&2
    exit 1
fi

ARCHIVE="/usr/share/meta-ai/${APP}-latest-oci.tar"
LOAD_OUTPUT=$(docker load -i "${ARCHIVE}")
echo "${LOAD_OUTPUT}"

IMAGE_ID=$(echo "${LOAD_OUTPUT}" | sed -n 's/^Loaded image ID: sha256:\(.*\)$/\1/p')

if [ -z "${IMAGE_ID}" ]; then
    LOADED_REF=$(echo "${LOAD_OUTPUT}" | sed -n 's/^Loaded image: //p')
    IMAGE_ID=$(docker images --format '{{.ID}} {{.Repository}}:{{.Tag}}' 2>/dev/null \
        | awk -v ref="${LOADED_REF}" '$2 == ref { print $1; exit }')
fi

if [ -z "${IMAGE_ID}" ]; then
    IMAGE_ID=$(docker images | awk '$1 == "latest" { print $2; exit }')
fi

if [ -z "${IMAGE_ID}" ]; then
    echo "meta-ai-oci-import: could not resolve the image ID loaded from ${ARCHIVE}" >&2
    exit 1
fi

docker tag "${IMAGE_ID}" "meta-ai-${APP}:latest"

# Remove the stale "latest:latest" reference now, under the lock: with
# serialized loads there is no longer a "next app's load reassigns it"
# guarantee to rely on, and leaving it around risks a later `docker images`
# grep-by-repo-name match ambiguously against more than one image.
docker rmi latest:latest >/dev/null 2>&1 || true
