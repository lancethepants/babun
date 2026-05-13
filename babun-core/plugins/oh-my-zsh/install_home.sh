#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"
source "$babun_tools/git.sh"

src="$babun/home/oh-my-zsh"

if [ ! -d "$homedir/.oh-my-zsh" ]; then
    git --git-dir="$src/.oh-my-zsh/.git" --work-tree="$src/.oh-my-zsh" reset --hard
    # === MODERNIZED: chmod is best-effort, don't kill install on perm errors ===
    #   Original:
    #     /bin/chmod 755 -R "$src/.oh-my-zsh"
    #
    #   Why: same root cause as the chmod /etc/ in core/install.sh — under a
    #   non-admin build some files (typically inside .oh-my-zsh/.git/) inherit
    #   ACLs that the build user can't modify. cp -rf reads them fine, so the
    #   chmod failure is cosmetic, but set -e would otherwise abort install_home.
    /bin/chmod 755 -R "$src/.oh-my-zsh" || echo "[babun] chmod $src/.oh-my-zsh had warnings above (non-fatal)"
    # === /MODERNIZED ===
    /bin/cp -rf "$src/.oh-my-zsh" "$homedir/.oh-my-zsh"

    # === MODERNIZED: skip cleanly if /etc/passwd doesn't exist ===
    #   Original:
    #     if grep -q "/bin/bash" "/etc/passwd"; then
    #         sed -i 's/\/bin\/bash/\/bin\/zsh/' "/etc/passwd"
    #     fi
    #
    #   Why: modern Cygwin doesn't create /etc/passwd by default — user info
    #   is read dynamically from Windows accounts via cygwin1.dll. The file
    #   only exists if mkpasswd.exe -l > /etc/passwd has been run (which the
    #   end-user install.bat does via post_extract.sh, but the build doesn't).
    #   Adding the existence check prevents the noisy "grep: /etc/passwd: No
    #   such file or directory" stderr without changing functionality.
    if [ -f "/etc/passwd" ] && grep -q "/bin/bash" "/etc/passwd"; then
   		sed -i 's/\/bin\/bash/\/bin\/zsh/' "/etc/passwd"
 	fi
    # === /MODERNIZED ===
fi


if [ ! -f "$homedir/.zshrc" ]; then
	/bin/cp "$babun/home/.zshrc" "$homedir/.zshrc"

	# === MODERNIZED: completion-cache prewarm is best-effort ===
	#   Original (without `|| true`):
	#     zsh -c "source ~/.zshrc; rm -f ...; compinit -u" &> /dev/null
	#     zsh -c "source ~/.zshrc; cat \"$homedir/.zcompdump\" > \"$homedir/.zcompdump-\"*" &> /dev/null
	#
	#   Why: pre-populating the zsh completion cache is a nice-to-have, not
	#   required. The second command's glob (`.zcompdump-*`) only resolves
	#   correctly after compinit creates the file, and modern zsh + oh-my-zsh
	#   sometimes can't create it in a non-interactive sourced context. End-
	#   user shells regenerate the cache lazily on first interactive start,
	#   so failure here is harmless. Without `|| true`, set -e killed the
	#   whole install_home.sh on this cosmetic step.
	zsh -c "source ~/.zshrc; rm -f \"$homedir/.zcompdump\"; compinit -u" &> /dev/null || true
	zsh -c "source ~/.zshrc; cat \"$homedir/.zcompdump\" > \"$homedir/.zcompdump-\"*" &> /dev/null || true
	# === /MODERNIZED ===
fi

if [[ "$installed_version" -le 1 ]]; then   
    git --git-dir="$homedir/.oh-my-zsh/.git" --work-tree="$homedir/.oh-my-zsh" config core.trustctime false
    git --git-dir="$homedir/.oh-my-zsh/.git" --work-tree="$homedir/.oh-my-zsh" config core.autocrlf false
    git --git-dir="$homedir/.oh-my-zsh/.git" --work-tree="$homedir/.oh-my-zsh" rm --cached -r . > /dev/null
    git --git-dir="$homedir/.oh-my-zsh/.git" --work-tree="$homedir/.oh-my-zsh" reset --hard
fi
