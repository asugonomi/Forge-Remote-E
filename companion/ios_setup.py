"""Run a private Tailscale Serve route while the iOS companion window is open."""
import argparse
from contextlib import ExitStack, contextmanager
import ctypes
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit
import webbrowser

from forge_remote_gateway import local_companion

HERE = Path(__file__).resolve().parent
FUNNEL_PORTS = {443, 8443, 10000}
PRIVATE_PORTS = range(18443, 18459)
_HOST = re.compile(r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.){2,}ts\.net")
_cleanup_job = None


def tailscale_cli():
    standard = Path(os.environ.get("ProgramFiles", "")) / "Tailscale/tailscale.exe"
    path = str(standard) if standard.is_file() else shutil.which("tailscale")
    if not path:
        raise ValueError("このPCにTailscaleをインストールし、iPhoneと同じTailscaleネットワークにサインインしてください。")
    return path


def json_command(exe, *args):
    try:
        result = subprocess.run([exe, *args], capture_output=True, timeout=15, check=True)
        return json.loads(result.stdout)
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        raise RuntimeError("Tailscaleに接続できません。PCのTailscaleを起動し、サインインしてから開き直してください。") from exc


def tail_host(status):
    if not isinstance(status, dict) or status.get("BackendState") != "Running":
        raise ValueError("PCのTailscaleでサインインして接続してください。")
    host = str((status.get("Self") or {}).get("DNSName", "")).rstrip(".").lower()
    if not _HOST.fullmatch(host) or len(host) > 253:
        raise ValueError("このPCのTailscale名が見つかりません。管理画面のDNS設定でMagicDNSを確認してください。")
    return host


def serve_config(exe):
    config = json_command(exe, "serve", "status", "--json")
    if config is None:
        return {}
    if not isinstance(config, dict):
        raise ValueError("Tailscale Serveの既存設定を確認できません。")
    return config


def config_sections(config):
    yield config
    for key in ("Foreground", "Services"):
        for child in (config.get(key) or {}).values():
            yield from config_sections(child)


def proxy_to(value, web_port):
    try:
        addr = urlsplit(value if "://" in value else "//" + value)
        return addr.hostname in {"127.0.0.1", "localhost", "::1"} and addr.port == web_port
    except (TypeError, ValueError):
        return False


def port_status(config, host, port, web_port):
    """Only an exclusive, root-mounted HTTPS route to our backend can be reused."""
    hp = f"{host}:{port}"
    target = f"http://127.0.0.1:{web_port}"
    matches = []
    sections = list(config_sections(config))
    public = {address for s in sections for address, allowed in (s.get("AllowFunnel") or {}).items() if allowed}
    if any(key.rpartition(":")[2] == str(port) for key in public):
        return "busy"
    for s in sections:
        tcp = s.get("TCP") or {}
        web = s.get("Web") or {}
        for public_addr in public:
            handlers = (web.get(public_addr) or {}).get("Handlers") or {}
            forward = (tcp.get(public_addr.rpartition(":")[2]) or {}).get("TCPForward", "")
            if proxy_to(forward, web_port) or any(proxy_to(h.get("Proxy", ""), web_port) for h in handlers.values()):
                raise ValueError("このローカルポートは別のTailscale Funnelでインターネットに公開されています。別のWebPortを選んでください。")
        web_keys = [k for k in web if k.rpartition(":")[2] == str(port)]
        if str(port) in tcp or web_keys:
            handlers = (web.get(hp) or {}).get("Handlers", {})
            root = handlers.get("/", {})
            matches.append((tcp.get(str(port)) == {"HTTPS": True} and web_keys == [hp] and
                            set(handlers) == {"/"} and set(root) == {"Proxy"} and
                            root["Proxy"].rstrip("/") == target))
    if not matches:
        return "free"
    return "ours" if matches == [True] else "busy"


def selected_port(config, host, web_port, requested=None, saved=None):
    selected = requested if requested is not None else saved
    ports = (selected,) if selected is not None else PRIVATE_PORTS
    for port in ports:
        if type(port) is not int or not 1024 <= port <= 65535 or port in FUNNEL_PORTS:
            raise ValueError("HttpsPortには1024～65535の番号を指定してください。8443と10000はFunnel用です。")
        status = port_status(config, host, port, web_port)
        if status == "free" or (status == "ours" and port == saved):
            return port, status
    raise ValueError("利用予定のHTTPSポートは他のサービスが使用中です。既存の設定を確認するか、別のHttpsPortを指定してください。")


def write_record(path, data):
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as f:
        tmp = Path(f.name)
        try:
            json.dump(data, f)
        except Exception:
            f.close()
            tmp.unlink(missing_ok=True)
            raise
    try:
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


