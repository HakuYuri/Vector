# Sourced by service.sh inside its new mount namespace, before app_process starts.
# No global mounts are changed here. Dex2OatServer's native mount helper deliberately
# enters init's namespace later, after the daemon has opened the original compilers.

vector_dex2oat_error() {
    echo "vector: dex2oat recovery: $*" >&2
    return 1
}

vector_restore_compiler_view() (
    target=$1
    if [ ! -e "$target" ]; then
        # Optional architectures/debug compilers can be absent, but not dangling links.
        [ ! -L "$target" ] || vector_dex2oat_error "dangling compiler link: $target"
        return $?
    fi
    [ -f "$target" ] || { vector_dex2oat_error "not a regular compiler: $target"; return 1; }

    # A stock compiler lives on its APEX filesystem. A file bind-mounted from the
    # module lives on /data instead. Do not guess a versioned APEX directory, which
    # may belong to a pending update rather than the active runtime.
    apex_device=$(stat -Lc '%d' "${target%/*}") || return 1
    wrapper32_id=
    wrapper64_id=
    if [ -f "$2" ]; then
        wrapper32_id=$(stat -Lc '%d:%i' "$2") || return 1
    fi
    if [ -f "$3" ]; then
        wrapper64_id=$(stat -Lc '%d:%i' "$3") || return 1
    fi
    layers=0
    while :; do
        [ -f "$target" ] || return 1
        target_id=$(stat -Lc '%d:%i' "$target") || return 1
        [ -n "$target_id" ] && [ -n "$apex_device" ] || return 1
        # Android 10's unsuffixed dex2oat may be either ELF class, so recognize
        # both wrappers by identity instead of guessing the ABI from its name.
        if [ "$target_id" = "$wrapper32_id" ] || [ "$target_id" = "$wrapper64_id" ]; then
            [ "$layers" -lt 8 ] || {
                vector_dex2oat_error "too many wrapper mounts: $target"; return 1;
            }
            # Only this exact, positively identified file mount. Never the APEX
            # itself, and never a lazy/recursive unmount.
            umount "$target" || { vector_dex2oat_error "cannot unmount $target"; return 1; }
            layers=$((layers + 1))
        elif [ "${target_id%:*}" = "$apex_device" ]; then
            return 0
        else
            # Includes deleted/replaced wrappers from another module revision.
            # Their identity cannot be established with the installed binary.
            vector_dex2oat_error "unrecognized compiler mount: $target; reboot after updating the module"
            return 1
        fi
    done
)

vector_prepare_dex2oat_namespace() (
    module_dir=$1
    own_ns=$(readlink /proc/self/ns/mnt) || return 1
    init_ns=$(readlink /proc/1/ns/mnt) || return 1
    if [ -z "$own_ns" ] || [ -z "$init_ns" ] || [ "$own_ns" = "$init_ns" ]; then
        vector_dex2oat_error 'refusing to change mounts in the init namespace'
        return 1
    fi
    mount -o rprivate none / || {
        vector_dex2oat_error 'cannot isolate mount propagation'; return 1;
    }

    # Android 10 uses runtime; Android 11+ uses art. Missing compilers are normal
    # on older Android releases and on devices without a secondary ABI.
    for apex in /apex/com.android.runtime /apex/com.android.art; do
        for name in dex2oat dex2oatd dex2oat32 dex2oatd32 dex2oat64 dex2oatd64; do
            vector_restore_compiler_view "$apex/bin/$name" \
                "$module_dir/bin/dex2oat32" "$module_dir/bin/dex2oat64" || return 1
        done
    done
)
