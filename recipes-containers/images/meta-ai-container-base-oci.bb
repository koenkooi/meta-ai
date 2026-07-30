SUMMARY = "Minimal base OCI layer shared by the per-app AI-runtime container images"
DESCRIPTION = "Minimal base OCI layer shared by the litert, onnxruntime, and \
               tensorflow-lite container images via OCI_BASE_IMAGE, so their common \
               content is stored once on the target instead of once per app. Uses \
               OCI_LAYER_MODE = 'multi' with a single 'base' packages layer: each app \
               image's own layer diffs against this base's already-unpacked bundle \
               (patches/0003-image-oci-rsync-checksum-no-times-cross-image-dedup.patch \
               makes that diff genuinely content-based rather than mtime-based), so \
               glibc/gcc-runtime/the abseil libs named below are stored once on target \
               and shared by digest across all three app images - verified via direct \
               blob inspection, see reports/container-host-image-build-report.md. \
               libstdc++ is deliberately NOT listed here: OCI_LAYERS' 'packages:' syntax \
               joins package names with a literal '+' and has no escape for a '+' inside \
               a package name, so 'libgcc+libstdc++' silently mis-splits into 'libgcc' \
               and 'libstdc' (dropping the trailing '++') - a genuine image-oci.bbclass \
               limitation, not something fixable from this recipe. It still ends up in \
               this shared layer transitively via abseil's RDEPENDS, so nothing is \
               duplicated in practice, but that is a property of this package set, not \
               of the design."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

OCI_LAYER_MODE = "multi"
OCI_LAYERS = "base:packages:base-files+base-passwd+netbase+glibc+libgcc\
+libabsl-strings2605.0.0+libabsl-strings-internal2605.0.0\
+libabsl-raw-logging-internal2605.0.0+libabsl-throw-delegate2605.0.0"

IMAGE_FSTYPES = "container oci"
inherit image
inherit image-oci
inherit meta_ai_strip_initial_sysroot_deps

IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
NO_RECOMMENDATIONS = "1"
IMAGE_CONTAINER_NO_DUMMY = "1"

OCI_IMAGE_CMD = ""
OCI_IMAGE_ENTRYPOINT = "/bin/sh"
OCI_IMAGE_TAG = "latest"
