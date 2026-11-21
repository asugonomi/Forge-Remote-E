"""Exercise the packaged browser server as a separate process against synthetic loopback Forge services."""
import base64
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

HERE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(HERE))
from ios_queue import Queue, ForgeCalls, task_id
from ios_web import Sessions

ORIGIN="https://fixture.test.ts.net:8443"
HOST="fixture.test.ts.net:8443"
LOGIN="fixture@example.invalid"
PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X1gAAAABJRU5ErkJggg=="
EPOCH="a"*32


def until(fn,seconds=12):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=fn()
        if value:
            return value
        time.sleep(.08)
    raise AssertionError("Timed out waiting for the expected fixture state")


def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1",0))
        return s.getsockname()[1]


class Service(BaseHTTPRequestHandler):
    def log_message(self,*args):
        pass

    def answer(self,data,status=200):
        encoded=json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(encoded)))
        try:
            self.end_headers()
            self.wfile.write(encoded)
        except OSError:
            pass

    def do_GET(self):
        self.server.hits.append(self.path)
        if self.path=="/sdapi/v1/options":
            self.answer({"live_previews_enable":True,"secret_example":"omitted"})
        else:
            self.answer({"synthetic":True})

    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        s=self.server
        s.hits.append(self.path)
        if self.path in ("/sdapi/v1/txt2img","/sdapi/v1/img2img"):
            s.runs.append(body["prompt"])
            s.active=body["force_task_id"]
            if body["prompt"].startswith("hold"):
                s.release.wait(20)
            s.active=None
            self.answer({"images":[PNG],"info":json.dumps({"infotexts":["synthetic Steps: 2"]})})
        elif self.path=="/internal/progress":
            active=body.get("id_task")==s.active
            self.answer(dict(active=active,progress=.34,id_live_preview=7,live_preview="data:image/png;base64,"+PNG))
        elif self.path=="/forge-remote/v1/job-control":
            active=task_id(body["proof"])==s.active
            if body["action"]=="poll":
                self.answer(dict(active=active,available=True,epoch=EPOCH,unit=3,status="idle"))
            elif body["action"]=="receipt":
                record=s.controls.get(body.get("request_id"))
                self.answer({"status":"applied","action":record} if record else {"status":"expired"})
            elif active and body["epoch"]==EPOCH and body["unit"]==3:
                s.controls[body["request_id"]]=body["action"]
                self.answer({"status":"queued"})
            else:
                self.answer({},409)
        else:
            self.answer({"synthetic":True})


