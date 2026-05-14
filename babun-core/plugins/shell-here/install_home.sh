set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"

# === MODERNIZED: skip "Open Babun here" context-menu registry install ===
#   Original:
#     pact install chere || echo "Installing 'chere' failed..."
#     "$babun_plugins/shell-here/exec.sh" init
#
#   Why: this fork doesn't want HKCU registry entries auto-added during
#   install. chere is also already in the cygwin package list so the pact
#   install is redundant noise. End users who DO want the context menu can
#   opt in with: babun shell-here init
#   And remove existing entries with:                babun shell-here remove
# === /MODERNIZED ===