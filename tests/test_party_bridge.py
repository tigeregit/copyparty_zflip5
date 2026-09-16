# -*- coding: utf-8 -*-
"""Unit tests for party_bridge restart / SvcHub patch / SystemExit isolation.

Run from repo root:

    PYTHONPATH=app/src/main/python python3 tests/test_party_bridge.py
"""
from __future__ import print_function

import os
import socket
import sys
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
        self.tcpsrv = FakeTcpSrv(socks or [])
        self.stopping = False
        self.sigterm_called = False

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


if __name__ == "__main__":
    unittest.main()
