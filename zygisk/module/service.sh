# Extract the directory path and change directory 
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# Keep the daemon's dex2oat bind-mounts inside a private mount namespace. Toybox unshare
# creates one with -m but takes no propagation flag, so the namespace marks its own root
# slave before handing over to the daemon.
unshare -m /system/bin/sh -c '
    mount -o rslave none / || echo "vector: this mount namespace stays shared" >&2
    exec "$0" "$@"
' "$MODDIR/daemon" "$@" &
