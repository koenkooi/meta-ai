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
+libabsl-strings+libabsl-strings-internal\
+libabsl-raw-logging-internal+libabsl-throw-delegate"

IMAGE_FSTYPES = "container oci"
inherit image
inherit image-oci
inherit meta_ai_strip_initial_sysroot_deps

# abseil-cpp's shared-lib sub-packages above are pre-Debian-rename names (as
# glibc/libgcc already are, for the parse-time reason in DESCRIPTION); their
# real opkg name embeds a SOVERSION derived from whichever abseil-cpp provider
# wins on a given MACHINE (meta-openembedded's vs. meta-qcom's dynamic-layer
# override, pinned per-machine via PREFERRED_VERSION_abseil-cpp) - e.g.
# libabsl-strings is libabsl-strings2605.0.0 on rb1-core-kit but
# libabsl-strings2601.0.0 on qemuarm64. do_rootfs already renames
# PACKAGE_INSTALL via oe.packagedata.runtime_mapping_rename before installing,
# so it picks up whichever is real; do_image_oci's own layer-package installer
# does not, so do it here from real PKGDATA before it runs.
python meta_ai_resolve_oci_layer_package_names () {
    import oe.packagedata

    layers = (d.getVar('OCI_LAYERS') or '').split()
    changed = False
    resolved = []
    for layer_def in layers:
        parts = layer_def.split(':')
        if len(parts) >= 3 and parts[1] == 'packages':
            renamed = []
            for pkg in ':'.join(parts[2:]).split('+'):
                real = pkg
                if oe.packagedata.has_subpkgdata(pkg, d):
                    real = oe.packagedata.read_subpkgdata_dict(pkg, d).get('PKG') or pkg
                changed = changed or real != pkg
                renamed.append(real)
            layer_def = '%s:packages:%s' % (parts[0], '+'.join(renamed))
        resolved.append(layer_def)

    if changed:
        bb.note("meta-ai-container-base-oci: resolved OCI_LAYERS packages to their "
                 "real (post-rename) names: %s" % ' '.join(resolved))
        d.setVar('OCI_LAYERS', ' '.join(resolved))
}
do_image_oci[prefuncs] =+ "meta_ai_resolve_oci_layer_package_names "

IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
NO_RECOMMENDATIONS = "1"
IMAGE_CONTAINER_NO_DUMMY = "1"

OCI_IMAGE_CMD = ""
OCI_IMAGE_ENTRYPOINT = "/bin/sh"
OCI_IMAGE_TAG = "latest"
