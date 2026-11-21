"""Same-origin iOS web app. Trusted HTTPS terminates at Tailscale Serve on this PC."""
import argparse
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import socket
import threading
import time
from urllib.parse import urlsplit
import uuid

from gateway_http import COMPANION, GatewayHandler, MIB, RequestError, SD, destination, read_payload, safe_image
from ios_queue import ForgeCalls, Queue, json_bytes

COOKIE="__Host-ForgeRemoteSafari"
LIFETIME=365*86400
MEDIA={".html":"text/html; charset=utf-8",".js":"text/javascript; charset=utf-8",".css":"text/css; charset=utf-8",
       ".webmanifest":"application/manifest+json",".png":"image/png",".svg":"image/svg+xml",".ico":"image/x-icon",
       ".woff2":"font/woff2",".json":"application/json; charset=utf-8"}
JOB_ROUTE=re.compile(r"/api/jobs/([a-f0-9-]{36})/(action|preview|info|results/([0-9]{1,5}))\Z")


def canonical_origin(value):
    p=urlsplit(value)
    if (p.scheme!="https" or not p.hostname or p.username or p.password or p.path not in ("","/") or p.query or p.fragment
        or not re.fullmatch(r"[a-z0-9-]+(?:\.[a-z0-9-]+)+",p.hostname)
        or not p.hostname.endswith(".ts.net")):
        raise ValueError("Expected the HTTPS origin of this Tailscale Serve host.")
    port=p.port
    if port is not None and not 1<=port<=65535:
        raise ValueError("Invalid HTTPS port.")
    return "https://"+p.hostname+(f":{port}" if port and port!=443 else "")


def default_directory():
    if os.name=="nt":
        return Path(os.environ["LOCALAPPDATA"])/"ForgeRemoteE/Safari"
    return Path(os.environ.get("XDG_STATE_HOME",Path.home()/".local/state"))/"forge-remote-e/safari"


def digest(val):
    return hashlib.sha256(val.encode("utf-8")).hexdigest()


def csrf(token):
    return hmac.new(token.encode("ascii"),b"ios-web-csrf-v1",hashlib.sha256).hexdigest()


