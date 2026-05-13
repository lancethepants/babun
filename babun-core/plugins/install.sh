#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"

source "$babun_tools/script.sh"
source "$babun_tools/plugins.sh"

# prepare the environment
mkdir -p "$babun/home"
mkdir -p "$babun/external"
mkdir -p "$babun/installed"

# fix the symlinks if necessary
bash "$babun_tools/fix_symlinks.sh"

# install plugins
plugin_install "dist"
plugin_install "core"
plugin_install "cygfix"
plugin_install "shell"
plugin_install "pact"
plugin_install "cacert"
plugin_install "oh-my-zsh"
plugin_install "git"
plugin_install "cygdrive"
plugin_install "ack"

# === MODERNIZED: explicit install_home after all plugin install.sh have run ===
#   The original design relied on babun.rc's auto-install (triggered while core's
#   install.sh sourced babun.rc) to populate the build user's ~/. That fired too
#   early — other plugins' install.sh hadn't populated $babun/home/<plugin>/
#   templates yet — so install_home failed on missing .vim, .oh-my-zsh, etc.
#   The build user's ~/ ended up partial (no `omz` command, no .vim configs).
#   We now call install_home explicitly at the end of this loop with all
#   plugin templates in place. babun.rc's auto-install is suppressed during
#   build via BABUN_BUILD=1 (see install.sh / babun.rc).
echo "Running install_home for all plugins (post-template population)"
bash "$babun_plugins/install_home.sh"
# === /MODERNIZED ===
