#pragma once

#include <cstdlib>
#include <string>
#include <unistd.h>

namespace vector_dex2oat {

inline constexpr char kExecGuard[] = "VECTOR_DEX2OAT_GUARD_PID";

// execve preserves the PID and environment, unlike a new compiler process.
// /proc/self/exe cannot identify the wrapper reliably: it can be launched through
// the dynamic linker with /proc/self/fd/N as its argument.
inline bool enter_exec_guard() {
    const auto pid = std::to_string(getpid());
    const char *previous = std::getenv(kExecGuard);
    if (previous && pid == previous) return false;
    return setenv(kExecGuard, pid.c_str(), 1) == 0;
}

}  // namespace vector_dex2oat