def allow_certificate(host):
    message = (f"このPCのTailscale名\n{host}\nでiPhone用のHTTPS接続を開始します。\n\n"
               "初回の証明書発行時に、この名前が公開の証明書記録に載ります。画像・プロンプト・接続先のサービスは"
               "Tailscale内でのみ利用できます。\n\nこの名前で続けますか？")
    if os.name == "nt":
        return ctypes.windll.user32.MessageBoxW(None, message, "Forge Remote E · iPhone / iPad", 0x124) == 6
    return input(message + " [y/N] ").strip().lower() == "y"


def web_identity(origin, web_port, *, secure=False):
    endpoint = urlsplit(origin)
    conn = (http.client.HTTPSConnection(endpoint.hostname, endpoint.port, timeout=3) if secure
            else http.client.HTTPConnection("127.0.0.1", web_port, timeout=2))
    try:
        host = endpoint.netloc if secure else f"127.0.0.1:{web_port}"
        conn.request("GET", "/api/identity", headers={"Host": host})
        reply = conn.getresponse()
        data = reply.read(4097)
        if reply.status == 200 and len(data) <= 4096:
            result = json.loads(data)
            if isinstance(result, dict) and result.get("product") == "ForgeRemoteE-iOS" and result.get("version") == 1:
                return result
    except (OSError, ValueError, http.client.HTTPException):
        pass
    finally:
        conn.close()
    return {}


def state_key(directory):
    return hashlib.sha256(os.fsencode(os.path.normcase(str(directory.resolve())))).hexdigest()


def matches(identity, args, origin, *, secure=False):
    return (identity.get("origin") == origin and identity.get("forge_port") == args.forge_port
            and identity.get("companion_port") == args.companion_port and
            (secure or identity.get("state_key") == state_key(args.state_dir)))


def ios_args(args, origin):
    return [sys.executable, "-B", "-u", str(HERE / "ios_web.py"), "--origin", origin, "--port", str(args.web_port),
            "--forge-port", str(args.forge_port), "--companion-port", str(args.companion_port),
            "--state-dir", str(args.state_dir)]


def start_process(command, **kwargs):
    proc = subprocess.Popen(command, **kwargs)
    if _cleanup_job:
        handle, assign = _cleanup_job
        if not assign(handle, int(proc._handle)):
            proc.terminate()
            proc.wait()
            raise OSError("起動した補助プロセスの終了処理を準備できませんでした。")
    return proc


@contextmanager
def child(command, **kwargs):
    proc = start_process(command, **kwargs)
    try:
        yield proc
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        if proc.stdout:
            proc.stdout.close()


def official_consent(line):
    for token in line.split():
        if not token.startswith("https://"):
            continue
        url = urlsplit(token)
        if (url.netloc == "login.tailscale.com" and (url.path == "/f/serve" or url.path.startswith("/admin/")) and
                all(32 < ord(c) < 127 for c in token)):
            return token


def show_serve_messages(pipe, open_browser):
    opened = False
    for line in pipe:
        # The CLI may ask the administrator to enable HTTPS. Only open the official link it supplies.
        url = official_consent(line)
        if url and not opened and open_browser:
            opened = True
            print("Tailscaleの管理画面でこのPCのHTTPS接続を許可してください。", flush=True)
            if webbrowser.open(url):
                continue
        print(line.rstrip(), flush=True)


def enable_child_cleanup():
    """Windows closes this private job when its owner console/process disappears."""
    global _cleanup_job
    if os.name != "nt" or _cleanup_job:
        return
    from ctypes import wintypes as w
    class Limits(ctypes.Structure):
        _fields_ = [("times", ctypes.c_int64 * 2), ("flags", w.DWORD),
                    ("min", ctypes.c_size_t), ("max", ctypes.c_size_t), ("count", w.DWORD),
                    ("affinity", ctypes.c_size_t), ("priority", w.DWORD), ("scheduling", w.DWORD)]
    class Extended(ctypes.Structure):
        _fields_ = [("limits", Limits), ("io", ctypes.c_uint64 * 6), ("memory", ctypes.c_size_t * 4)]
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    create = kernel.CreateJobObjectW
    create.argtypes, create.restype = (ctypes.c_void_p, w.LPCWSTR), w.HANDLE
    set_info = kernel.SetInformationJobObject
    set_info.argtypes = (w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD)
    assign = kernel.AssignProcessToJobObject
    assign.argtypes = (w.HANDLE, w.HANDLE)
    handle = create(None, None)
    limit = Extended()
    limit.limits.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    if not (handle and set_info(handle, 9, ctypes.byref(limit), ctypes.sizeof(limit))):
        raise OSError("接続の終了処理を準備できませんでした。PC上で直接このファイルを開いてください。")
    # Enroll our three service children only. The default browser and any previously
    # running Forge/companion are independent of the launcher's window.
    _cleanup_job = (handle, assign)


