SUMMARY = "Import bundled AI-runtime OCI images into Docker at boot"
DESCRIPTION = "Ships the litert, onnxruntime, tensorflow-lite, and llama-cpp OCI archives \
into the rootfs and instantiates a templated systemd oneshot service per app to load each \
into Docker at boot time."
HOMEPAGE = "https://github.com/qualcomm-linux/meta-ai"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

SRC_URI = "file://meta-ai-oci-import@.service file://meta-ai-oci-import.sh"

inherit systemd meta_ai_strip_initial_sysroot_deps

# Content comes from DEPLOY_DIR_IMAGE, which is machine-specific; a plain
# tune-arch package would let two machines share a stale build of this one.
PACKAGE_ARCH = "${MACHINE_ARCH}"

METAAI_OCI_APPS = "litert onnxruntime tensorflow-lite llama-cpp"

SYSTEMD_SERVICE:${PN} = "${@' '.join('meta-ai-oci-import@%s.service' % a for a in d.getVar('METAAI_OCI_APPS').split())}"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

FILES:${PN} += "\
    ${datadir}/meta-ai/*-latest-oci.tar \
    ${systemd_system_unitdir}/meta-ai-oci-import@.service \
    ${bindir}/meta-ai-oci-import.sh \
"

RDEPENDS:${PN} = "docker"

do_compile[noexec] = "1"

# do_install here only copies prebuilt .tar files out of DEPLOY_DIR_IMAGE
# (do_compile is noexec) so it needs no target sysroot content; the
# meta_ai_strip_initial_sysroot_deps class (inherited above) keeps the
# do_install[depends] on the three ...:do_image_complete tasks below from
# dragging libgcc-initial (or similar) into a sysroot collision - see that
# class for the full analysis.
do_install[depends] += "\
    meta-ai-litert-oci:do_image_complete \
    meta-ai-onnxruntime-oci:do_image_complete \
    meta-ai-tensorflow-lite-oci:do_image_complete \
    meta-ai-llama-cpp-oci:do_image_complete \
"

do_install() {
    install -d ${D}${datadir}/meta-ai
    install -m 0644 ${DEPLOY_DIR_IMAGE}/meta-ai-litert-oci-latest-oci.tar ${D}${datadir}/meta-ai/litert-latest-oci.tar
    install -m 0644 ${DEPLOY_DIR_IMAGE}/meta-ai-onnxruntime-oci-latest-oci.tar ${D}${datadir}/meta-ai/onnxruntime-latest-oci.tar
    install -m 0644 ${DEPLOY_DIR_IMAGE}/meta-ai-tensorflow-lite-oci-latest-oci.tar ${D}${datadir}/meta-ai/tensorflow-lite-latest-oci.tar
    install -m 0644 ${DEPLOY_DIR_IMAGE}/meta-ai-llama-cpp-oci-latest-oci.tar ${D}${datadir}/meta-ai/llama-cpp-latest-oci.tar

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${UNPACKDIR}/meta-ai-oci-import@.service ${D}${systemd_system_unitdir}/meta-ai-oci-import@.service

    install -d ${D}${bindir}
    install -m 0755 ${UNPACKDIR}/meta-ai-oci-import.sh ${D}${bindir}/meta-ai-oci-import.sh
}
