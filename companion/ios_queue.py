"""Private, durable browser-device queues and scoped control of Forge sampling jobs."""
import base64
from contextlib import contextmanager
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import sqlite3
import threading
import time
import uuid

from gateway_http import COMPANION, MIB, POST_LIMIT, SD, RequestError, safe_image


def uuid4(value):
    try:
        parsed = uuid.UUID(value)
        return parsed.version == 4 and str(parsed) == value
    except (ValueError, TypeError, AttributeError):
        return False


def task_id(proof):
    digest = hashlib.sha256(("jp.local.forgedesk/v1:" + proof).encode("ascii")).hexdigest()
    return "task(fr-" + digest + ")"


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"), sort_keys=True).encode()


def check_generation(mode, payload):
    if mode not in ("txt2img", "img2img") or not isinstance(payload, dict):
        raise RequestError(422)
    if (any(key.startswith("outpath_") for key in payload)
            or any(key in payload for key in ("override_settings", "force_task_id"))
            or payload.get("override_settings_restore_afterwards", True) is not True):
        raise RequestError(422)
    for name, maximum in (("width",4096),("height",4096),("batch_size",8),("n_iter",100),("steps",500)):
        if name in payload and (type(payload[name]) is not int or not 1 <= payload[name] <= maximum):
            raise RequestError(422)
    if mode == "img2img":
        images = payload.get("init_images")
        if not isinstance(images,list) or not 1<=len(images)<=8 or not all(safe_image(v) for v in images):
            raise RequestError(422)
        if payload.get("mask") is not None and not safe_image(payload["mask"]):
            raise RequestError(422)
    elif "init_images" in payload or "mask" in payload:
        raise RequestError(422)


class ForgeCalls:
    def __init__(self, forge_port, companion_port):
        self.ports = {"forge":forge_port,"companion":companion_port}

    def post(self, path, payload, timeout, limit):
        port = self.ports["companion" if path.startswith(COMPANION) else "forge"]
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        try:
            conn.request("POST",path,body=json_bytes(payload),headers={"Content-Type":"application/json"})
            response=conn.getresponse()
            if response.status!=200:
                raise RequestError(response.status if 400<=response.status<600 else 502)
            if response.length is not None and response.length>limit:
                raise RequestError(502)
            raw=response.read(limit+1)
            if len(raw)>limit:
                raise RequestError(502)
            value=json.loads(raw)
            if not isinstance(value,dict):
                raise RequestError(502)
            return value
        finally:
            conn.close()


def decode_image(value, limit):
    if not isinstance(value,str) or len(value)>(limit+2)//3*4+128:
        raise ValueError("invalid encoded image")
    if value.startswith("data:image/"):
        head,value=value.split(",",1)
        if not head.endswith(";base64"):
            raise ValueError("invalid encoded image")
    raw=base64.b64decode(value,validate=True)
    if not raw or len(raw)>limit:
        raise ValueError("invalid image length")
    if raw.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png",raw
    if raw.startswith(b"\xff\xd8\xff"):
        return "image/jpeg",raw
    if raw[:4]==b"RIFF" and raw[8:12]==b"WEBP":
        return "image/webp",raw
    if raw.startswith((b"GIF87a",b"GIF89a")):
        return "image/gif",raw
    raise ValueError("unsupported image")


