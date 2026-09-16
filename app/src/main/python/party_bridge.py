# -*- coding: utf-8 -*-
"""Bridge: start/stop real copyparty inside Chaquopy."""
from __future__ import print_function

import os
import sys
import threading
import traceback

_lock = threading.Lock()
_thread = None
_hub = None
_running = False
_error = None
_started_event = threading.Event()


def is_running():
    return bool(_running)


def last_error():
    return _error


def _capture_hub():
    """Patch SvcHub so we can call shutdown() from Kotlin."""
    import copyparty.__main__ as cpp_main
    import copyparty.svchub as svchub

    Orig = svchub.SvcHub

    class CapturingSvcHub(Orig):
        def __init__(self, *a, **kw):
            global _hub
            Orig.__init__(self, *a, **kw)
            _hub = self
            _started_event.set()

        def cb_httpsrv_up(self):
            try:
                Orig.cb_httpsrv_up(self)
            finally:
                _started_event.set()

    svchub.SvcHub = CapturingSvcHub
    cpp_main.SvcHub = CapturingSvcHub


def _build_argv(port, share_path, read_only, password, hist_dir, bind_host):
    # -j 1: thread broker only (Android has no working multiprocessing IPC)
    host = (bind_host or "0.0.0.0").strip() or "0.0.0.0"
    argv = [
        "copyparty",
        "-j", "1",
        "-p", str(int(port)),
        "-i", host,
        "--http-only",
        "--no-thumb",
        "--hist", hist_dir,
        "--dbpath", hist_dir,
        "--no-robots",
    ]

    vol_src = share_path
    if password:
        argv.extend(["-a", "share:%s" % password])
        if read_only:
            argv.extend(["-v", "%s::r,share" % vol_src])
        else:
            argv.extend(["-v", "%s::rw,share" % vol_src])
    else:
        if read_only:
            argv.extend(["-v", "%s::r" % vol_src])
        else:
            argv.extend(["-v", "%s::rw" % vol_src])

    return argv


def start(port, share_path, read_only, password, hist_dir, bind_host="0.0.0.0"):
    """Start copyparty in a background thread. Returns True if kickoff OK."""
    global _thread, _running, _error, _hub
    with _lock:
        if _running:
            return True
        _error = None
        _hub = None
        _started_event.clear()

        if not share_path or not os.path.isdir(share_path):
            _error = "share path missing or not a directory: %r" % (share_path,)
            return False

        os.makedirs(hist_dir, exist_ok=True)

        home = os.environ.get("HOME") or hist_dir
        os.environ["HOME"] = home
        os.environ["PRTY_NO_TUI"] = "1"

        argv = _build_argv(
            port, share_path, read_only, password or "", hist_dir, bind_host
        )

        def runner():
            global _running, _error, _hub
            _running = True
            try:
                _capture_hub()
                from copyparty.__main__ import main
                print("[party_bridge] starting:", " ".join(argv))
                main(argv)
            except SystemExit as e:
                print("[party_bridge] SystemExit", e)
            except Exception:
                _error = traceback.format_exc()
                print("[party_bridge] crash:\n", _error)
            finally:
                _running = False
                _hub = None
                _started_event.set()
                print("[party_bridge] stopped")

        _thread = threading.Thread(target=runner, name="copyparty-main", daemon=True)
        _thread.start()

    _started_event.wait(timeout=8.0)
    if _error:
        return False
    return _running or _hub is not None


def stop():
    """Request graceful shutdown of the running SvcHub."""
    global _hub, _running
    with _lock:
        hub = _hub
    if hub is not None:
        try:
            print("[party_bridge] calling hub.shutdown()")
            hub.shutdown()
        except Exception:
            print("[party_bridge] shutdown error:\n", traceback.format_exc())
            try:
                hub.sigterm()
            except Exception:
                pass
    t = _thread
    if t is not None and t.is_alive():
        t.join(timeout=5.0)
    _running = False
    return True


def status():
    return {
        "running": is_running(),
        "error": _error or "",
        "has_hub": _hub is not None,
    }
