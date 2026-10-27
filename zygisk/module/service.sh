# Extract the directory path and change directory 
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# A root manager runs this script with its own busybox, but a manual bootstrap has nothing in
# front of it except Android's utilities, so this is written for toybox's unshare: it makes a
# mount namespace with -m and takes no propagation flag. The namespace therefore marks its own
# root private before removing inherited wrapper mounts. A slave namespace would still
# receive the next wrapper mount from init, including when the daemon mounts it there itself.
unshare -m /system/bin/sh -c '
    . "$0/dex2oat-namespace.sh" || exit 1
    vector_prepare_dex2oat_namespace "$0" || exit 1
    exec "$0/daemon" "$@"
' "$MODDIR" "$@" &
