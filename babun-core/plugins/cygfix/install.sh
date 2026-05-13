#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"

# === MODERNIZED: plugin reduced to a no-op (was replacing critical binaries) ===
#   Original behavior overwrote four cygwin binaries with copies babun bundled
#   around 2014:
#     /bin/cp -rf $src/bin/mkpasswd_1.7.29.exe       /bin/mkpasswd.exe
#     /bin/cp -rf $src/bin/mkgroup_1.7.29.exe        /bin/mkgroup.exe
#     /bin/cp -rf $src/bin/git-remote-http_2.1.4.exe /usr/libexec/git-core/git-remote-http.exe
#     /bin/cp -rf $src/bin/git-remote-https_2.1.4.exe /usr/libexec/git-core/git-remote-https.exe
#   ...with chmod 755 on each.
#
#   Why removed: those 2014-era binaries link against Cygwin 1.7 libcurl/libssl/
#   cygwin1.dll which no longer exist in 3.x Cygwin. After cygfix ran, every
#   HTTPS git operation aborted with the cryptic "cannot open shared object
#   file" error. The bug they fixed (https://github.com/babun/babun/issues/455 —
#   a 2014 git NTLM proxy issue) does not exist in modern Cygwin's git.
# === /MODERNIZED ===

if [ ! -f "/bin/vi" ]; then
    ln -s /usr/bin/vim /bin/vi
fi
