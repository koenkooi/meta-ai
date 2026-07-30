SUMMARY = "Qualcomm CDI generator"
DESCRIPTION = "Tooling to generate CDI (Container Device Interface) config files, \
so that Qualcomm accelerator devices can be passed through into containers."
HOMEPAGE = "https://github.com/qualcomm-linux/qualcomm-CDI-generator"
SECTION = "console/utils"

LICENSE = "BSD-3-Clause"
LIC_FILES_CHKSUM = "file://LICENSE.txt;md5=223037c4be0bfc6cf757035432adf983"

DEPENDS = "systemd"

PV = "0.4"

SRC_URI = "git://github.com/qualcomm-linux/qualcomm-CDI-generator.git;protocol=https;branch=main"
SRCREV = "b1247ba69820c4fb00e9eab6854b74d28b24ec55"

inherit meson pkgconfig systemd

SYSTEMD_SERVICE:${PN} = "qualcomm-cdi-generator.service"

FILES:${PN} += "\
    ${systemd_system_unitdir}/qualcomm-cdi-generator.service \
    ${systemd_unitdir}/system-preset/10-qualcomm-cdi-generator.preset \
"

RDEPENDS:${PN} = "python3-core python3-json python3-logging"
