SUMMARY = "OCI container image running llama-server (llama.cpp) with a baked-in GGUF model"
DESCRIPTION = "OCI container image bundling llama.cpp's llama-server, built with \
meta-virtualization's image-oci tooling on top of the shared meta-ai-container-base-oci \
base layer, for import into a container runtime. Unlike the litert/onnxruntime/ \
tensorflow-lite images (which ship only a runtime library for bind-mount consumption), \
this image is directly runnable: catatonit as PID 1 supervises llama-server, which \
serves an OpenAI-compatible API and web UI on port 8080. Uses OCI_LAYER_MODE = 'multi' \
with a 'llama-cpp' packages layer (busybox+catatonit+llama-cpp; base-files/base-passwd/ \
netbase/glibc/libgcc already come from the base layer) plus a 'model' directories layer \
that copies the baked-in GGUF model out of IMAGE_ROOTFS - see the model-install comment \
below for why the model can't go through OCI_LAYERS' packages type directly."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

OCI_BASE_IMAGE = "meta-ai-container-base-oci"

OCI_LAYER_MODE = "multi"
OCI_LAYERS = "llama-cpp:packages:busybox+catatonit+llama-cpp \
              model:directories:${LLAMA_MODEL_DIR}"

IMAGE_FSTYPES = "container oci"
inherit image
inherit image-oci
inherit meta_ai_strip_initial_sysroot_deps

IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
NO_RECOMMENDATIONS = "1"
IMAGE_CONTAINER_NO_DUMMY = "1"

# catatonit as PID 1 init shim; -- separates catatonit flags from the supervised program
OCI_IMAGE_ENTRYPOINT = "/usr/bin/catatonit --"
OCI_IMAGE_PORTS = "8080/tcp"
OCI_IMAGE_TAG = "latest"

# Qwen3.5-0.8B: small enough to build and boot-test quickly; swap via a
# bbappend for a larger model without touching the rest of this recipe.
LLAMA_MODEL_PACKAGE = "unsloth-qwen3p5-0p8b-gguf-bf16"
LLAMA_MODEL_FILE = "Qwen3.5-0.8B-BF16.gguf"
LLAMA_MODEL_DIR = "${datadir}/llama-cpp/models"
LLAMA_MODEL_PATH = "${LLAMA_MODEL_DIR}/${LLAMA_MODEL_FILE}"

LLAMA_HOST ?= "0.0.0.0"
LLAMA_CTX_SIZE ?= "16384"
LLAMA_TEMP ?= "1.0"
LLAMA_TOP_P ?= "0.95"
LLAMA_TOP_K ?= "20"
LLAMA_MIN_P ?= "0.00"
LLAMA_REASONING_BUDGET ?= "0"
LLAMA_EXTRA_ARGS ?= ""

OCI_IMAGE_CMD = "/usr/bin/llama-server \
    --host ${LLAMA_HOST} \
    --model ${LLAMA_MODEL_PATH} \
    --ctx-size ${LLAMA_CTX_SIZE} \
    --temp ${LLAMA_TEMP} \
    --top-p ${LLAMA_TOP_P} \
    --top-k ${LLAMA_TOP_K} \
    --min-p ${LLAMA_MIN_P} \
    --reasoning-budget ${LLAMA_REASONING_BUDGET} \
    ${LLAMA_EXTRA_ARGS}"

# The model has to land in IMAGE_ROOTFS (not just DEPLOY_DIR_IMAGE) before
# do_image_oci's 'model:directories' layer entry runs, since OCI_LAYERS'
# directories type copies from IMAGE_ROOTFS. It can't go through OCI_LAYERS'
# packages type either: huggingface-model.bbclass ships the model via
# do_deploy with a deliberately empty package (IPK/DEB's ar format caps
# individual members at ~9.3GB, so there is no real package for opkg to
# install into a layer). Reuse the same do_rootfs[depends] + \
# ROOTFS_POSTPROCESS_COMMAND wiring as bradfa's llama-cpp-container-image.bbclass.
python () {
    model_pkg = d.getVar('LLAMA_MODEL_PACKAGE')
    if model_pkg:
        d.appendVarFlag('do_rootfs', 'depends', ' %s:do_deploy' % model_pkg)
        d.appendVar('ROOTFS_POSTPROCESS_COMMAND', ' install_llama_model ; ')
}

install_llama_model () {
    install -d ${IMAGE_ROOTFS}${LLAMA_MODEL_DIR}
    install -m 0644 \
        "${DEPLOY_DIR_IMAGE}${LLAMA_MODEL_DIR}/${LLAMA_MODEL_FILE}" \
        "${IMAGE_ROOTFS}${LLAMA_MODEL_DIR}/"
}
