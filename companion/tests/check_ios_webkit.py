"""WebKit user journey through the real HTTP/session/queue implementation with synthetic PC inputs."""
from contextlib import ExitStack
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import http.client
import ssl
import sys
import tempfile
import threading
from urllib.parse import urlsplit

src=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(src))
from playwright.sync_api import sync_playwright, expect
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from ios_web import Sessions, BrowserServer
from ios_queue import Queue, ForgeCalls
from test_ios_server import Service, port, until

ORIGIN="https://browser-test.example.ts.net:18443"
profile={"prompt":"a blue ceramic mug","negative_prompt":"fog","width":384,"height":384,"steps":8,"cfg_scale":2,
         "distilled_cfg_scale":3,"seed":-1,"sampler_name":"Euler","scheduler":"Simple","batch_size":1,"n_iter":1}
class PC(Service):
    def do_GET(self):
        route=urlsplit(self.path).path
        items={"/sdapi/v1/samplers":[{"name":"Euler"}],"/sdapi/v1/schedulers":[{"label":"Simple"}],
               "/forge-remote/v1/generation-defaults":{"active":"Anima","txt2img":profile,"img2img":profile},
               "/forge-remote/v1/size-presets":{"ratios":[],"resolutions":[{"label":"384 × 384","width":384,"height":384}]},
               "/forge-remote/v1/tags":[{"name":"blue_sky_(demo)","insert":r"blue sky \(demo\)","count":810}],
               "/forge-remote/v1/capabilities":{"tags":True},"/forge-remote/v1/gallery-index/dates":{"days":[],"version":1,"total":0},
               "/forge-remote/v1/gallery-index/page":{"items":[],"version":1,"offset":0,"total":0},
               "/sdapi/v1/script-info":[{"name":"Museum fixture","is_img2img":False,"is_alwayson":True,"args":[{"label":"Dose","value":2}]}]}
        if route in items:self.answer(items[route])
        else:super().do_GET()

