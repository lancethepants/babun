#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"

# Modern Cygwin ships the `ca-certificates` package, which maintains the CA
# trust bundle at /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem and updates
# it through normal package upgrades. The old approach of fetching cacert.pem
# from curl.haxx.se and splitting it into /usr/ssl/certs/ is obsolete and that
# directory no longer exists. Kept as a no-op for plugin-loop compatibility.
echo "cacert: CA bundle is provided by Cygwin's ca-certificates package — nothing to do"
