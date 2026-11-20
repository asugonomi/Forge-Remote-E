"""Synthetic acceptance tests: all listeners use ephemeral IPv4 loopback ports."""
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from forge_remote_gateway import active_identity, bind_addresses, in_network, LAN, TAIL
from gateway_http import COMPANION, SD, GatewayServer, destination
from gateway_state import GatewayState


def unseal(code):
    assert code.startswith("FER1.")
    return json.loads(base64.urlsafe_b64decode(code[5:]+"=="))


class Fake(BaseHTTPRequestHandler):
    def log_message(self,*args):
        pass

    def do_GET(self):
        self.do_POST()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length",0)))
        self.server.seen.append((self.command,self.path,dict(self.headers),body))
        if self.path==SD+"options":
            value = {"private_test_setting":"secret", "live_previews_enable":True, "lora_preferred_name":"Filename"}
        else:
            value = {"upstream":self.server.tag}
        payload = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(payload)))
        self.send_header("Set-Cookie","should-never-be-forwarded=1")
        self.end_headers()
        self.wfile.write(payload)


class GatewayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.resources = ExitStack()
        cls.addClassCleanup(cls.resources.close)
        cls.directory = cls.resources.enter_context(tempfile.TemporaryDirectory())
        cls.state = GatewayState(cls.directory)
        cls.upstreams = {}
        for tag in ("forge","companion"):
            server = ThreadingHTTPServer(("127.0.0.1",0),Fake)
            cls.upstreams[tag] = server
            server.tag, server.seen = tag, []
            cls.run_server(server)
        ports = {key:s.server_port for key,s in cls.upstreams.items()}
        cls.gw = GatewayServer(("127.0.0.1",0),cls.state,cls.state.tls(),ports,threading.BoundedSemaphore(8))
        cls.run_server(cls.gw)

    @classmethod
    def run_server(cls,server):
        cls.resources.enter_context(server)
        thread = threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        cls.resources.callback(server.shutdown)

    def call(self,method,path,body=None,token=None,headers=None):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        c = http.client.HTTPSConnection("127.0.0.1",self.gw.server_port,timeout=5,context=ctx)
        try:
            c.connect()
            self.assertEqual(self.state.pin,hashlib.sha256(c.sock.getpeercert(binary_form=True)).hexdigest())
            h = dict(headers or {})
            if token:
                h["Authorization"] = "Bearer "+token
            if body is not None:
                h["Content-Type"] = "application/json"
                body = json.dumps(body).encode()
            c.request(method,path,body=body,headers=h)
            r = c.getresponse()
            raw = r.read()
            response = json.loads(raw) if r.getheader("Content-Type","").startswith("application/json") else {}
            self.assertIsNone(r.getheader("Set-Cookie"))
            return r.status,response
        finally:
            c.close()

    def invite(self,**kwargs):
        record = unseal(self.state.invite(["https://192.168.1.7:17873"],"test PC",**kwargs))
        self.assertEqual((record["v"],record["server_id"],record["pin"]),(1,self.state.id,self.state.pin))
        return record["secret"]

    def token(self,name="test phone"):
        status,data = self.call("POST","/pair",{"secret":self.invite(),"device_name":name})
        self.assertEqual(status,200)
        self.assertEqual((data["version"],data["server_id"]),(1,self.state.id))
        return data["token"]

    def test_two_devices_revoke_and_restart(self):
        a,b=self.token("QA one"),self.token("QA two")
        self.assertNotEqual(a,b)
        self.assertEqual(self.call("GET","/health")[0],401)
        self.assertEqual(self.call("GET","/health",token=b)[1],{"version":1,"server_id":self.state.id})
        duplicate=GatewayState(self.directory)
        self.assertEqual((duplicate.id,duplicate.pin),(self.state.id,self.state.pin))
        self.assertTrue(duplicate.authenticated("Bearer "+a))
        self.assertTrue(duplicate.revoke(next(i for i,n in duplicate.devices() if n=="QA one")))
        self.assertEqual(self.call("GET","/health",token=a)[0],401)
        self.assertEqual(self.call("GET","/health",token=b)[0],200)
        self.assertTrue(active_identity("127.0.0.1",self.gw.server_port,self.state.pin))
        self.assertFalse(active_identity("127.0.0.1",self.gw.server_port,"0"*64))
        data=self.state.path.read_bytes()
        self.assertNotIn(a.encode(),data)
        self.assertNotIn(b.encode(),data)

    def test_only_one_concurrent_redemption_and_expiry(self):
        secret=self.invite()
        with ThreadPoolExecutor(max_workers=2) as ex:
            statuses=list(ex.map(lambda n:self.call("POST","/pair",{"secret":secret,"device_name":n})[0],["A","B"]))
        self.assertEqual(sorted(statuses),[200,403])
        stale=self.invite(now=0)
        self.assertEqual(self.call("POST","/pair",{"secret":stale})[0],403)
        superseded=self.invite()
        self.invite()
        self.assertEqual(self.call("POST","/pair",{"secret":superseded})[0],403)

    def test_allowed_requests_reach_fixed_backends(self):
        token=self.token()
        for path,tag in [(SD+"samplers","forge"),("/adetailer/v1/schema","forge"),
                         ("/tacapi/v1/lora-info/%E3%83%86%E3%82%B9%E3%83%88","forge"),
                         (COMPANION+"gallery/"+"f"*32+"/display?large=true","companion"),
                         (COMPANION+"tags?formatted=true&q=cup","companion")]:
            code,data=self.call("GET",path,token=token,headers={"Cookie":"not-forwarded","X-Forwarded-For":"test"})
            self.assertEqual((code,data),(200,{"upstream":tag}))
            method,received,headers,body=self.upstreams[tag].seen[-1]
            self.assertEqual((method,received),("GET",path))
            self.assertTrue({"Authorization","Cookie","X-Forwarded-For"}.isdisjoint(headers))
            self.assertEqual(headers["Host"],"127.0.0.1:"+str(self.upstreams[tag].server_port))
        for path,payload,tag in [(SD+"txt2img",{"prompt":"cup","script_name":"test","script_args":[]},"forge"),
                                (SD+"img2img",{"init_images":["YWJj"]},"forge"),
                                (SD+"png-info",{"image":"data:image/png;base64,YWJj"},"forge"),
                                ("/internal/progress",{"id_task":"synthetic"},"forge"),
                                (COMPANION+"job-control",{"action":"observe"},"companion")]:
            self.assertEqual(self.call("POST",path,payload,token)[0],200)
            self.assertEqual(json.loads(self.upstreams[tag].seen[-1][3]),payload)
        status,values=self.call("GET",SD+"options",token=token)
        self.assertEqual((status,values),(200,{"live_previews_enable":True,"lora_preferred_name":"Filename"}))

    def test_unapproved_paths_and_methods_are_local_only(self):
        a=self.token()
        count=sum(len(s.seen) for s in self.upstreams.values())
        for method,path,form in [("POST",SD+"options",{}),("POST",SD+"interrupt",{}),("GET","/config",None),
                                 ("GET",COMPANION+"tags?url=http%3A%2F%2Funused",None),
                                 ("GET","/tacapi/v1/lora-info/%2e%2e%2fdata",None),
                                 ("GET","https://example.invalid/config",None),
                                 ("GET","//example.invalid/config",None),
                                 ("POST",SD+"png-info",{"image":"https://example.invalid/image"}),
                                 ("POST",SD+"img2img",{"init_images":["http://example.invalid/image"]}),
                                 ("POST","/internal/progress",{"value":"x"*8200}),
                                 ("PUT",SD+"options",{})]:
            self.assertGreaterEqual(self.call(method,path,form,a)[0],400)
        self.assertEqual(self.call("GET",SD+"samplers")[0],401)
        self.assertEqual(self.call("GET",SD+"samplers",token=a,headers={"Origin":"https://example.invalid"})[0],400)
        self.assertEqual(sum(len(s.seen) for s in self.upstreams.values()),count)

    def test_network_selection(self):
        for value in ["0.0.0.0","127.0.0.1","8.8.8.8","169.254.1.1","100.95.1.1","::"]:
            self.assertFalse(in_network(value,LAN))
            with self.assertRaises(ValueError):
                bind_addresses(value,False,True)
        with patch("forge_remote_gateway.physical_lan",return_value=["192.168.1.2"]), \
             patch("forge_remote_gateway.tailscale_address",return_value="100.90.1.2"):
            self.assertEqual(bind_addresses(None,False,False),("192.168.1.2","100.90.1.2"))
        with patch("forge_remote_gateway.physical_lan",return_value=["10.1.1.2","192.168.1.2"]):
            with self.assertRaises(ValueError):
                bind_addresses(None,False,True)
        self.assertTrue(in_network("100.100.12.7",TAIL))
        self.assertIsNone(destination("GET",COMPANION+"tags?q=x&q=y"))


if __name__=="__main__":
    unittest.main()
