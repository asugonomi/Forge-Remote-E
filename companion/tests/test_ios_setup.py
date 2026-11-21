import argparse
from contextlib import contextmanager
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import socket
import tempfile
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import ios_setup as setup
from ios_pairing_card import show_card


HOST = "studio.example-test.ts.net"
PORT = 18443
WEB = 17875
TARGET = f"http://127.0.0.1:{WEB}"
ORIGIN = f"https://{HOST}:{PORT}"


def route(port=PORT, proxy=TARGET):
    return {"TCP": {str(port): {"HTTPS": True}},
            "Web": {f"{HOST}:{port}": {"Handlers": {"/": {"Proxy": proxy}}}}}


class SetupTests(unittest.TestCase):
    def test_name_and_private_port(self):
        status = dict(BackendState="Running", Self={"DNSName": HOST+"."})
        self.assertEqual(setup.tail_host(status), HOST)
        for name in ("evil.invalid", "foo.ts.net@bad", "host./evil.example.ts.net"):
            with self.assertRaises(ValueError):
                setup.tail_host(dict(BackendState="Running", Self={"DNSName":name}))
        with self.assertRaises(ValueError):
            setup.tail_host(dict(BackendState="NeedsLogin"))
        self.assertEqual(setup.selected_port({}, HOST, WEB), (PORT, "free"))
        for number in (443, 8443, 10000, 0, 65536):
            with self.assertRaises(ValueError):
                setup.selected_port({}, HOST, WEB, number)

    def test_existing_routes_and_nested_owners(self):
        private = {"Foreground": {"session": route()}}
        self.assertEqual(setup.selected_port(private, HOST, WEB, saved=PORT),(PORT, "ours"))
        self.assertEqual(setup.selected_port(private, HOST, WEB), (PORT+1, "free"))
        altered = route()
        altered["Web"][f"{HOST}:{PORT}"]["Handlers"]["/other"] = {"Text":"owned by another app"}
        self.assertEqual(setup.port_status(altered, HOST, PORT, WEB), "busy")
        with self.assertRaises(ValueError):
            setup.selected_port({"Services":{"svc:other":altered}}, HOST, WEB, saved=PORT)
        later = route(proxy="http://127.0.0.1:12000")
        self.assertEqual(setup.port_status(later, HOST, PORT, WEB),"busy")
        self.assertEqual(setup.port_status({"TCP":{str(PORT):{"TCPForward":"127.0.0.1:9"}}}, HOST, PORT, WEB), "busy")
        matching = route()
        matching["AllowFunnel"] = {f"{HOST}:{PORT}":True}
        self.assertEqual(setup.port_status(matching, HOST, PORT, WEB),"busy")

    def test_funnel_upstream_on_another_port(self):
        public = route(443)
        public["AllowFunnel"] = {f"{HOST}:443":True}
        with self.assertRaises(ValueError):
            setup.selected_port({"Foreground":{"x":public}}, HOST, WEB)
        public["Web"][f"{HOST}:443"]["Handlers"]["/"]["Proxy"] = "http://127.0.0.1:5000"
        self.assertEqual(setup.selected_port(public, HOST, WEB), (PORT, "free"))
        public["TCP"]["443"] = {"TCPForward": "localhost:17875"}
        with self.assertRaises(ValueError):
            setup.selected_port(public, HOST, WEB)

    def test_offline_card_and_official_browser(self):
        url = ORIGIN+"/#pair="+"T"*43
        with tempfile.TemporaryDirectory() as folder, patch("ios_pairing_card.webbrowser.open") as browser:
            page = show_card(url, folder, open_browser=False)
            self.assertIn("<svg ", page.read_text(encoding="utf-8"))
            self.assertIn(url, page.read_text(encoding="utf-8"))
            self.assertNotIn("forgeremote://", page.read_text(encoding="utf-8"))
            browser.assert_not_called()
            show_card(url.replace("T"*43,"J"*43),folder)
            browser.assert_called_once_with(page.resolve().as_uri())
            self.assertNotIn(url, page.read_text(encoding="utf-8"))
            for invalid in (url.replace("https://","http://"),url.replace("/#pair=","/?pair="), url+"X"):
                with self.assertRaises(ValueError):
                    show_card(invalid, folder)
        url = "https://login.tailscale.com/f/serve?node=test-node"
        self.assertEqual(setup.official_consent("To enable, visit: "+url), url)
        for address in ("https://evil.invalid/f/serve","https://login.tailscale.com.evil.invalid/f/serve",
                        "https://login.tailscale.com/other-page","https://guest@login.tailscale.com/f/serve"):
            self.assertIsNone(setup.official_consent(address))

    def test_pwa_icons(self):
        from PIL import Image
        static = HERE/"ios_static"
        manifest = json.loads((static/"manifest.webmanifest").read_text(encoding="utf-8"))
        self.assertEqual(manifest["start_url"],"/")
        self.assertEqual(manifest["display"],"standalone")
        for row in manifest["icons"]:
            with Image.open(static/row["src"].lstrip("/")) as image:
                self.assertEqual(row["sizes"],"x".join(map(str,image.size)))
                self.assertEqual(image.mode,"RGB")
        with Image.open(static/"icons/apple-touch-icon.png") as icon:
            self.assertEqual(icon.size,(180,180))

    @unittest.skipUnless(os.name=="nt","Windows launcher process ownership")
    def test_closing_owner_cleans_only_the_child_process(self):
        # A disposable helper stands in for the local web server; no network or device is used.
        from ctypes import wintypes as w
        kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        kernel.OpenProcess.argtypes=(w.DWORD,w.BOOL,w.DWORD)
        kernel.OpenProcess.restype=w.HANDLE
        kernel.WaitForSingleObject.argtypes=(w.HANDLE,w.DWORD)
        kernel.CloseHandle.argtypes=(w.HANDLE,)
        helper="import sys,time;time.sleep(30)"
        supervisor=("import ios_setup as s,sys,subprocess; s.enable_child_cleanup(); "
                    "p=s.start_process([sys.executable,'-c',sys.argv[1]],stdin=subprocess.DEVNULL,"
                    "stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);print(p.pid,flush=True);input()")
        with subprocess.Popen([sys.executable,"-B","-c",supervisor,helper],cwd=HERE,stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE,text=True) as owner:
            pid=int(owner.stdout.readline())
            handle=kernel.OpenProcess(0x00100000,False,pid)
            self.assertTrue(handle)
            try:
                self.assertEqual(kernel.WaitForSingleObject(handle,0),258)
                owner.terminate()
                owner.wait(timeout=5)
                self.assertEqual(kernel.WaitForSingleObject(handle,5000),0)
            finally:
                kernel.CloseHandle(handle)

    def test_second_launch_preserves_both_processes_and_only_requests_local_invite(self):
        with tempfile.TemporaryDirectory() as td:
            state=Path(td)
            setup.write_record(state/"ios-serve.json", dict(host=HOST,port=PORT))
            args=argparse.Namespace(root=state, state_dir=state, web_port=WEB,companion_port=17872,forge_port=7860,
                                    https_port=None, no_open=True,serve_only=False,accept_certificate=False)
            ident={"product":"ForgeRemoteE-iOS","version":1,"origin":ORIGIN,"forge_port":7860,"companion_port":17872,
                   "state_key":setup.state_key(state)}
            with patch.object(setup,"tailscale_cli",return_value="fixture"), \
                 patch.object(setup,"json_command",return_value={"BackendState":"Running","Self":{"DNSName":HOST}}), \
                 patch.object(setup,"serve_config",return_value={"Foreground":{"only":route()}}), \
                 patch.object(setup,"web_identity",return_value=ident) as identity, \
                 patch.object(setup,"child",side_effect=AssertionError), \
                 patch.object(setup,"local_companion",side_effect=AssertionError), \
                 patch.object(setup,"allow_certificate",side_effect=AssertionError), \
                 patch.object(setup.subprocess,"run") as invite:
                setup.run(args)
                self.assertEqual(2,identity.call_count)
                self.assertEqual(identity.call_args.kwargs,{"secure":True})
                self.assertEqual(invite.call_args.args[0][-2:],["--pair","--no-open"])
                args.serve_only=True
                setup.run(args)
                self.assertEqual(invite.call_count,1)

    def test_first_start_only_private_while_owned_processes_are_alive(self):
        with tempfile.TemporaryDirectory() as td, socket.socket() as free:
            free.bind(("127.0.0.1",0))
            web_port=free.getsockname()[1]
            free.close()
            state=Path(td)
            args=argparse.Namespace(root=state,state_dir=state,web_port=web_port,companion_port=17872,forge_port=7860,
                                    https_port=None,no_open=True,serve_only=False,accept_certificate=True)
            events=[]
            @contextmanager
            def bridge(_args, **_kwargs):
                events.append("bridge")
                try:
                    yield
                finally:
                    events.append("closed bridge")
            @contextmanager
            def start(cmd, **_kwargs):
                label="serve" if cmd[0]=="fixture" else "web"
                if label=="serve":
                    self.assertEqual(cmd,["fixture","serve",f"--https={PORT}",f"http://127.0.0.1:{web_port}"])
                    self.assertIn("local identity", events)
                events.append(label)
                try:
                    yield Mock(stdout=[],poll=Mock(return_value=None))
                finally:
                    events.append("closed "+label)
            def identity(origin,port,secure=False):
                if "web" not in events:
                    return {}
                if secure:
                    self.assertIn("serve", events)
                events.append("HTTPS identity" if secure else "local identity")
                return {"origin":origin,"forge_port":7860,"companion_port":17872,
                        **({} if secure else {"state_key":setup.state_key(state)})}
            def pair(cmd,check):
                self.assertIn("HTTPS identity",events)
                self.assertTrue(check)
                self.assertEqual(cmd[-2:],["--pair","--no-open"])
                events.append("pair")
            with patch.object(setup,"tailscale_cli",return_value="fixture"), \
                 patch.object(setup,"json_command",return_value={"BackendState":"Running","Self":{"DNSName":HOST}}), \
                 patch.object(setup,"serve_config",side_effect=[{}, {}, {"Foreground":{"ours":route(proxy=f"http://127.0.0.1:{web_port}")}}]), \
                 patch.object(setup,"web_identity",side_effect=identity), patch.object(setup,"child",side_effect=start), \
                 patch.object(setup,"local_companion",side_effect=bridge), patch.object(setup,"enable_child_cleanup"), \
                 patch.object(setup.subprocess,"run",side_effect=pair), patch.object(setup.time,"sleep",side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    setup.run(args)
            self.assertEqual(events,["bridge","web","local identity","serve","HTTPS identity","pair",
                                     "closed serve","closed web","closed bridge"])


if __name__ == "__main__":
    unittest.main()
