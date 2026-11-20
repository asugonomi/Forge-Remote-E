"""One-command startup, additional pairing and shutdown using synthetic loopback services."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from forge_remote_gateway import _companion_ready, run
from gateway_state import GatewayState


class FakeForge(BaseHTTPRequestHandler):
    def log_message(self,*args):
        pass

    def do_GET(self):
        doc = json.dumps({}).encode()
        self.send_response(200 if self.path=="/sdapi/v1/options" else 404)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(doc)))
        self.end_headers()
        self.wfile.write(doc)


def fresh_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1",0))
        return s.getsockname()[1]


def card_secret(path):
    code = re.search(r"FER1\.([a-zA-Z0-9_-]+)",path.read_text(encoding="utf-8")).group(1)
    return json.loads(base64.urlsafe_b64decode(code+"=="))["secret"]


class Startup(unittest.TestCase):
    def test_launcher_reuses_running_gateway_and_stops_owned_companion(self):
        with tempfile.TemporaryDirectory() as td,ThreadingHTTPServer(("127.0.0.1",0),FakeForge) as forge:
            root = Path(td)
            (root/"modules").mkdir()
            (root/"launch.py").touch()
            args = argparse.Namespace(root=root,state_dir=root/"identity",forge_port=forge.server_port,
                                      companion_port=fresh_port(),port=fresh_port(),pair=False,list_devices=False,
                                      revoke=None,no_open=True,lan_address=None,no_lan=False,no_tailscale=True,
                                      data_root=None,config=None,tag_extension=None,ar_extension=None,lora_root=[])
            threading.Thread(target=forge.serve_forever,daemon=True).start()
            try:
                with patch("forge_remote_gateway.bind_addresses",return_value=("127.0.0.1",None)), \
                     patch("forge_remote_gateway.announce",return_value=nullcontext()), ThreadPoolExecutor(1) as pool:
                    stop=threading.Event()
                    future=pool.submit(run,args,stop)
                    card=args.state_dir/"pairing.html"
                    try:
                        deadline=time.monotonic()+25
                        while not card.exists() and time.monotonic()<deadline:
                            if future.done():
                                future.result()
                            time.sleep(0.1)
                        self.assertTrue(card.is_file(),"Gateway startup did not create the offline pairing card")
                        self.assertTrue(_companion_ready(args.companion_port))
                        initial=card_secret(card)
                        run(args)
                        self.assertEqual(card_secret(card),initial)
                        run(argparse.Namespace(**(vars(args) | {"pair":True})))
                        new=card_secret(card)
                        self.assertNotEqual(initial,new)
                        state=GatewayState(args.state_dir)
                        self.assertIsNone(state.redeem(initial,"old"))
                        token=state.redeem(new,"new phone")
                        self.assertTrue(state.authenticated("Bearer "+token))
                        identifier=state.devices()[0][0]
                        run(argparse.Namespace(**(vars(args) | {"revoke":identifier})))
                        self.assertFalse(state.authenticated("Bearer "+token))
                    finally:
                        stop.set()
                        future.result(timeout=15)
                self.assertFalse(_companion_ready(args.companion_port))
            finally:
                forge.shutdown()


if __name__=="__main__":
    unittest.main()
