#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"

# Historically this plugin overwrote four binaries (mkpasswd, mkgroup,
# git-remote-http, git-remote-https) with copies from Cygwin 1.7.29 / git 2.1.4
# era to work around a Git NTLM-proxy bug (babun#455) and some account-handling
# quirks. Those bundled binaries link against ancient versions of cygwin1.dll
# and libcurl/libssl that no longer exist in modern (3.x+) Cygwin, and
# installing them breaks HTTPS git clones with a cryptic "cannot open shared
# object file" loader error. The modern Cygwin packages don't have the old
# bugs, so we keep the stock binaries.

if [ ! -f "/bin/vi" ]; then
    ln -s /usr/bin/vim /bin/vi
fi
