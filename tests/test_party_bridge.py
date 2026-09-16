# -*- coding: utf-8 -*-
"""Unit tests for party_bridge restart / SvcHub patch / SystemExit isolation.

Run from repo root:

    PYTHONPATH=app/src/main/python python3 tests/test_party_bridge.py
"""
from __future__ import print_function

import os
import socket
import sys
import tempfile
import threading
import time
import types
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PYSRC = os.path.join(ROOT, "app", "src", "main", "python")
if PYSRC not in sys.path:
    sys.path.insert(0, PYSRC)

import party_bridge as pb  # noqa: E402


def _listen(port=0):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    return srv, srv.getsockname()[1]


class FakeTcpSrv(object):
    def __init__(self, socks):
        self.srv = list(socks)
        self.stopping = False

    def shutdown(self):
        self.stopping = True
        for s in list(self.srv):
            try:
                s.close()
            except Exception:
                pass
        self.srv = []


class FakeSvcHub(object):
    def __init__(self, socks=None):
        import queue as _queue
        self.tcpsrv = FakeTcpSrv(socks or [])
        self.stopping = False
        self.sigterm_called = False
        self.sig = _queue.Queue()

    def shutdown(self):
        self.stopping = True
        self.tcpsrv.shutdown()
        sys.exit(0)

    def sigterm(self):
        self.sigterm_called = True

    def cb_httpsrv_up(self):
        pass