class Sessions:
    def __init__(self, queue, origin):
        self.queue,self.origin=queue,origin
        with queue.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS web_invite(slot INTEGER PRIMARY KEY,digest TEXT,origin TEXT,expires REAL);
                CREATE TABLE IF NOT EXISTS web_devices(owner TEXT PRIMARY KEY,label TEXT,token TEXT UNIQUE,
                    login TEXT,origin TEXT,expires REAL,prefs BLOB NOT NULL);
            """)

    def issue(self):
        value=secrets.token_urlsafe(32)
        with self.queue.db(True) as db:
            db.execute("REPLACE INTO web_invite VALUES(1,?,?,?)",(digest(value),self.origin,time.time()+900))
        return self.origin+"/#pair="+value

    def redeem(self,value,name,login):
        if not isinstance(value,str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}",value):
            raise RequestError(403)
        with self.queue.db(True) as db:
            invite=db.execute("SELECT digest,expires FROM web_invite WHERE slot=1 AND origin=?",(self.origin,)).fetchone()
            if not invite or invite["expires"]<=time.time() or not hmac.compare_digest(invite["digest"],digest(value)):
                raise RequestError(403)
            token=secrets.token_urlsafe(32)
            owner=str(uuid.uuid4())
            label="".join(c for c in str(name or "Safari") if c.isprintable())[:64]
            db.execute("INSERT INTO web_devices VALUES(?,?,?,?,?,?,?)",
                       (owner,label,digest(token),digest(login),self.origin,time.time()+LIFETIME,b"{}"))
            db.execute("DELETE FROM web_invite WHERE slot=1")
        return token

    def identify(self,token,login):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}",token):
            raise RequestError(401)
        with self.queue.db() as db:
            row=db.execute("SELECT owner FROM web_devices WHERE token=? AND login=? AND origin=? AND expires>?",
                           (digest(token),digest(login),self.origin,time.time())).fetchone()
        if not row:
            raise RequestError(401)
        return row[0]


class BrowserHandler(GatewayHandler):
    server_version="ForgeRemoteSafari"
    def setup(self):
        BaseHTTPRequestHandler.setup(self)

    def end_headers(self):
        self.send_header("Referrer-Policy","no-referrer")
        self.send_header("X-Frame-Options","DENY")
        self.send_header("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                         "img-src 'self' data: blob:; connect-src 'self'; font-src 'self'; object-src 'none'; "
                         "worker-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
        super().end_headers()

    def bytes(self,code,mime,raw=b"",extra=None):
        self.send_response(code)
        if code!=204:
            self.send_header("Content-Type",mime)
        self.send_header("Content-Length",str(len(raw)))
        self.send_header("Cache-Control","no-store")
        self.send_header("X-Content-Type-Options","nosniff")
        for key,value in (extra or {}).items():
            self.send_header(key,value)
        self.end_headers()
        if raw:
            self.wfile.write(raw)

    def data(self,code,obj,extra=None):
        self.bytes(code,"application/json; charset=utf-8",json_bytes(obj),extra)

    def run_api(self):
        if self.headers.get_all("Tailscale-Funnel-Request") or self.headers.get("Transfer-Encoding"):
            raise RequestError(403)
        target=self.requestline.split()[1]
        if len(target)>4096 or not target.startswith("/") or target.startswith("//") or "#" in target or "\\" in target:
            raise RequestError(400)
        origin=self.server.origin
        hosts=self.headers.get_all("Host",[])
        expected=urlsplit(origin).netloc
        if len(hosts)!=1:
            raise RequestError(400)
        host=hosts[0].lower()
        if self.command=="GET" and target=="/api/identity" and host in (expected,f"127.0.0.1:{self.server.server_port}"):
            identity=dict(product="ForgeRemoteE-iOS",version=1,origin=origin,
                          forge_port=self.server.ports["forge"],companion_port=self.server.ports["companion"])
            if host==f"127.0.0.1:{self.server.server_port}":
                folder=os.path.normcase(str(self.server.queue.directory.resolve()))
                identity["state_key"]=hashlib.sha256(os.fsencode(folder)).hexdigest()
            self.data(200,identity)
            return
        if host!=expected:
            raise RequestError(403)
        identities=self.headers.get_all("Tailscale-User-Login",[])
        if len(identities)!=1 or not 0<len(identities[0])<=512:
            raise RequestError(403)
        login=identities[0]
        if self.command=="GET" and ("Content-Length" in self.headers or "Expect" in self.headers):
            raise RequestError(400)
        if self.command=="POST" and self.headers.get_all("Origin",[])!=[origin]:
            raise RequestError(403)
        path=urlsplit(target).path
        if self.command=="GET" and self.static(path):
            return
        if self.headers.get("Sec-Fetch-Site") not in (None,"same-origin","none"):
            raise RequestError(403)
        sessions=self.server.sessions
        if self.command=="POST" and target=="/api/pair":
            _,form=read_payload(self,4096)
            token=sessions.redeem(form.get("secret"),form.get("device_name"),login)
            self.data(200,{"csrf":csrf(token)},{"Set-Cookie":f"{COOKIE}={token}; Path=/; Max-Age={LIFETIME}; Secure; HttpOnly; SameSite=Strict"})
            return
        values=[v.strip().partition("=")[2] for line in self.headers.get_all("Cookie",[])
                for v in line.split(";") if v.strip().partition("=")[0]==COOKIE]
        if len(values)!=1:
            raise RequestError(401)
        token=values[0]
        owner=sessions.identify(token,login)
        if self.command=="POST":
            headers=self.headers.get_all("X-CSRF-Token",[])
            if len(headers)!=1 or not hmac.compare_digest(headers[0].encode(),csrf(token).encode()):
                raise RequestError(403)
        if target=="/api/session" and self.command=="GET":
            self.data(200,{"csrf":csrf(token)})
            return
        if target=="/api/preferences":
            self.preferences(owner)
            return
        q=self.server.queue
        if target=="/api/queue":
            if self.command=="POST":
                _,form=read_payload(self,4096)
                q.pause(owner,form.get("action"))
            self.data(200,q.snapshot(owner))
            return
        if self.command=="POST" and target=="/api/jobs":
            _,form=read_payload(self,64*MIB+4096)
            self.data(202,q.enqueue(owner,form.get("id"),form.get("mode"),form.get("payload")))
            return
        match=JOB_ROUTE.fullmatch(target)
        if match:
            job,part,index=match.groups()
            if self.command=="POST" and part=="action":
                _,form=read_payload(self,4096)
                self.data(200,q.change(owner,job,form))
            elif self.command=="GET" and part=="info":
                self.data(200,{"info":q.get_data(owner,job,"info")})
            elif self.command=="GET" and (part=="preview" or index is not None):
                pic=q.get_data(owner,job,part,int(index or 0))
                if pic:
                    self.bytes(200,*pic)
                else:
                    self.bytes(204,"")
            else:
                raise RequestError(405)
            return
        dest=destination(self.command,target)
        if self.command=="GET" and dest:
            self.forward(dest,target,None)
            return
        if self.command=="POST" and target in (SD+"png-info",COMPANION+"size-presets/ratio"):
            payload,form=read_payload(self,64*MIB if target==SD+"png-info" else 4096)
            if target==SD+"png-info" and not safe_image(form.get("image")):
                raise RequestError(422)
            self.forward(dest,target,payload)
            return
        raise RequestError(404)

    def preferences(self,owner):
        queue=self.server.queue
        if self.command=="POST":
            _,form=read_payload(self,192*1024)
            with queue.db(True) as db:
                db.execute("UPDATE web_devices SET prefs=? WHERE owner=?",(json_bytes(form),owner))
            self.data(200,{"ok":True})
        else:
            with queue.db() as db:
                row=db.execute("SELECT prefs FROM web_devices WHERE owner=?",(owner,)).fetchone()
            if not row:
                raise RequestError(401)
            self.bytes(200,"application/json; charset=utf-8",row[0])

    def static(self,path):
        location="index.html" if path=="/" else path[1:]
        if not re.fullmatch(r"(?:[A-Za-z0-9][A-Za-z0-9_-]*/)*[A-Za-z0-9][A-Za-z0-9_.-]*",location):
            return False
        base=self.server.assets
        file=(base/location).resolve()
        mime=MEDIA.get(file.suffix.lower())
        if not mime or not file.is_relative_to(base) or not file.is_file():
            return False
        if file.stat().st_size>8*MIB:
            raise RequestError(500)
        self.bytes(200,mime,file.read_bytes())
        return True


class BrowserServer(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,port,origin,queue,assets,sessions):
        self.origin=origin
        self.queue=queue
        self.sessions=sessions
        self.ports=queue.calls.ports
        self.assets=Path(assets).resolve()
        self.slots=threading.BoundedSemaphore(24)
        super().__init__(("127.0.0.1",port),BrowserHandler)

    def server_bind(self):
        if hasattr(socket,"SO_EXCLUSIVEADDRUSE"):
            self.allow_reuse_address=False
            self.socket.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
        super().server_bind()

    def get_request(self):
        conn,addr=super().get_request()
        conn.settimeout(30)
        return conn,addr

    def process_request(self,request,peer):
        if self.slots.acquire(blocking=False):
            try:
                super().process_request(request,peer)
            except BaseException:
                self.slots.release()
                raise
        else:
            self.shutdown_request(request)

    def process_request_thread(self,request,peer):
        try:
            super().process_request_thread(request,peer)
        finally:
            self.slots.release()

    def handle_error(self,request,peer):
        # Never log browser request data, prompts, cookies or registration codes.
        pass


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin",required=True,type=canonical_origin)
    parser.add_argument("--port",type=int,default=17875)
    parser.add_argument("--forge-port",type=int,default=7860)
    parser.add_argument("--companion-port",type=int,default=17872)
    parser.add_argument("--state-dir",type=Path,default=default_directory())
    parser.add_argument("--static-dir",type=Path,default=Path(__file__).with_name("ios_static"))
    admin=parser.add_mutually_exclusive_group()
    admin.add_argument("--pair",action="store_true")
    admin.add_argument("--list-devices",action="store_true")
    admin.add_argument("--revoke")
    parser.add_argument("--no-open",action="store_true")
    args=parser.parse_args()
    ports=(args.port,args.forge_port,args.companion_port)
    if len(set(ports))!=3 or not all(1<=p<=65535 for p in ports):
        parser.error("Web, companion and Forge ports must differ and be in 1..65535.")
    calls=ForgeCalls(args.forge_port,args.companion_port)
    queue=Queue(args.state_dir,calls)
    sessions=Sessions(queue,args.origin)
    if args.pair:
        from ios_pairing_card import show_card
        show_card(sessions.issue(),args.state_dir,open_browser=not args.no_open)
        print("Private single-device pairing card created.")
        return
    if args.list_devices:
        with queue.db() as db:
            for row in db.execute("SELECT owner,label FROM web_devices WHERE origin=?",(args.origin,)):
                print(row["owner"],row["label"])
        return
    if args.revoke:
        with queue.db(True) as db:
            n=db.execute("DELETE FROM web_devices WHERE owner=? AND origin=?",(args.revoke,args.origin)).rowcount
            if not n:
                parser.exit(2,"Device not registered.\n")
            db.execute("REPLACE INTO prefs VALUES(?,1)",(args.revoke,))
        print("Device revoked.")
        return
    with BrowserServer(args.port,args.origin,queue,args.static_dir,sessions) as server:
        queue.start()
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            queue.close()


if __name__=="__main__":
    main()
