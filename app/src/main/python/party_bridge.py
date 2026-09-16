# -*- coding: utf-8 -*-
"""Bridge: start/stop real copyparty inside Chaquopy."""
from __future__ import print_function

import collections
import io
import os
import socket
import sys
import threading
import time
import traceback

_op_lock = threading.RLock()
_thread = None
_hub = None
_running = False
_error = None
_started_event = threading.Event()
_svchub_patched = False
_tracked_socks = []
_stopping = False
_LOG_MAX = 200
_log_ring = collections.deque(maxlen=_LOG_MAX)
_log_lock = threading.Lock()
_io_patched = False
_orig_stdout = None
_orig_stderr = None


def is_running():
    return bool(_running)


def last_error():
    return _error


def last_log(n=40):
    """Return the most recent captured bridge / copyparty log lines."""
    with _log_lock:
        lines = list(_log_ring)
    if n is not None and n > 0:
        lines = lines[-int(n):]
    return "\n".join(lines)


def _ring_write(text):
    if text is None:
        return
    s = text if isinstance(text, str) else str(text)
    if not s:
        return
    with _log_lock:
        # Split on newlines so the ring stays line-oriented.
        parts = s.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        for i, part in enumerate(parts):
            if i < len(parts) - 1:
                _log_ring.append(part)
            elif part:
                # trailing fragment without newline — keep as its own line
                _log_ring.append(part)


def _bridge_print(*args, **kwargs):
    """Print and always capture into the ring buffer."""
    try:
        buf = io.StringIO()
        kwargs = dict(kwargs)
        kwargs["file"] = buf
        print(*args, **kwargs)
        msg = buf.getvalue()
    except Exception:
        msg = " ".join(str(a) for a in args) + "\n"
    _ring_write(msg.rstrip("\n") if msg.endswith("\n") else msg)
    try:
        out = _orig_stdout if _orig_stdout is not None else sys.__stdout__
        if out is not None:
            out.write(msg)
            if hasattr(out, "flush"):
                out.flush()
    except Exception:
        pass


class _TeeStream(object):
    """Tee writes into the ring buffer while forwarding to the real stream."""

    def __init__(self, real):
        self._real = real

    def write(self, data):
        try:
            _ring_write(data)
        except Exception:
            pass
        try:
            if self._real is not None:
                return self._real.write(data)
        except Exception:
            pass
        return len(data) if data is not None else 0

    def flush(self):
        try:
            if self._real is not None and hasattr(self._real, "flush"):
                self._real.flush()
        except Exception:
            pass

    def fileno(self):
        if self._real is not None and hasattr(self._real, "fileno"):
            return self._real.fileno()
        raise io.UnsupportedOperation("fileno")

    def isatty(self):
        try:
            return bool(self._real is not None and self._real.isatty())
        except Exception:
            return False

    @property
    def encoding(self):
        return getattr(self._real, "encoding", "utf-8")

    def __getattr__(self, name):
        return getattr(self._real, name)


def _install_io_tee():
    global _io_patched, _orig_stdout, _orig_stderr
    if _io_patched:
        return
    _orig_stdout = sys.stdout
    _orig_stderr = sys.stderr
    sys.stdout = _TeeStream(_orig_stdout)
    sys.stderr = _TeeStream(_orig_stderr)
    _io_patched = True


def _redact_argv(argv):
    out = []
    skip_next = False
    for i, tok in enumerate(list(argv or [])):
        if skip_next:
            out.append("<redacted>")
            skip_next = False
            continue
        if tok in ("-a", "--a"):
            out.append(tok)
            skip_next = True
            continue
        # -a user:pass form already handled via next-token; also scrub inline
        if isinstance(tok, str) and tok.startswith("share:") and ":" in tok[6:]:
            out.append("share:<redacted>")
            continue
        out.append(tok)
    return out


def _hub_state():
    hub = _hub
    if hub is None:
        return "hub=None"
    tcpsrv = getattr(hub, "tcpsrv", None)
    nsrv = 0
    if tcpsrv is not None:
        try:
            nsrv = len(getattr(tcpsrv, "srv", None) or [])
        except Exception:
            nsrv = -1
    return "hub=%s stopping=%s tcpsrv_socks=%s" % (
        type(hub).__name__,
        getattr(hub, "stopping", "?"),
        nsrv,
    )


