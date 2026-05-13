#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"

# === MODERNIZED: plugin reduced to a no-op (path no longer exists) ===
#   Original behavior:
#     cd /usr/ssl/certs
#     curl http://curl.haxx.se/ca/cacert.pem | \
#         awk 'split_after==1{n++;split_after=0} /-----END CERTIFICATE-----/ \
#         {split_after=1} {print > "cert" n ".pem"}'
#
#   Why removed: /usr/ssl/certs/ was the OpenSSL 1.x certs dir; it doesn't
#   exist in modern Cygwin. The current `ca-certificates` Cygwin package
#   maintains a bundle at /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem
#   and refreshes it through normal Cygwin package updates (pact update).
#   The original `cd` failed under `set -e -f -o pipefail` and aborted the
#   plugin install loop.
# === /MODERNIZED ===

echo "cacert: CA bundle is provided by Cygwin's ca-certificates package — nothing to do"
