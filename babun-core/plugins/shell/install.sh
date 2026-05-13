#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"

src="$babun_source/babun-core/plugins/shell/src/"
dest="$babun/home/shell/"

# === MODERNIZED: silence first-install "cannot stat" stderr ===
#   Original lines kept the visible `cp: cannot stat '/etc/minttyrc'` warnings
#   on every first install. The `|| echo ""` made them non-fatal but stderr
#   leaked through. Redirected to /dev/null so the backup attempt is silent
#   when there's nothing to back up (the normal first-install case).
#   Babun's custom minttyrc/nanorc/vimrc still install on the line that
#   follows each backup attempt — your beloved mintty config is intact.
#
#   Original:
#     /bin/cp -rf /etc/minttyrc /etc/minttyrc.old  || echo ""
#     /bin/cp -rf /etc/nanorc /etc/nanorc.old  || echo ""
#     /bin/cp -rf /etc/vimrc /etc/vimrc.old  || echo ""
/bin/cp -rf /etc/minttyrc /etc/minttyrc.old 2>/dev/null || true
/bin/cp -rf $src/minttyrc /etc/minttyrc

/bin/cp -rf /etc/nanorc /etc/nanorc.old 2>/dev/null || true
/bin/cp -rf $src/nanorc /etc/nanorc

/bin/cp -rf /etc/vimrc /etc/vimrc.old 2>/dev/null || true
/bin/cp -rf $src/vimrc /etc/vimrc
# === /MODERNIZED ===

mkdir -p "$dest"
/bin/cp -rf "$src/.vim" "$dest/.vim" 