class PartyBridgeTests(unittest.TestCase):
    def tearDown(self):
        pb._tracked_socks = []
        pb._hub = None
        pb._running = False
        pb._thread = None
        pb._error = None
        pb._svchub_patched = False
        pb._signal_patched = False
        pb._stopping = False
        with pb._log_lock:
            pb._log_ring.clear()

    def test_close_listeners_frees_port(self):
        srv, port = _listen()
        hub = FakeSvcHub([srv])
        pb._track_hub_sockets(hub)
        self.assertFalse(pb._can_bind("127.0.0.1", port))
        pb._close_listeners(hub)
        self.assertTrue(pb._can_bind("127.0.0.1", port))

    def test_stop_swallows_sys_exit_and_frees_port(self):
        srv, port = _listen()
        hub = FakeSvcHub([srv])
        pb._hub = hub
        pb._running = True
        # leftover "server" thread that just waits until the socket dies
        def waiter():
            try:
                srv.accept()
            except Exception:
                pass

        t = threading.Thread(target=waiter, name="fake-copyparty", daemon=True)
        t.start()
        pb._thread = t
        pb._track_hub_sockets(hub)

        pb.stop()
        self.assertFalse(pb.is_running())
        self.assertIsNone(pb._hub)
        self.assertTrue(pb._can_bind("127.0.0.1", port))

    def test_addr_in_use_detector(self):
        self.assertTrue(pb._is_addr_in_use_text("OSError: [Errno 98] Address already in use"))
        self.assertTrue(pb._is_addr_in_use_text("port 3923 is busy on interface 0.0.0.0"))
        self.assertFalse(pb._is_addr_in_use_text("share path missing"))

    def test_capture_hub_is_idempotent(self):
        copyparty = types.ModuleType("copyparty")
        main_mod = types.ModuleType("copyparty.__main__")
        svchub = types.ModuleType("copyparty.svchub")
        svchub.SvcHub = FakeSvcHub
        main_mod.SvcHub = FakeSvcHub
        sys.modules["copyparty"] = copyparty
        sys.modules["copyparty.__main__"] = main_mod
        sys.modules["copyparty.svchub"] = svchub

        pb._svchub_patched = False
        pb._capture_hub()
        first = svchub.SvcHub
        self.assertTrue(getattr(first, "_party_bridge_capture", False))
        pb._capture_hub()
        pb._capture_hub()
        self.assertIs(svchub.SvcHub, first)
        # MRO is Capturing -> FakeSvcHub, not nested Capturing wrappers
        capture_layers = [
            c for c in svchub.SvcHub.__mro__
            if getattr(c, "_party_bridge_capture", False)
        ]
        self.assertEqual(len(capture_layers), 1)

        inst = svchub.SvcHub()
        # shutdown must not raise SystemExit to the caller
        inst.shutdown()
        self.assertIs(pb._hub, inst)

    def test_unwrap_nested_wrappers(self):
        class Orig(object):
            pass

        class Wrap1(Orig):
            _party_bridge_capture = True

        class Wrap2(Wrap1):
            _party_bridge_capture = True

        self.assertIs(pb._unwrap_svchub(Wrap2), Orig)
        self.assertIs(pb._unwrap_svchub(Orig), Orig)

    def test_systemexit_sets_nonempty_error(self):
        """Runner SystemExit must populate _error (unless stopping)."""
        share = tempfile.mkdtemp(prefix="cpp-share-")
        hist = tempfile.mkdtemp(prefix="cpp-hist-")

        copyparty = types.ModuleType("copyparty")
        main_mod = types.ModuleType("copyparty.__main__")
        svchub = types.ModuleType("copyparty.svchub")
        svchub.SvcHub = FakeSvcHub
        main_mod.SvcHub = FakeSvcHub

        def boom_main(argv):
            print("argparse: bad config for test", file=sys.stderr)
            raise SystemExit(2)

        main_mod.main = boom_main
        sys.modules["copyparty"] = copyparty
        sys.modules["copyparty.__main__"] = main_mod
        sys.modules["copyparty.svchub"] = svchub

        ok = pb.start(39231, share, True, "secret-pw", hist, "127.0.0.1")
        self.assertFalse(ok)
        err = pb.last_error()
        self.assertIsNotNone(err)
        self.assertTrue(err.strip(), "expected nonempty diagnostic")
        self.assertIn("SystemExit", err)
        self.assertIn("39231", err)
        self.assertNotIn("secret-pw", err)
        self.assertIn("<redacted>", err)
        # log ring should have captured stderr/print
        self.assertTrue(pb.last_log())

    def test_systemexit_ignored_while_stopping(self):
        share = tempfile.mkdtemp(prefix="cpp-share-")
        hist = tempfile.mkdtemp(prefix="cpp-hist-")

        copyparty = types.ModuleType("copyparty")
        main_mod = types.ModuleType("copyparty.__main__")
        svchub = types.ModuleType("copyparty.svchub")
        svchub.SvcHub = FakeSvcHub
        main_mod.SvcHub = FakeSvcHub

        started = threading.Event()

        def slow_exit_main(argv):
            started.set()
            time.sleep(0.3)
            raise SystemExit(0)

        main_mod.main = slow_exit_main
        sys.modules["copyparty"] = copyparty
        sys.modules["copyparty.__main__"] = main_mod
        sys.modules["copyparty.svchub"] = svchub

        # Kick runner without waiting for readiness via internals
        pb._stopping = False
        pb._error = None
        pb._hub = None
        pb._started_event.clear()

        def runner():
            global_ns = pb
            global_ns._running = True
            try:
                pb._install_io_tee()
                pb._capture_hub()
                from copyparty.__main__ import main
                main(["copyparty"])
            except SystemExit as e:
                if not pb._stopping:
                    pb._error = pb._diagnostic("SystemExit(%r)" % (e.code,), argv=["copyparty"])
            finally:
                pb._running = False
                pb._hub = None
                pb._started_event.set()

        # Prefer exercising start() with stop overlap: mark stopping before exit
        def main_stop_race(argv):
            started.set()
            # Simulate stop requested before SystemExit from shutdown
            pb._stopping = True
            raise SystemExit(0)

        main_mod.main = main_stop_race
        ok = pb.start(39232, share, True, "", hist, "127.0.0.1")
        self.assertFalse(ok)
        # While stopping, SystemExit must not invent a scary startup error
        self.assertTrue(
            pb.last_error() is None
            or "failed to become ready" in (pb.last_error() or "")
            or "SystemExit" not in (pb.last_error() or "")
        )

    def test_first_start_readiness_success(self):
        """First start succeeds when hub is created and thread stays alive."""
        share = tempfile.mkdtemp(prefix="cpp-share-")
        hist = tempfile.mkdtemp(prefix="cpp-hist-")

        copyparty = types.ModuleType("copyparty")
        main_mod = types.ModuleType("copyparty.__main__")
        svchub = types.ModuleType("copyparty.svchub")

        class ReadyHub(FakeSvcHub):
            def __init__(self, *a, **kw):
                FakeSvcHub.__init__(self, [])
                # CapturingSvcHub sets _hub after Orig.__init__

        svchub.SvcHub = ReadyHub
        main_mod.SvcHub = ReadyHub

        hold = threading.Event()

        def ready_main(argv):
            # Construct hub the same way copyparty does
            hub = svchub.SvcHub()
            # Block like SvcHub.run() until stop
            hold.wait(timeout=5.0)

        main_mod.main = ready_main
        sys.modules["copyparty"] = copyparty
        sys.modules["copyparty.__main__"] = main_mod
        sys.modules["copyparty.svchub"] = svchub

        try:
            ok = pb.start(39233, share, True, "", hist, "127.0.0.1")
            self.assertTrue(ok, pb.last_error())
            self.assertTrue(pb.is_running())
            self.assertIsNotNone(pb._hub)
            self.assertFalse(pb.last_error())
        finally:
            hold.set()
            pb.stop()

    def test_false_start_without_hub_sets_diagnostic(self):
        """If main returns without hub/SystemExit, start still sets a diagnostic."""
        share = tempfile.mkdtemp(prefix="cpp-share-")
        hist = tempfile.mkdtemp(prefix="cpp-hist-")

        copyparty = types.ModuleType("copyparty")
        main_mod = types.ModuleType("copyparty.__main__")
        svchub = types.ModuleType("copyparty.svchub")
        svchub.SvcHub = FakeSvcHub
        main_mod.SvcHub = FakeSvcHub

        def quiet_main(argv):
            return None  # exits without creating a hub

        main_mod.main = quiet_main
        sys.modules["copyparty"] = copyparty
        sys.modules["copyparty.__main__"] = main_mod
        sys.modules["copyparty.svchub"] = svchub

        ok = pb.start(39234, share, False, "pw-should-hide", hist, "127.0.0.1")
        self.assertFalse(ok)
        err = pb.last_error()
        self.assertTrue(err and err.strip())
        self.assertIn("failed to become ready", err)
        self.assertIn("thread_alive=", err)
        self.assertNotIn("pw-should-hide", err)

    def test_redact_argv(self):
        argv = ["copyparty", "-a", "share:s3cret", "-v", "/tmp::r,share"]
        red = pb._redact_argv(argv)
        self.assertEqual(red[red.index("-a") + 1], "<redacted>")
        self.assertNotIn("s3cret", " ".join(red))


    def test_patch_signal_ignores_non_main_thread(self):
        """signal.signal on a dummy thread must not kill the wrapper."""
        import signal

        # Force re-patch
        pb._signal_patched = False
        pb._patch_signal()
        self.assertTrue(pb._signal_patched)

        errors = []
        result = []

        def worker():
            try:
                # Under CPython this raises ValueError on non-main threads.
                # Our wrapper must swallow ValueError/OSError/RuntimeError.
                out = signal.signal(signal.SIGTERM, signal.SIG_DFL)
                result.append(out)
            except Exception as e:
                errors.append(e)

        t = threading.Thread(target=worker, name="dummy-signal-thr")
        t.start()
        t.join(timeout=2.0)
        self.assertFalse(t.is_alive())
        self.assertEqual(errors, [], "wrapper must not propagate signal errors: %r" % (errors,))
        # On non-main thread safe_signal returns None; on main it may return previous handler.
        # Either way the thread must survive.
        self.assertTrue(len(result) == 1)

    def test_patch_signal_idempotent(self):
        pb._signal_patched = False
        pb._patch_signal()
        import signal
        first = signal.signal
        pb._patch_signal()
        self.assertIs(signal.signal, first)

    def test_wake_runner_posts_sigterm(self):
        import signal
        hub = FakeSvcHub()
        pb._wake_runner(hub)
        got = hub.sig.get(timeout=1.0)
        self.assertEqual(got, signal.SIGTERM)

    def test_hub_captured_on_half_init_failure(self):
        """_hub must be set in finally even if Orig.__init__ raises."""
        copyparty = types.ModuleType("copyparty")
        main_mod = types.ModuleType("copyparty.__main__")
        svchub = types.ModuleType("copyparty.svchub")

        class BoomHub(object):
            def __init__(self, *a, **kw):
                self.tcpsrv = FakeTcpSrv([])
                raise RuntimeError("half-init boom")

            def shutdown(self):
                raise SystemExit(0)

            def cb_httpsrv_up(self):
                pass

        svchub.SvcHub = BoomHub
        main_mod.SvcHub = BoomHub
        sys.modules["copyparty"] = copyparty
        sys.modules["copyparty.__main__"] = main_mod
        sys.modules["copyparty.svchub"] = svchub

        pb._svchub_patched = False
        pb._signal_patched = False
        pb._capture_hub()
        with self.assertRaises(RuntimeError):
            svchub.SvcHub()
        self.assertIsNotNone(pb._hub)
        self.assertTrue(getattr(type(pb._hub), "_party_bridge_capture", False))

    def test_build_argv_omits_reuseaddr(self):
        argv = pb._build_argv(3923, "/tmp", True, "", "/tmp/hist", "0.0.0.0")
        self.assertNotIn("--reuseaddr", argv)

    def test_capture_hub_calls_patch_signal(self):
        copyparty = types.ModuleType("copyparty")
        main_mod = types.ModuleType("copyparty.__main__")
        svchub = types.ModuleType("copyparty.svchub")
        svchub.SvcHub = FakeSvcHub
        main_mod.SvcHub = FakeSvcHub
        sys.modules["copyparty"] = copyparty
        sys.modules["copyparty.__main__"] = main_mod
        sys.modules["copyparty.svchub"] = svchub

        pb._svchub_patched = False
        pb._signal_patched = False
        pb._capture_hub()
        self.assertTrue(pb._signal_patched)




if __name__ == "__main__":
    unittest.main()