def run(args):
    exe = tailscale_cli()
    host = tail_host(json_command(exe, "status", "--json"))
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    record = args.state_dir / "ios-serve.json"
    saved = json.loads(record.read_text(encoding="utf-8")) if record.exists() else {}
    port, route = selected_port(serve_config(exe), host, args.web_port, args.https_port, saved.get("port"))
    if len({port, args.forge_port, args.companion_port, args.web_port}) != 4:
        raise ValueError("Forge・PC補助・iOS Web・Tailscale HTTPSにはそれぞれ違うポートを指定してください。")
    origin = f"https://{host}:{port}"
    old_identity = web_identity(origin, args.web_port)
    serving = matches(old_identity, args, origin)
    if old_identity and not serving:
        raise ValueError("別のiOS接続がこのポートで動作中です。最初の接続ウィンドウを確認してください。")
    if route == "ours" and not (serving and saved.get("host") == host):
        raise ValueError("既存のTailscale接続とこのiOS補助サーバーの組み合わせを確認できません。")
    if not serving:
        with socket.socket() as available:
            try:
                available.bind(("127.0.0.1", args.web_port))
            except OSError as exc:
                raise ValueError("iOS用のローカルポートが使用中です。別のWebPortを選んでください。") from exc
    if saved.get("host") != host and not args.accept_certificate and not allow_certificate(host):
        print("起動を取り消しました。")
        return
    owned = not serving or route != "ours"
    with ExitStack() as stack:
        if owned:
            enable_child_cleanup()
        # The companion context terminates only the process that it created.
        if not serving:
            stack.enter_context(local_companion(args,start_process=start_process))
            web = stack.enter_context(child(ios_args(args, origin)))
            for _ in range(100):
                if web.poll() is not None:
                    raise RuntimeError("iOS用サーバーが起動できませんでした。上のメッセージを確認してください。")
                if matches(web_identity(origin, args.web_port), args, origin):
                    break
                time.sleep(.2)
            else:
                raise RuntimeError("iOS用のローカル接続を確認できません。")
        if route != "ours":
            # Foreground Serve creates an ephemeral route which ends with this child.
            # Never use --bg, --yes, funnel, reset, off, or configure existing ports.
            if port_status(serve_config(exe), host, port, args.web_port) != "free":
                raise ValueError("ポート設定が変わりました。もう一度開き直してください。")
            serve = stack.enter_context(child([exe, "serve", f"--https={port}", f"http://127.0.0.1:{args.web_port}"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace"))
            threading.Thread(target=show_serve_messages, args=(serve.stdout, not args.no_open), daemon=True).start()
            print("iPhone用のHTTPS接続を準備しています。初回はTailscale管理画面での許可が必要な場合があります。", flush=True)
            while True:
                if serve.poll() is not None:
                    raise RuntimeError("Tailscale Serveを起動できません。上のメッセージを確認してください。")
                if port_status(serve_config(exe), host, port, args.web_port) == "ours":
                    break
                time.sleep(2)
        # Check the real HTTPS certificate and proxy destination, not just the daemon's JSON.
        for _ in range(30):
            if matches(web_identity(origin, args.web_port, secure=True), args, origin, secure=True):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Tailscale HTTPS経由でPCに接続できません。証明書、接続ルール、MagicDNSを確認してください。")
        write_record(record, {"host":host, "port":port})
        if not args.serve_only:
            subprocess.run([*ios_args(args, origin), "--pair", *(["--no-open"] if args.no_open else [])], check=True)
        if not owned:
            return
        print("接続中はこのウィンドウを開いたままにしてください。終了するには Ctrl+C を押してください。", flush=True)
        while True:
            if not serving and web.poll() is not None:
                raise RuntimeError("iOS用サーバーが終了しました。")
            if route != "ours" and serve.poll() is not None:
                raise RuntimeError("Tailscale接続が終了しました。")
            time.sleep(1)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--forge-port",type=int,default=7860)
    p.add_argument("--companion-port",type=int,default=17872)
    p.add_argument("--web-port",type=int,default=17875)
    p.add_argument("--https-port",type=int)
    p.add_argument("--state-dir",type=Path, default=Path(os.environ.get("LOCALAPPDATA", Path.home())) / "ForgeRemoteE/Safari")
    p.add_argument("--no-open",action="store_true")
    p.add_argument("--serve-only",action="store_true",help="Start HTTPS without creating a pairing invitation.")
    p.add_argument("--accept-certificate",action="store_true")
    p.set_defaults(data_root=None, config=None, tag_extension=None, ar_extension=None, lora_root=())
    try:
        run(p.parse_args())
    except KeyboardInterrupt:
        print("接続を終了しました。")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        p.exit(1, str(exc) + "\n")


if __name__ == "__main__":
    main()