def _diagnostic(prefix, argv=None, extra=""):
    t = _thread
    alive = bool(t is not None and t.is_alive())
    parts = [
        prefix,
        "running=%s thread_alive=%s %s" % (_running, alive, _hub_state()),
    ]
    if argv is not None:
        parts.append("argv=%s" % (" ".join(_redact_argv(argv)),))
    tail = last_log(40)
    if tail:
        parts.append("--- log tail ---\n" + tail)
    if extra:
        parts.append(extra)
    return "\n".join(parts)


def _close_one_sock(srv):
    if srv is None:
        return
    try:
        srv.shutdown(socket.SHUT_RDWR)
    except Exception:
        pass
    try:
        srv.close()
    except Exception:
        pass


def _close_sock_list(socks):
    for srv in list(socks or []):
        _close_one_sock(srv)


def _close_listeners(hub):
    """Close leftover listen sockets so the next start can bind the port."""
    global _tracked_socks
    _close_sock_list(_tracked_socks)
    _tracked_socks = []
    if hub is None:
        return
    tcpsrv = getattr(hub, "tcpsrv", None)
    if tcpsrv is None:
        return
    try:
        setattr(tcpsrv, "stopping", True)
    except Exception:
        pass
    _close_sock_list(getattr(tcpsrv, "srv", None))
    try:
        tcpsrv.srv = []
    except Exception:
        pass


def _track_hub_sockets(hub):
    tcpsrv = getattr(hub, "tcpsrv", None)
    if tcpsrv is None:
        return
    for srv in list(getattr(tcpsrv, "srv", None) or []):
        if srv not in _tracked_socks:
            _tracked_socks.append(srv)


def _is_addr_in_use_text(text):
    if not text:
        return False
    t = text.lower()
    return (
        "address already in use" in t
        or "errno 98" in t
        or "[errno 98]" in t
        or "port" in t and "is busy" in t
    )


def _can_bind(host, port):
    """Return True if host:port can be bound (listen socket is gone)."""
    ip = (host or "0.0.0.0").split(",")[0].strip() or "0.0.0.0"
    if ip in ("", "*", "all"):
        ip = "0.0.0.0"
    family = socket.AF_INET6 if ":" in ip else socket.AF_INET
    srv = socket.socket(family, socket.SOCK_STREAM)
    try:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if family == socket.AF_INET6:
            try:
                srv.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, False)
            except Exception:
                pass
        srv.bind((ip, int(port)))
        return True
    except OSError:
        return False
    finally:
        _close_one_sock(srv)


