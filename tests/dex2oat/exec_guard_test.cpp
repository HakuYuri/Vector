#include "exec_guard.h"

#include <cassert>
#include <sys/wait.h>

int main(int argc, char **argv) {
    using namespace vector_dex2oat;
    if (argc > 1) {
        // This is a real exec, not just two calls in the first process image.
        return enter_exec_guard() ? 1 : 0;
    }

    unsetenv(kExecGuard);
    assert(enter_exec_guard());
    assert(!enter_exec_guard());

    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) _exit(enter_exec_guard() ? 0 : 1);
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);

    // A stale or foreign marker must not block a new compiler process.
    setenv(kExecGuard, "not-a-pid", 1);
    assert(enter_exec_guard());
    execl(argv[0], argv[0], "reexec", nullptr);
    return 2;
}
