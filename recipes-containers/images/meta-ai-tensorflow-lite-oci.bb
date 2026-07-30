SUMMARY = "OCI container image bundling the TensorFlow Lite AI runtime"
DESCRIPTION = "OCI container image bundling the TensorFlow Lite AI runtime, built with \
meta-virtualization's image-oci tooling on top of the shared meta-ai-container-base-oci \
base layer, for import into a container runtime. Uses OCI_LAYER_MODE = 'multi' with a \
single 'tensorflow-lite' packages layer rather than single-layer mode's whole-rootfs \
rsync: opkg installs tensorflow-lite's full RDEPENDS chain (glibc, gcc-runtime, abseil) \
into this layer's own scratch rootfs, then umoci's repack diffs it against the \
already-unpacked base bundle. With \
patches/0003-image-oci-rsync-checksum-no-times-cross-image-dedup.patch applied, that \
diff is content-based (rsync -a --checksum --no-times) rather than mtime-based, so the \
RDEPENDS content already provided by the base collapses to nothing and only \
tensorflow-lite's own files land in this layer's blob - verified by digest, see \
reports/container-host-image-build-report.md."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

OCI_BASE_IMAGE = "meta-ai-container-base-oci"

OCI_LAYER_MODE = "multi"
OCI_LAYERS = "tensorflow-lite:packages:tensorflow-lite"

IMAGE_FSTYPES = "container oci"
inherit image
inherit image-oci
inherit meta_ai_strip_initial_sysroot_deps

IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
NO_RECOMMENDATIONS = "1"
IMAGE_CONTAINER_NO_DUMMY = "1"

# No entrypoint/shell: this image ships only the tensorflow-lite library,
# meant to be consumed via bind-mount by another container rather than run
# directly.
OCI_IMAGE_TAG = "latest"