class LocalTLS(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def relay(self):
        h={k:v for k,v in self.headers.items() if k.lower() in {"accept","content-type","cookie","x-csrf-token","sec-fetch-site"}}
        h["Host"]=urlsplit(ORIGIN).netloc
        h["Tailscale-User-Login"]="local-fixture@example.invalid"
        origin=self.headers.get("Origin")
        if origin==self.server.external:h["Origin"]=ORIGIN
        body=self.rfile.read(int(self.headers.get("content-length",0))) if self.command=="POST" else None
        c=http.client.HTTPConnection("127.0.0.1",self.server.backend,timeout=10)
        try:
            c.request(self.command,self.path,body,headers=h);r=c.getresponse();content=r.read()
            self.send_response(r.status)
            for k,v in r.getheaders():
                if k.lower() not in {"server","date","connection","transfer-encoding"}:self.send_header(k,v)
            self.end_headers();self.wfile.write(content)
        except (ConnectionResetError,BrokenPipeError):pass
        finally:c.close()
    do_GET=do_POST=relay

def run_server(stack,server):
    threading.Thread(target=server.serve_forever,daemon=True).start()
    stack.callback(server.server_close);stack.callback(server.shutdown)
def entry():
    with ExitStack() as stack:
        temp=Path(stack.enter_context(tempfile.TemporaryDirectory()))
        pc=ThreadingHTTPServer(("127.0.0.1",0),PC); pc.active=None;pc.controls={};pc.runs=[];pc.hits=[];pc.release=threading.Event()
        run_server(stack,pc)
        seeds=[]
        class CheckedCalls(ForgeCalls):
            def post(self,path,payload,timeout,limit):
                if path=="/sdapi/v1/txt2img":
                    seeds.append(payload.get("seed"))
                return super().post(path,payload,timeout,limit)
        q=Queue(temp/"db",CheckedCalls(pc.server_port,pc.server_port));sessions=Sessions(q,ORIGIN)
        server=BrowserServer(port(),ORIGIN,q,src/"ios_static",sessions);q.start();stack.callback(q.close)
        run_server(stack,server)
        proxy=ThreadingHTTPServer(("127.0.0.1",0),LocalTLS)
        proxy.backend=server.server_port;proxy.external="https://127.0.0.1:"+str(proxy.server_port)
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,"Test fixture")])
        cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
              .serial_number(x509.random_serial_number()).not_valid_before(datetime.utcnow()-timedelta(days=1))
              .not_valid_after(datetime.utcnow()+timedelta(days=1)).sign(key,hashes.SHA256()))
        cert_file=temp/"certificate.pem"
        cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM)+key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert_file);proxy.socket=tls.wrap_socket(proxy.socket,server_side=True)
        run_server(stack,proxy)
        pw=stack.enter_context(sync_playwright());browser=pw.webkit.launch();stack.callback(browser.close)
        context=browser.new_context(viewport={"width":390,"height":844},has_touch=True,is_mobile=True,ignore_https_errors=True)
        page=context.new_page(); page.set_default_timeout(7000);errors=[]
        page.on("pageerror",lambda e:errors.append(str(e)))
        url=proxy.external+"/";offer=sessions.issue().split("#")[1]
        page.goto(url+"#"+offer); expect(page.get_by_role("heading",name="画像を生成")).to_be_visible()
        assert page.url==url
        assert page.locator("textarea[name=prompt]").input_value()==profile["prompt"]
        page.locator("textarea[name=prompt]").fill("blue")
        suggestion=page.locator(".fe-tag-grid button").first
        expect(suggestion).to_be_visible();suggestion.click()
        assert page.locator("textarea[name=prompt]").input_value()==r"blue sky \(demo\), "
        nav=page.locator("#navigation")
        nav.get_by_role("button",name="拡張").click()
        page.get_by_text("Museum fixture",exact=True).click()
        page.get_by_role("checkbox",name="次の生成で使う").check()
        nav.get_by_role("button",name="生成",exact=True).click()
        page.locator("textarea[name=prompt]").fill("hold mug")
        page.get_by_label("Seed（ランダムは −1）").fill("9223372036854775806")
        page.get_by_role("button",name="生成キューに追加").click()
        expect(page.get_by_role("heading",name="生成キュー")).to_be_visible()
        nav.get_by_role("button",name="生成",exact=True).click()
        page.locator("textarea[name=prompt]").fill("second mug")
        page.get_by_role("button",name="生成キューに追加").click()
        expect(page.locator(".job-row").filter(has_text="second mug")).to_be_visible()
        page.close();pc.release.set()
        until(lambda:len(pc.runs)==2,10)
        assert seeds==[9223372036854775806,9223372036854775806]
        page=context.new_page();page.on("pageerror",lambda e:errors.append(str(e)));page.goto(url)
        expect(page.get_by_role("heading",name="画像を生成")).to_be_visible()
        nav=page.locator("#navigation");nav.get_by_role("button",name="キュー").click()
        expect(page.locator(".job-row").filter(has_text="完了")).to_have_count(2)
        page.get_by_role("button",name="結果を見る (1)").first.click()
        expect(page.locator("#result-stage img")).to_be_visible()
        page.get_by_role("button",name="全画面",exact=True).click()
        page.keyboard.press("Escape")
        nav.get_by_role("button",name="生成",exact=True).click()
        expect(page.locator("textarea[name=prompt]")).to_have_value("second mug")
        page.locator("#connection").click(); expect(page.get_by_role("heading",name="ホーム画面に追加")).to_be_visible()
        manifest=page.evaluate("async()=>await (await fetch('/manifest.webmanifest')).json()")
        assert manifest["display"]=="standalone" and manifest["start_url"]=="/"
        nav.get_by_role("button",name="生成",exact=True).click()
        page.set_viewport_size({"width":920,"height":600});page.locator("textarea[name=prompt]").fill("blue")
        expect(page.locator("#suggestions-wide .fe-tag-grid button").first).to_be_visible()
        assert page.evaluate("document.documentElement.scrollWidth<=window.innerWidth")
        assert not errors,errors
        print("WebKit full-app pass: QR, persistent session, settings, completion, extension, two queued jobs across page close, results, home icon and wide overlay")
if __name__=="__main__":entry()
