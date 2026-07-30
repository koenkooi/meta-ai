# Flashable container host image for rb1-core-kit (meta-qcom); ext4/qcomflash types from kas ci/base.yml
SUMMARY = "core-image-base plus Docker, the Qualcomm CDI generator, and the cdi CLI"
DESCRIPTION = "core-image-base plus Docker, the Qualcomm CDI generator, and the cdi CLI, \
targeting rb1-core-kit as a container host."

LICENSE = "MIT"

IMAGE_FEATURES += "splash"

inherit core-image

IMAGE_INSTALL:append = " \
    docker \
    qualcomm-cdi-generator \
    cdi \
    meta-ai-oci-import \
"
