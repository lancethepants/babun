#!/bin/bash
# IMPORTANT NOTE!
# DO NOT MODIFY THIS FILE -> IT WILL BE OVERWRITTEN ON UPDATE
# If you want to some options modify the following file: ~/.zshrc
source /etc/zprofile
test -f "$homedir/.zprofile" && source "$homedir/.zprofile"
source "/usr/local/etc/babun.instance"

# disable oh-my-zsh auto updates
export DISABLE_AUTO_UPDATE="true"

# === MODERNIZED: skip oh-my-zsh's group-writable compaudit complaint ===
#   Cygwin's default umask leaves directories group-writable (NTFS ACL quirk),
#   which makes oh-my-zsh's compaudit print a warning every time the shell
#   starts: "Insecure completion-dependent directories detected".
#   Disabling compfix tells oh-my-zsh to trust the directories anyway.
#   The other option is `umask 022` here, but that affects every future
#   directory the user creates, which is more invasive than necessary.
export ZSH_DISABLE_COMPFIX="true"
# === /MODERNIZED ===

unsetopt promptcr

# overwrite values with user's local settings
test -f "$homedir/.babunrc" && source "$homedir/.babunrc"