class IosServer(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.static=self.root/"static"
        self.static.mkdir()
        (self.static/"index.html").write_text("<title>Synthetic UI</title>",encoding="utf-8")
        self.state=self.root/"state"
        self.ports=[port() for _ in range(3)]
        while len(set(self.ports))<3:
            self.ports=[port() for _ in range(3)]
        self.s=ThreadingHTTPServer(("127.0.0.1",self.ports[1]),Service)
        self.other=ThreadingHTTPServer(("127.0.0.1",self.ports[2]),Service)
        # Independent destination ports, shared mock generation/control lifecycle.
        self.s.runs=[];self.s.hits=[];self.s.controls={};self.s.release=threading.Event();self.s.active=None
        self.other.RequestHandlerClass=type("Companion",(Service,),{"do_POST":lambda obj: self.companion_post(obj)})
        self.other.hits=[]
        for s in (self.s,self.other):
            threading.Thread(target=s.serve_forever,daemon=True).start()
        self.process=None
        self.addCleanup(self.cleanup)
        self.start()

    def companion_post(self,handler):
        real=handler.server
        handler.server=self.s
        try:
            Service.do_POST(handler)
        finally:
            handler.server=real

    def start(self):
        args=[getattr(sys,"_base_executable",sys.executable),"-B",str(HERE/"ios_web.py"),
              "--origin",ORIGIN,"--port",str(self.ports[0]),"--forge-port",str(self.ports[1]),
              "--companion-port",str(self.ports[2]),"--state-dir",str(self.state),"--static-dir",str(self.static)]
        self.process=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        def ready():
            if self.process.poll() is not None:
                raise AssertionError(self.process.stderr.read().decode(errors="replace"))
            try:
                return self.request("GET","/api/identity")[0]==200
            except OSError:
                return False
        until(ready)

    def kill(self):
        if self.process:
            self.process.kill()
            self.process.wait(6)
            self.process.stderr.close()
            self.process=None

    def cleanup(self):
        self.s.release.set()
        self.kill()
        for s in (self.s,self.other):
            s.shutdown()
            s.server_close()

    def request(self,method,path,body=None,device=None,headers=None,disconnect=False):
        conn=http.client.HTTPConnection("127.0.0.1",self.ports[0],timeout=8)
        h={"Host":HOST,"Tailscale-User-Login":LOGIN}
        if method=="POST":
            h.update({"Origin":ORIGIN,"Content-Type":"application/json"})
        if device:
            h["Cookie"],h["X-CSRF-Token"]=device
        for k,v in (headers or {}).items():
            if v is None:
                h.pop(k,None)
            else:
                h[k]=v
        try:
            conn.request(method,path,body=None if body is None else json.dumps(body),headers=h)
            if disconnect:
                return
            response=conn.getresponse()
            raw=response.read()
            parsed=json.loads(raw) if response.getheader("Content-Type","").startswith("application/json") else raw
            return response.status,parsed,response.getheader("Set-Cookie","")
        finally:
            conn.close()

    def offer(self):
        q=Queue(self.state,ForgeCalls(self.ports[1],self.ports[2]))
        return Sessions(q,ORIGIN).issue().rsplit("#pair=",1)[1]

    def pair(self,offer=None):
        status,data,cookie=self.request("POST","/api/pair",{"secret":offer or self.offer(),"device_name":"synthetic"})
        self.assertEqual(status,200)
        for flag in ("HttpOnly","Secure","SameSite=Strict","Max-Age="):
            self.assertIn(flag,cookie)
        return cookie.split(";")[0],data["csrf"]

    def test_security_sessions_and_routes(self):
        self.assertNotIn("state_key",self.request("GET","/api/identity")[1])
        direct=self.request("GET","/api/identity",headers={"Host":f"127.0.0.1:{self.ports[0]}","Tailscale-User-Login":None})
        self.assertEqual((direct[0],len(direct[1]["state_key"])),(200,64))
        for headers in [{"Host":"forged.invalid"},{"Tailscale-Funnel-Request":"?1"},{"Tailscale-User-Login":None}]:
            self.assertEqual(self.request("GET","/",headers=headers)[0],403)
        self.assertIn(b"Synthetic UI",self.request("GET","/")[1])
        self.assertEqual(self.request("GET","/api/queue")[0],401)
        offer=self.offer()
        for headers in [{"Origin":None},{"Origin":"https://unrelated.invalid"}]:
            self.assertEqual(self.request("POST","/api/pair",{"secret":offer},headers=headers)[0],403)
        device=self.pair(offer)
        self.assertEqual(self.request("POST","/api/pair",{"secret":offer})[0],403)
        self.assertEqual(self.request("GET","/api/session",device=device)[1]["csrf"],device[1])
        self.assertEqual(self.request("GET","/api/session",device=device,headers={"Tailscale-User-Login":"other"})[0],401)
        for headers in [{"X-CSRF-Token":None},{"Origin":"null"},{"Sec-Fetch-Site":"cross-site"}]:
            self.assertEqual(self.request("POST","/api/queue",{"action":"pause"},device,headers)[0],403)
        self.assertEqual(self.request("GET","/sdapi/v1/options",device=device)[1],{"live_previews_enable":True})
        self.assertEqual(self.request("GET","/forge-remote/v1/gallery-index/dates?refresh=false",device=device)[0],200)
        self.assertEqual(self.request("POST","/sdapi/v1/png-info",{"image":PNG},device)[0],200)
        self.assertEqual(self.request("POST","/forge-remote/v1/size-presets/ratio",{},device)[0],200)
        for path in ("/sdapi/v1/interrupt","/internal/progress","/sdapi/v1/txt2img","/forge-remote/v1/job-control"):
            self.assertEqual(self.request("POST",path,{},device)[0],404)
        self.assertEqual(self.request("POST","/sdapi/v1/png-info",{"image":"https://example.invalid/"},device)[0],422)
        fake=self.pair()
        settings={"txt2img":{"steps":7,"prompt":"spring garden"}}
        self.assertEqual(self.request("POST","/api/preferences",settings,device)[0],200)
        self.assertEqual(self.request("GET","/api/preferences",device=fake)[1],{})
        self.kill();self.start()
        self.assertEqual(self.request("GET","/api/preferences",device=device)[1],settings)

    def test_durable_idempotent_order_disconnect_control_results(self):
        a,b=self.pair(),self.pair()
        self.request("POST","/api/queue",{"action":"pause"},a)
        first,second=str(uuid.uuid4()),str(uuid.uuid4())
        def enqueue(id_,prompt,**kwargs):
            return self.request("POST","/api/jobs",{"id":id_,"mode":"txt2img","payload":{"prompt":prompt}},a,**kwargs)
        def jobs():
            return self.request("GET","/api/queue",device=a)[1]["jobs"]
        def action(id_,data,who=a):
            return self.request("POST","/api/jobs/"+id_+"/action",data,who)
        self.assertEqual(enqueue(first,"other")[0],202)
        enqueue(second,"hold garden",disconnect=True)
        until(lambda:len(jobs())==2)
        self.assertEqual(enqueue(second,"hold garden")[0],202)
        self.assertEqual(enqueue(second,"different")[0],409)
        self.assertEqual(action(second,{"action":"move","before":first})[0],200)
        self.assertEqual(action(first,{"action":"delete"},b)[0],404)
        self.kill();self.start()
        self.assertEqual([j["id"] for j in jobs()],[second,first])
        self.request("POST","/api/queue",{"action":"resume"},a)
        current=until(lambda:next((j for j in jobs() if j["id"]==second and j["control"] and j["preview_version"]>0),None))
        self.assertEqual(current["progress"],.34)
        route="/api/jobs/"+second
        self.assertEqual(self.request("GET",route+"/preview",device=a)[1],base64.b64decode(PNG))
        self.assertEqual(self.request("GET",route+"/preview",device=b)[0],404)
        data=dict(action="skip",epoch=EPOCH,unit=3,request_id=str(uuid.uuid4()))
        self.assertEqual(action(second,dict(data,unit=2))[0],409)
        self.assertEqual(action(second,data)[0],200)
        self.assertEqual(action(second,data)[0],200)
        self.assertEqual(action(second,dict(data,action="interrupt",request_id=str(uuid.uuid4())))[0],409)
        until(lambda:self.s.controls)
        self.s.release.set()
        until(lambda:next((j for j in jobs() if j["id"]==second and j["state"]=="done_with_skip"),None))
        until(lambda:len([j for j in jobs() if j["result_count"]==1])==2)
        self.assertEqual(self.s.runs,["hold garden","other"])
        self.assertIn("infotexts",self.request("GET",route+"/info",device=a)[1]["info"])
        self.kill();self.start()
        self.assertEqual(self.request("GET",route+"/results/0",device=a)[1],base64.b64decode(PNG))
        self.assertEqual(self.request("GET",route+"/results/0",device=b)[0],404)
        self.assertEqual(action(second,{"action":"delete"})[0],200)
        self.assertEqual(enqueue(second,"hold garden")[0],409)

    def test_restart_mid_submission_never_replays(self):
        a=self.pair()
        job=str(uuid.uuid4())
        submit={"id":job,"mode":"img2img","payload":{"prompt":"hold image","init_images":[PNG]}}
        self.assertEqual(self.request("POST","/api/jobs",submit,a)[0],202)
        until(lambda:self.s.active)
        self.kill();self.start()
        self.assertEqual(self.request("GET","/api/queue",device=a)[1]["jobs"][0]["state"],"unknown")
        self.assertEqual(self.request("POST","/api/jobs",submit,a)[1]["state"],"unknown")
        self.s.release.set()
        self.assertEqual(self.s.runs,["hold image"])

    def test_interrupt_is_scoped_and_cli_invite_cannot_spawn_second_worker(self):
        # The CLI issues a card offline, against the same private state as the serving worker.
        cli=[getattr(sys,"_base_executable",sys.executable),"-B",str(HERE/"ios_web.py"),
             "--origin",ORIGIN,"--state-dir",str(self.state)]
        subprocess.run(cli+["--pair","--no-open"],check=True,capture_output=True,timeout=8)
        text=(self.state/"ios-pairing.html").read_text(encoding="utf-8")
        import re
        invite=re.search(r"#pair=([A-Za-z0-9_-]{43})",text)[1]
        a=self.pair(invite)
        occupied=port()
        second=subprocess.run(cli+["--port",str(occupied)],capture_output=True,timeout=8)
        self.assertNotEqual(second.returncode,0)
        job=str(uuid.uuid4())
        self.request("POST","/api/jobs",{"id":job,"mode":"txt2img","payload":{"prompt":"hold bowl"}},a)
        def current():
            return self.request("GET","/api/queue",device=a)[1]["jobs"][0]
        until(lambda:current()["control"])
        ticket=dict(action="interrupt",epoch=EPOCH,unit=3,request_id=str(uuid.uuid4()))
        path="/api/jobs/"+job+"/action"
        self.assertEqual(self.request("POST",path,ticket,a)[0],200)
        until(lambda:len(self.s.controls)==1)
        self.s.release.set()
        until(lambda:current()["state"]=="interrupted")
        self.assertEqual(self.request("POST",path,ticket,a)[1]["status"],"applied")
        self.assertNotIn("/sdapi/v1/interrupt",self.s.hits)
        self.assertNotIn("/sdapi/v1/skip",self.s.hits)


if __name__=="__main__":
    unittest.main()