def _wait_port_released(bind_host, port, timeout=2.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _can_bind(bind_host, port):
            return True
        time.sleep(0.1)
    return _can_bind(bind_host, port)


def _unwrap_svchub(cls):
    """Walk subclass chain and return the first non-bridge SvcHub."""
    seen = set()
    cur = cls
    while cur is not None and id(cur) not in seen:
        seen.add(id(cur))
        if not getattr(cur, "_party_bridge_capture", False):
            return cur
        bases = getattr(cur, "__bases__", ())
        cur = bases[0] if bases else None
    return cls


def _capture_hub():
    """Patch SvcHub once so we can shut it down from Kotlin without nesting wrappers."""
    global _svchub_patched
    import copyparty.__main__ as cpp_main
    import copyparty.svchub as svchub

    current = svchub.SvcHub
    if getattr(current, "_party_bridge_capture", False) and _svchub_patched:
        return

    Orig = _unwrap_svchub(current)

    class CapturingSvcHub(Orig):
        _party_bridge_capture = True

        def __init__(self, *a, **kw):
            global _hub
            Orig.__init__(self, *a, **kw)
            _hub = self
            _track_hub_sockets(self)
            _started_event.set()

        def cb_httpsrv_up(self):
            try:
                Orig.cb_httpsrv_up(self)
            finally:
                _track_hub_sockets(self)
                _started_event.set()

        def shutdown(self):
            # Close listen sockets first so restart can bind; isolate SystemExit
            # so Chaquopy / Android caller threads are not killed.
            try:
                _close_listeners(self)
            except Exception:
                pass
            try:
                Orig.shutdown(self)
            except SystemExit:
                return

    svchub.SvcHub = CapturingSvcHub
    cpp_main.SvcHub = CapturingSvcHub
    _svchub_patched = True


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
            argv.extend(["-v", "%s::rwdm,share" % vol_src])
    else:
        if read_only:
            argv.extend(["-v", "%s::r" % vol_src])
        else:
            argv.extend(["-v", "%s::rwdm" % vol_src])

    return argv


def _stop_unlocked():
    """Request shutdown, always close listen sockets, never raise SystemExit."""
    global _hub, _running, _stopping
    _stopping = True
    hub = _hub
    t = _thread
    _close_listeners(hub)

    if hub is not None:
        done = threading.Event()

        def _graceful():
            try:
                hub.shutdown()
            except SystemExit:
                pass
            except Exception:
                _bridge_print("[party_bridge] shutdown error:\n", traceback.format_exc())
                try:
                    hub.sigterm()
                except Exception:
                    pass
            finally:
                done.set()

        threading.Thread(
            target=_graceful, name="copyparty-shutdown", daemon=True
        ).start()
        done.wait(timeout=3.0)

    if t is not None and t.is_alive():
        t.join(timeout=5.0)
    _running = False
    _hub = None
    return True


def _start_unlocked(port, share_path, read_only, password, hist_dir, bind_host):
    """Start copyparty in a background thread. Returns True if kickoff OK."""
    global _thread, _running, _error, _hub, _stopping
    if _running and _thread is not None and _thread.is_alive() and _hub is not None:
        return True

    prev = _thread
    leftover_hub = _hub
    if (prev is not None and prev.is_alive()) or leftover_hub is not None:
        _bridge_print("[party_bridge] leftover instance; stopping before restart")
        _stop_unlocked()
        _wait_port_released(bind_host, port, timeout=2.0)

    _stopping = False
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
            _install_io_tee()
            _capture_hub()
            from copyparty.__main__ import main
            _bridge_print(
                "[party_bridge] starting:", " ".join(_redact_argv(argv))
            )
            main(argv)
        except SystemExit as e:
            code = getattr(e, "code", e)
            msg = "SystemExit(%r)" % (code,)
            _bridge_print("[party_bridge]", msg)
            if not _stopping:
                _error = _diagnostic(msg, argv=argv)
        except Exception:
            if _stopping:
                _bridge_print(
                    "[party_bridge] ignored error during stop:\n",
                    traceback.format_exc(),
                )
            else:
                _error = traceback.format_exc()
                _bridge_print("[party_bridge] crash:\n", _error)
        finally:
            _running = False
            try:
                _close_listeners(_hub)
            except Exception:
                pass
            _hub = None
            _started_event.set()
            _bridge_print("[party_bridge] stopped")

    _thread = threading.Thread(target=runner, name="copyparty-main", daemon=True)
    _thread.start()

    _started_event.wait(timeout=8.0)
    if _error:
        return False
    if _running or _hub is not None:
        _track_hub_sockets(_hub)
        return True
    # False start with no exception path — still give Kotlin a copyable diagnostic.
    _error = _diagnostic(
        "copyparty failed to become ready (no hub / not running after start wait)",
        argv=argv,
    )
    return False


def start(port, share_path, read_only, password, hist_dir, bind_host="0.0.0.0"):
    """Start copyparty. Retries once if the previous listen socket is still busy."""
    global _error
    with _op_lock:
        ok = _start_unlocked(
            port, share_path, read_only, password, hist_dir, bind_host
        )
        if ok:
            return True
        if _is_addr_in_use_text(_error):
            _bridge_print("[party_bridge] EADDRINUSE; forcing release and retry")
            _stop_unlocked()
            _wait_port_released(bind_host, port, timeout=2.5)
            _error = None
            ok = _start_unlocked(
                port, share_path, read_only, password, hist_dir, bind_host
            )
            if ok:
                return True
            if _is_addr_in_use_text(_error):
                _error = (
                    "端口 %s 仍被占用（上次停止后监听套接字未释放）。"
                    "请再点一次启动，或更换端口。\n\n%s"
                    % (port, _error or "")
                )
        if not ok and not _error:
            _error = _diagnostic(
                "copyparty start returned false without an error detail",
                argv=_build_argv(
                    port, share_path, read_only, password or "", hist_dir, bind_host
                ),
            )
        return False


def stop():
    """Request graceful shutdown of the running SvcHub; always free the port."""
    with _op_lock:
        return _stop_unlocked()


def status():
    return {
        "running": is_running(),
        "error": _error or "",
        "has_hub": _hub is not None,
        "log_tail": last_log(20),
    }
