# dex2oat restart regression

## Observed failure

The failure was observed on a **vivo X300 Ultra** (user-reported retail model),
Android 16, KernelSU, Zygisk Next 1.5.2 and Vector v2.2. This is the observed
environment, not a claim that the bug is vivo-specific.

After a manual daemon recovery with the wrapper file bind mounts still present,
the daemon's saved "stock" compiler descriptors pointed to Vector's wrappers.
ELF magic/class checks alone could not distinguish them. The wrapper received
itself through SCM_RIGHTS, copied another hook library into a memfd, appended the
inline flag and exec'd itself through the linker. The PID stayed the same while
open memfds and arguments accumulated. One compiler task ran until artd's
570-second timeout; memory pressure subsided when the task exited.

A device-specific v2.2 mitigation restored the original compiler view in a
private namespace and guarded the wrapper. A subsequent app verification compile
completed in 92 ms. This is a functional check, **not a before/after benchmark**,
and it does not establish the cause of a separately reported historical reboot.
That mitigation is not the same binary as this branch, which is based on newer
upstream code. No serial numbers, account information or device logs are included.

## Fix in this branch

* Before starting the daemon, `service.sh` prepares a recursively private mount
  namespace. The helper refuses init's namespace and stops if isolation fails.
* For each supported compiler name, it peels only file mounts whose device/inode
  match one of the installed wrappers, with a bound of eight layers. The exposed
  compiler must be a regular file on its active APEX bin directory's filesystem.
  There is no hard-coded APEX version, device identifier or socket name override.
* Unknown mounts (including a deleted wrapper from another revision) stop
  recovery instead of being unmounted or handed out as stock. A module update
  with such old mounts requires the normal reboot, not blind live recovery.
  Users with temporary root must arrange to obtain root again after that reboot.
* A PID marker stops wrapper re-exec before IPC and memfd creation. It permits a
  new process inheriting the environment. This is a safety guard, not a security
  boundary against a privileged process deliberately changing the environment.
* Invalid compiler/hook descriptors fail instead of continuing to exec.

No global APEX mounts are removed by the new helper. Vector's existing native
mount helper still intentionally enters init's namespace to install the wrappers
after the original descriptors have been opened.

## Host checks

```sh
python3 -m unittest discover -s tests/dex2oat -v
sh -n zygisk/module/service.sh
sh -n zygisk/module/dex2oat-namespace.sh
sh -n zygisk/module/customize.sh
git diff --check
```

The tests compile the actual C++ guard and exercise fork and exec. Shell tests
mock namespace IDs, stat and mount operations; **they do not prove real kernel
mount behavior or Android SELinux compatibility**. They cover clean startup,
known/stacked mounts, unknown and missing files, failed namespace isolation,
unmount failures, bounded retries, and Android 10's ambiguous compiler names.
The Core workflow runs these checks before building the module.

The modified wrapper also cross-compiles for ARMv7, ARM64, x86 and x86_64 with
the Android NDK. Compilation alone is not runtime validation on all four ABIs.

## Full build and device validation

Use the JDK/SDK/NDK versions pinned by the upstream build, initialize recursive
submodules, and run `./gradlew :zygisk:zipRelease` (or `./gradlew zipAll`). A local
build without a release keystore uses the local debug signing certificate for
the manager; it is **not signed by the upstream maintainer**. Keep its matching
daemon and manager together. Do not assume it can update an independently
installed upstream-signed manager without a signature conflict.

Before claiming this branch is device-validated, on a test device:

1. Save module/config backups and arrange root recovery if needed. Install the
   complete matching build through the root manager's normal workflow.
2. Confirm initial startup, saved compiler descriptors, and a successful app
   compile; check that the global paths expose the wrappers.
3. Restart the daemon through `service.sh` without removing its existing file
   mounts. Confirm its saved FDs still point to stock compilers, init's mounts
   are unchanged by private cleanup, and a new compile completes normally.
4. Test an intentionally invalid descriptor/re-entry only in an isolated test
   harness. Never reproduce the old unbounded loop on a daily-use phone.

The user's currently working phone is intentionally left on the previously
validated device-specific mitigation during this source preparation.