class Queue:
    def __init__(self, directory, calls):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.path = self.directory/"browser.sqlite3"
        os.close(os.open(self.path,os.O_CREAT|os.O_WRONLY,0o600))
        self.calls=calls
        self.halt=threading.Event()
        self.wake=threading.Event()
        self.thread=None
        self.lockfile=None
        self.live={}
        self.guard=threading.Lock()
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS prefs(owner TEXT PRIMARY KEY, paused INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS jobs (
                    seq INTEGER PRIMARY KEY, owner TEXT NOT NULL, id TEXT NOT NULL, mode TEXT NOT NULL,
                    position INTEGER NOT NULL, state TEXT NOT NULL, created REAL NOT NULL,
                    proof TEXT NOT NULL, hash TEXT NOT NULL, payload BLOB, prompt TEXT NOT NULL,
                    count INTEGER NOT NULL DEFAULT 0, info TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
                    ack TEXT NOT NULL DEFAULT '', UNIQUE(owner,id));
                CREATE INDEX IF NOT EXISTS job_order ON jobs(state, position, seq);
                CREATE TABLE IF NOT EXISTS pictures (
                    seq INTEGER NOT NULL, idx INTEGER NOT NULL, mime TEXT NOT NULL, data BLOB NOT NULL,
                    PRIMARY KEY(seq,idx));
                CREATE TABLE IF NOT EXISTS controls (
                    seq INTEGER NOT NULL, epoch TEXT NOT NULL, unit INTEGER NOT NULL, request_id TEXT NOT NULL,
                    action TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(seq,epoch,unit));
            """)

    @contextmanager
    def db(self, write=False):
        db=sqlite3.connect(self.path,timeout=12)
        db.row_factory=sqlite3.Row
        try:
            if write:
                db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        finally:
            db.close()

    def start(self):
        lock = open(self.directory/"worker.lock","a+b")
        try:
            lock.seek(0)
            if os.name=="nt":
                import msvcrt
                msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except Exception:
            lock.close()
            raise RuntimeError("A queue worker already uses this state directory.")
        self.lockfile=lock
        with self.db(True) as db:
            db.execute("UPDATE jobs SET state='unknown',error='PC再起動前の送信結果を確認できません' WHERE state='running'")
            db.execute("UPDATE controls SET status='unconfirmed' WHERE status='queued'")
        self.thread=threading.Thread(target=self._work,daemon=True)
        self.thread.start()

    def close(self):
        self.halt.set()
        self.wake.set()
        if self.thread:
            self.thread.join(2)
        if self.lockfile and (not self.thread or not self.thread.is_alive()):
            self.lockfile.close()

    def enqueue(self, owner, job, mode, payload):
        if not uuid4(job):
            raise RequestError(422)
        check_generation(mode,payload)
        encoded=json_bytes(payload)
        if len(encoded)>POST_LIMIT[SD+mode]:
            raise RequestError(413)
        digest=hashlib.sha256(mode.encode()+b"\0"+encoded).hexdigest()
        with self.db(True) as db:
            prior=db.execute("SELECT hash,state FROM jobs WHERE owner=? AND id=?",(owner,job)).fetchone()
            if prior:
                if prior["hash"]!=digest or prior["state"]=="deleted":
                    raise RequestError(409)
                return {"id":job,"state":prior["state"]}
            pending=db.execute("SELECT count(*) FROM jobs WHERE owner=? AND state='queued'",(owner,)).fetchone()[0]
            if pending>=100:
                raise RequestError(429)
            position=db.execute("SELECT coalesce(max(position),0)+1 FROM jobs").fetchone()[0]
            db.execute("""INSERT INTO jobs(owner,id,mode,position,state,created,proof,hash,payload,prompt)
                VALUES(?,?,?,?,'queued',?,?,?,?,?)""",
                (owner,job,mode,position,time.time(),str(uuid.uuid4()),digest,encoded,str(payload.get("prompt",""))[:200]))
        self.wake.set()
        return {"id":job,"state":"queued"}

    def snapshot(self, owner):
        with self.db() as db:
            paused=db.execute("SELECT paused FROM prefs WHERE owner=?",(owner,)).fetchone()
            rows=list(db.execute("""SELECT id,seq,mode,state,created,count AS result_count,prompt,error FROM jobs
                WHERE owner=? AND state!='deleted' ORDER BY CASE state WHEN 'running' THEN 0 WHEN 'queued' THEN 1 ELSE 2 END,
                CASE WHEN state='queued' THEN position ELSE -seq END""",(owner,)))
        view=[]
        with self.guard:
            for row in rows:
                item=dict(row)
                live=self.live.get(item.pop("seq"),{})
                item.update(progress=live.get("progress",1 if item["state"] in ("done","done_with_skip") else 0),
                            control=live.get("control"),preview_id=live.get("frame",-1),
                            preview_version=live.get("version",0),eta=live.get("eta"))
                view.append(item)
        return {"paused":bool(paused and paused[0]),"jobs":view}

    def pause(self, owner, action):
        if action not in ("pause","resume"):
            raise RequestError(422)
        with self.db(True) as db:
            db.execute("REPLACE INTO prefs VALUES(?,?)",(owner,int(action=="pause")))
        self.wake.set()

    def owned(self, db, owner, job):
        row=db.execute("SELECT seq,state,proof,info FROM jobs WHERE owner=? AND id=? AND state!='deleted'",(owner,job)).fetchone()
        if not row:
            raise RequestError(404)
        return row

    def change(self, owner, job, form):
        action=form.get("action")
        if action in ("skip","interrupt"):
            return self._control(owner,job,form)
        with self.db(True) as db:
            row=self.owned(db,owner,job)
            if action=="delete":
                if row["state"]=="running":
                    raise RequestError(409)
                db.execute("DELETE FROM pictures WHERE seq=?",(row["seq"],))
                db.execute("UPDATE jobs SET state='deleted',payload=NULL,info='',prompt='',error='' WHERE seq=?",(row["seq"],))
            elif action in ("up","down","move"):
                if row["state"]!="queued":
                    raise RequestError(409)
                ordered=db.execute("SELECT id,position FROM jobs WHERE owner=? AND state='queued' ORDER BY position",(owner,)).fetchall()
                ids=[r["id"] for r in ordered]
                index=ids.index(job)
                if action=="move":
                    before=form.get("before")
                    if "before" not in form or (before is not None and before not in ids):
                        raise RequestError(409)
                    if before!=job:
                        ids.remove(job)
                        ids.insert(len(ids) if before is None else ids.index(before),job)
                else:
                    target=max(0,min(len(ids)-1,index+(1 if action=="down" else -1)))
                    ids[index],ids[target]=ids[target],ids[index]
                db.executemany("UPDATE jobs SET position=? WHERE owner=? AND id=?",
                               [(row["position"],owner,id_) for id_,row in zip(ids,ordered)])
            else:
                raise RequestError(422)
        self.wake.set()
        return {"ok":True}

    def get_data(self,owner,job,part,index=0):
        with self.db() as db:
            row=self.owned(db,owner,job)
            if part=="info":
                return row["info"]
            if part=="preview":
                with self.guard:
                    return self.live.get(row["seq"],{}).get("image")
            pic=db.execute("SELECT mime,data FROM pictures WHERE seq=? AND idx=?",(row["seq"],index)).fetchone()
            if not pic:
                raise RequestError(404)
            return tuple(pic)

    def _control(self,owner,job,form):
        if (not uuid4(form.get("request_id")) or not isinstance(form.get("epoch"),str)
                or type(form.get("unit")) is not int):
            raise RequestError(422)
        action=form["action"]
        body={k:form[k] for k in ("action","epoch","unit","request_id")}
        with self.db(True) as db:
            row=self.owned(db,owner,job)
            key=(row["seq"],body["epoch"],body["unit"])
            old=db.execute("SELECT request_id,action,status FROM controls WHERE seq=? AND epoch=? AND unit=?",key).fetchone()
            if old:
                if (old["request_id"],old["action"])!=(body["request_id"],action):
                    raise RequestError(409)
                return {"status":old["status"],"action":action}
            with self.guard:
                ticket=self.live.get(row["seq"],{}).get("control")
            if row["state"]!="running" or not ticket or body["epoch"]!=ticket["epoch"] or body["unit"]!=ticket["unit"]:
                raise RequestError(409)
            db.execute("INSERT INTO controls VALUES(?,?,?,?,?,'queued')",(*key,body["request_id"],action))
        body["proof"]=row["proof"]
        route=COMPANION+"job-control"
        try:
            receipt=self.calls.post(route,body,5,8192)
        except RequestError as exc:
            if exc.status in (409,422):
                self._save_receipt(key,action,"expired")
                raise
            receipt={}
        except (OSError,http.client.HTTPException):
            receipt={}
        if receipt.get("status")=="applied" and receipt.get("action")==action:
            self._save_receipt(key,action,"applied")
            return {"status":"applied","action":action}
        threading.Thread(target=self._await_receipt,args=(key,action,dict(body,action="receipt")),daemon=True).start()
        return {"status":"queued","action":action}

    def _save_receipt(self,key,action,status):
        with self.db(True) as db:
            db.execute("UPDATE controls SET status=? WHERE seq=? AND epoch=? AND unit=?",(status,*key))
            if status=="applied":
                db.execute("UPDATE jobs SET ack=CASE WHEN ack='interrupt' THEN ack ELSE ? END WHERE seq=?",(action,key[0]))
                self._terminal_ack(db,key[0])

    def _await_receipt(self,key,action,body):
        deadline=time.monotonic()+180
        while not self.halt.wait(.5) and time.monotonic()<deadline:
            try:
                receipt=self.calls.post(COMPANION+"job-control",body,5,8192)
            except (OSError,ValueError,http.client.HTTPException,RequestError):
                continue
            if receipt.get("status")=="applied" and receipt.get("action")==action:
                self._save_receipt(key,action,"applied")
                return
            if receipt.get("status")=="expired":
                self._save_receipt(key,action,"expired")
                return
        self._save_receipt(key,action,"unconfirmed")

    @staticmethod
    def _terminal_ack(db,seq):
        db.execute("""UPDATE jobs SET state=CASE WHEN ack='interrupt' THEN 'interrupted'
            WHEN ack='skip' AND count>0 THEN 'done_with_skip' WHEN ack='skip' THEN 'skipped' ELSE state END
            WHERE seq=? AND state IN ('empty','done','skipped','done_with_skip','interrupted')""",(seq,))

    def _claim(self):
        with self.db(True) as db:
            row=db.execute("""SELECT seq,proof,mode,payload FROM jobs LEFT JOIN prefs USING(owner)
                WHERE state='queued' AND coalesce(paused,0)=0 ORDER BY position,seq LIMIT 1""").fetchone()
            if row:
                db.execute("UPDATE jobs SET state='running' WHERE seq=?",(row["seq"],))
            return row

    def _work(self):
        while not self.halt.is_set():
            self.wake.clear()
            row=self._claim()
            if row is None:
                self.wake.wait(3)
                continue
            seq=row["seq"]
            payload=json.loads(row["payload"])
            payload.update(force_task_id=task_id(row["proof"]),send_images=True)
            payload.setdefault("save_images",True)
            stopped=threading.Event()
            monitor=threading.Thread(target=self._monitor,args=(seq,row["proof"],stopped),daemon=True)
            with self.guard:
                self.live[seq]={}
            monitor.start()
            try:
                result=self.calls.post(SD+row["mode"],payload,1200,96*MIB)
                images=result.get("images")
                if not isinstance(images,list):
                    raise ValueError("Invalid result")
                pictures=[decode_image(v,72*MIB) for v in images]
                info=result.get("info","")
                if not isinstance(info,str):
                    info=json.dumps(info,ensure_ascii=False)
                with self.db(True) as db:
                    db.executemany("INSERT INTO pictures VALUES(?,?,?,?)",[(seq,i,mime,pic) for i,(mime,pic) in enumerate(pictures)])
                    db.execute("UPDATE jobs SET count=?,state=?,info=?,payload=NULL WHERE seq=?",
                               (len(pictures),"done" if pictures else "empty",info,seq))
                    self._terminal_ack(db,seq)
            except Exception as error:
                # A broken connection may follow acceptance. Never submit that job again.
                definite=isinstance(error,RequestError) and 400<=error.status<500
                with self.db(True) as db:
                    db.execute("UPDATE jobs SET state=?,error=?,payload=NULL WHERE seq=?",
                               ("failed" if definite else "unknown","Forgeが要求を拒否しました" if definite else "送信後の結果を確認できません",seq))
            finally:
                stopped.set()
                with self.guard:
                    self.live.pop(seq,None)

    def _monitor(self,seq,proof,stopped):
        frame=-1
        while not stopped.is_set():
            try:
                progress=self.calls.post("/internal/progress",{"id_task":task_id(proof),"id_live_preview":frame,
                                        "live_preview":True},5,8*MIB)
                change={"control":None}
                if progress.get("active") is True:
                    value=progress.get("progress")
                    if isinstance(value,(int,float)) and math.isfinite(value):
                        change["progress"]=max(0,min(1,value))
                    eta=progress.get("eta")
                    if isinstance(eta,(int,float)) and math.isfinite(eta):
                        change["eta"]=max(0,eta)
                    new=progress.get("id_live_preview")
                    if type(new) is int and new>=0 and new!=frame and progress.get("live_preview"):
                        change["image"]=decode_image(progress["live_preview"],4*MIB)
                        change["frame"]=frame=new
                    try:
                        t=self.calls.post(COMPANION+"job-control",{"proof":proof,"action":"poll"},5,8192)
                        if t.get("active") is True and isinstance(t.get("epoch"),str) and type(t.get("unit")) is int:
                            change["control"]={k:t.get(k) for k in ("epoch","unit","status")}
                    except Exception:
                        pass
                with self.guard:
                    if seq in self.live:
                        if "image" in change:
                            change["version"]=self.live[seq].get("version",0)+1
                        self.live[seq].update(change)
            except Exception:
                with self.guard:
                    if seq in self.live:
                        self.live[seq]["control"]=None
            stopped.wait(1)
