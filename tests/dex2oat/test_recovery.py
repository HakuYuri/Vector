"""Host regression tests; mount/stat mocks never touch the host's mount table."""

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "zygisk/module/dex2oat-namespace.sh"


class ExecGuardTest(unittest.TestCase):
    def test_exec_is_rejected_but_new_process_is_allowed(self):
        with tempfile.TemporaryDirectory(prefix="vector-exec-guard-") as tmp:
            binary = Path(tmp) / "exec-guard-test"
            subprocess.run(
                shlex.split(os.environ.get("CXX", "c++"))
                + ["-std=c++17", "-Wall", "-Wextra", "-Werror",
                   "-I" + str(ROOT / "dex2oat/src/main/cpp/include"),
                   str(ROOT / "tests/dex2oat/exec_guard_test.cpp"), "-o", str(binary)],
                check=True,
            )
            subprocess.run([str(binary)], check=True, timeout=5)


class NamespaceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="vector mount test ")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.target = self.base / "apex/bin/dex2oat64"
        self.wrapper = self.base / "module/bin/dex2oat64"
        self.wrapper32 = self.base / "module/bin/dex2oat32"
        for path in (self.target, self.wrapper, self.wrapper32):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        self.counter = self.base / "layers"
        self.events = self.base / "events"

    def run_shell(self, script, layers=0, mode="ok"):
        self.counter.write_text(str(layers))
        self.events.write_text("")
        env = {**os.environ, "TARGET": str(self.target), "WRAPPER": str(self.wrapper),
               "WRAPPER32": str(self.wrapper32), "COUNTER": str(self.counter),
               "EVENTS": str(self.events), "MODE": mode}
        setup = r'''
            . "$1"
            stat() {
                [ "$MODE" != stat-fail ] || return 1
                case "$3" in
                    "$WRAPPER") echo 9:201 ;;
                    "$WRAPPER32") echo 9:202 ;;
                    "${TARGET%/*}") echo 7 ;;
                    "$TARGET")
                        if [ "$MODE" = unknown ]; then echo 9:999
                        elif [ "$MODE" = unknown-below ] && [ "$(cat "$COUNTER")" -eq 0 ]; then echo 9:999
                        elif [ "$MODE" = empty-stat ]; then return 0
                        elif [ "$(cat "$COUNTER")" -gt 0 ]; then
                            if [ "$MODE" = wrapper32 ]; then echo 9:202; else echo 9:201; fi
                        else echo 7:101; fi ;;
                    *) return 1 ;;
                esac
            }
            umount() {
                printf 'umount:%s\n' "$*" >> "$EVENTS"
                [ "$#" = 1 ] && [ "$1" = "$TARGET" ] || return 1
                [ "$MODE" != unmount-fail ] || return 1
                [ "$MODE" != no-progress ] || return 0
                if [ "$MODE" = disappears ]; then rm "$TARGET"; fi
                remaining=$(cat "$COUNTER")
                printf '%s\n' "$((remaining - 1))" > "$COUNTER"
            }
            readlink() {
                [ "$MODE" != ns-fail ] || return 1
                [ "$MODE" != empty-ns ] || return 0
                if [ "$MODE" = init ] || [ "$1" = /proc/1/ns/mnt ]; then
                    echo 'mnt:[1]'
                else echo 'mnt:[2]'; fi
            }
            mount() {
                printf 'mount:%s\n' "$*" >> "$EVENTS"
                [ "$*" = '-o rprivate none /' ] || return 1
                [ "$MODE" != private-fail ]
            }
        '''
        result = subprocess.run(["sh", "-c", setup + script, "test", str(HELPER)],
                                env=env, capture_output=True, text=True, timeout=5)
        return result, self.events.read_text().splitlines()

    def restore(self, **kwargs):
        return self.run_shell('vector_restore_compiler_view "$TARGET" "$WRAPPER32" "$WRAPPER"', **kwargs)

    def test_wrapper_abi_is_identified_by_inode_not_filename(self):
        result, events = self.restore(layers=1, mode="wrapper32")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, [f"umount:{self.target}"])

    def test_clean_stock_is_left_alone(self):
        result, events = self.restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, [])

    def test_known_wrapper_layers_are_peeled(self):
        for layers in (1, 2, 8):
            with self.subTest(layers=layers):
                result, events = self.restore(layers=layers)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(events, [f"umount:{self.target}"] * layers)

    def test_too_many_layers_stop(self):
        result, events = self.restore(layers=9)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(events), 8)

    def test_successful_unmount_without_progress_is_bounded(self):
        result, events = self.restore(layers=1, mode="no-progress")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(events), 8)

    def test_unknown_or_replaced_wrapper_is_not_unmounted(self):
        result, events = self.restore(mode="unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unrecognized compiler mount", result.stderr)
        self.assertEqual(events, [])

    def test_unknown_mount_below_known_wrapper_stops(self):
        result, events = self.restore(layers=1, mode="unknown-below")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, [f"umount:{self.target}"])

    def test_compiler_missing_after_unmount_stops(self):
        result, events = self.restore(layers=1, mode="disappears")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, [f"umount:{self.target}"])

    def test_unmount_failure_stops(self):
        result, events = self.restore(layers=1, mode="unmount-fail")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, [f"umount:{self.target}"])

    def test_failed_or_empty_stat_stops(self):
        for mode in ("stat-fail", "empty-stat"):
            with self.subTest(mode=mode):
                result, events = self.restore(mode=mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(events, [])

    def test_absent_optional_compiler_is_ok(self):
        self.target.unlink()
        result, events = self.restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, [])

    def test_dangling_compiler_symlink_stops(self):
        self.target.unlink()
        self.target.symlink_to(self.base / "absent")
        result, events = self.restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, [])

    def test_non_regular_compiler_stops(self):
        self.target.unlink()
        self.target.mkdir()
        result, events = self.restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, [])

    def test_absent_wrapper_does_not_hide_unknown_mount(self):
        self.wrapper.unlink()
        result, events = self.restore(mode="unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, [])

    def test_absent_secondary_wrapper_allows_stock(self):
        self.wrapper.unlink()
        result, events = self.restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, [])

    def prepare(self, mode="ok", restore_result=0):
        return self.run_shell('''
            vector_restore_compiler_view() {
                printf 'restore:%s:%s:%s\\n' "$1" "$2" "$3" >> "$EVENTS"
                return ''' + str(restore_result) + '''
            }
            vector_prepare_dex2oat_namespace /module
        ''', mode=mode)

    def test_init_or_unreadable_namespace_never_changes_mounts(self):
        for mode in ("init", "ns-fail", "empty-ns"):
            with self.subTest(mode=mode):
                result, events = self.prepare(mode=mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(events, [])

    def test_propagation_failure_never_unmounts(self):
        result, events = self.prepare(mode="private-fail")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, ["mount:-o rprivate none /"])

    def test_all_compilers_checked_only_after_isolation(self):
        result, events = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = ["mount:-o rprivate none /"]
        for apex in ("runtime", "art"):
            for name in ("dex2oat", "dex2oatd", "dex2oat32", "dex2oatd32", "dex2oat64", "dex2oatd64"):
                expected.append(f"restore:/apex/com.android.{apex}/bin/{name}:"
                                "/module/bin/dex2oat32:/module/bin/dex2oat64")
        self.assertEqual(events, expected)

    def test_compiler_recovery_error_stops_startup(self):
        result, events = self.prepare(restore_result=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(events), 2)


if __name__ == "__main__":
    unittest.main()
