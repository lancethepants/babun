#!/bin/bash
set -e -f -o pipefail
source "/usr/local/etc/babun.instance"
source "$babun_tools/script.sh"

# fix symlinks on local instance
/bin/dos2unix.exe /etc/postinstall/symlinks_repair.sh

/bin/chmod 755 /etc/postinstall/symlinks_repair.sh
/etc/postinstall/symlinks_repair.sh
/bin/mv.exe /etc/postinstall/symlinks_repair.sh /etc/postinstall/symlinks_repair.sh.done

# regenerate user/group information
/bin/rm -rf /home

echo "[babun] HOME set to $HOME"

if [[ ! "$HOME" == /cygdrive* ]]; then
	echo "[babun] Running mkpasswd for CYGWIN home"
	# regenerate users' info
	/bin/mkpasswd.exe -l -c > /etc/passwd	

	# remove spaces in username and user home folder (sic!)
	# xuser=${USERNAME//[[:space:]]}
	# xhome="\/home\/"
	# /bin/sed -e "s/$USERNAME/$xuser/" -e "s/$xhome$USERNAME/$xhome$xuser/" -i /etc/passwd
else
	echo "[babun] Running mkpasswd for WINDOWS home"
	# regenerate users' info using windows paths
	/bin/mkpasswd -l -c -p"$(/bin/cygpath -H)" > /etc/passwd
fi
/bin/mkgroup -l -c > /etc/group

# === MODERNIZED: chmod failures are non-fatal ===
#   Original:
#     /bin/chmod 755 -R /usr/local
#     /bin/chmod u+rwx -R /etc
#
#   Why: under non-admin install, some files in /etc/ have Windows ACLs the
#   running user can't modify (same as the chmod in core/install.sh). The
#   original behavior aborted post_extract under `set -e`, which then caused
#   install.bat to print "Terminating due to internal error #1" and never
#   reach the :RUN section that auto-opens the babun terminal.
# fix file permissions in /usr/local
/bin/chmod 755 -R /usr/local || echo "[babun] chmod /usr/local had warnings above (non-fatal)"
/bin/chmod u+rwx -R /etc || echo "[babun] chmod /etc had warnings above (non-fatal)"
# === /MODERNIZED ===
