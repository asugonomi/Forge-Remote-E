"""Synthetic, GPU-free tests for owner-bound controls and Forge callback registration."""
import asyncio
import hashlib
import runpy
import sys
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from fastapi import FastAPI, HTTPException
import httpx

BASE = Path(__file__).resolve().parent.parent
PATH = BASE / "forge-remote-job-control/scripts/forge_job_control.py"
API = runpy.run_path(str(PATH), run_name="forge_remote_control_test")
PROOF = "de12a955-972c-4f80-8963-857445d24976"


class JobControlTest(unittest.TestCase):
    def setUp(self):
        self.c = API["Control"]()
        self.scope = API["Scope"](API["task_id"](PROOF), 100.0, 1, 54, 0, False)
        self.log = []
        self.c.sample(self.scope, self.log.append)
        self.ticket = self.handle("poll")

    def current(self):
        return self.scope.task, self.scope.started, self.scope.unit

    def handle(self, action, **extra):
        return self.c.handle({"proof": PROOF, "action": action, **extra}, self.current)

    def command(self):
        return {"unit": self.ticket["unit"], "epoch": self.ticket["epoch"], "request_id": str(uuid.uuid4())}

    def test_digest_vector(self):
        self.assertEqual(self.scope.task, "task(fr-" + hashlib.sha256(
            b"jp.local.forgedesk/v1:de12a955-972c-4f80-8963-857445d24976").hexdigest() + ")")

    def test_only_sampling_applies_once_and_preserves_receipt(self):
        for action in ("skip", "interrupt"):
            with self.subTest(action=action):
                self.setUp()
                req = self.command()
                self.assertEqual(self.handle(action, **req)["status"], "queued")
                self.assertEqual(self.handle("receipt", **req)["status"], "queued")
                self.assertEqual(self.log, [])
                self.c.sample(self.scope, self.log.append)
                self.c.sample(self.scope, self.log.append)
                self.assertEqual(self.log, [action])
                self.c.sample(None, self.log.append)
                self.assertEqual(self.handle("receipt", **req), {"status": "applied", "action": action})

    def test_stale_and_wrong_owner(self):
        req = self.command()
        self.assertFalse(self.handle("poll", proof=str(uuid.uuid4()))["active"])
        for stale in ({"proof":str(uuid.uuid4())}, {"epoch":"0"*32}, {"unit":2}):
            with self.assertRaises(HTTPException) as e:
                self.handle("interrupt", **{**req, **stale})
            self.assertEqual(e.exception.status_code, 409)
        with self.assertRaises(HTTPException):
            self.handle("skip", **{**req, "unit":True})
        self.assertEqual(self.log, [])

    def test_pending_drops_at_every_scope_boundary(self):
        from dataclasses import replace
        for changed in ({"started":101.0}, {"task":"task(PC)"}, {"unit":2}, {"processing":55},
                        {"iteration":1}, {"hires":True}):
            with self.subTest(changed=changed):
                self.setUp()
                req = self.command()
                self.handle("interrupt", **req)
                self.scope = replace(self.scope, **changed)
                self.c.sample(self.scope, self.log.append)
                self.assertEqual(self.log, [])
                self.assertEqual(self.handle("receipt", **req)["status"], "expired")

    def test_racing_commands_only_one_accepted(self):
        reqs = [self.command() for _ in range(24)]
        def send(req):
            try:
                return self.handle("skip", **req)["status"]
            except HTTPException as exc:
                return exc.status_code
        with ThreadPoolExecutor(8) as executor:
            results = list(executor.map(send, reqs))
        self.assertEqual(results.count("queued"), 1)
        self.assertEqual(results.count(409), 23)
        self.c.sample(self.scope, self.log.append)
        self.assertEqual(self.log, ["skip"])

    def test_actual_route_and_registered_callback(self):
        callbacks = {}
        class Callbacks:
            def on_cfg_denoiser(self, fn, **kwargs): callbacks["sample"] = fn
            def on_app_started(self, fn, **kwargs): callbacks["start"] = fn
        action_log = []
        state = SimpleNamespace(time_start=1.0, job_no=0, skip=lambda: action_log.append("skip"),
                                interrupt=lambda: action_log.append("interrupt"))
        progress = SimpleNamespace(current_task=API["task_id"](PROOF))
        modules = SimpleNamespace(script_callbacks=Callbacks(), shared=SimpleNamespace(state=state), progress=progress)
        with patch.dict(sys.modules, {"modules":modules}):
            API["register"]()
        app = FastAPI()
        callbacks["start"](None, app)
        p = SimpleNamespace(iteration=0, is_hr_pass=False)
        params = SimpleNamespace(denoiser=SimpleNamespace(p=p))
        callbacks["sample"](params)
        async def verify():
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 12345)),
                                         base_url="http://127.0.0.1") as client:
                route = API["INTERNAL_ROUTE"]
                payload = {"proof": PROOF, "action": "poll"}
                headers = {"X-Forge-Remote-Internal": "1"}
                self.assertEqual((await client.post(route, json=payload)).status_code, 403)
                for bad in ({"Origin":"http://other.test"}, {"Host":"other.test"}, {"X-Forwarded-For":"10.0.0.2"}):
                    self.assertEqual((await client.post(route, json=payload, headers={**headers, **bad})).status_code, 403)
                result = (await client.post(route, json=payload, headers=headers)).json()
                req = {**payload, "action":"skip", "epoch":result["epoch"], "unit":result["unit"], "request_id":str(uuid.uuid4())}
                self.assertEqual((await client.post(route, json=req, headers=headers)).json()["status"], "queued")
                self.assertEqual(action_log, [])
                callbacks["sample"](params)
                self.assertEqual(action_log, ["skip"])
                progress.current_task = "task(other)"
                self.assertFalse((await client.post(route, json=payload, headers=headers)).json()["active"])
                req["action"] = "receipt"
                self.assertEqual((await client.post(route, json=req, headers=headers)).json()["status"], "applied")
                self.assertEqual((await client.post(route, json={"body":"x"*5000}, headers=headers)).status_code, 413)
        asyncio.run(verify())

    def test_companion_forwards_only_to_local_fixed_route(self):
        bridge = runpy.run_path(str(BASE/"forge_remote_api.py"), run_name="remote_bridge_test")
        upstream = MagicMock()
        session = upstream.return_value.__enter__.return_value
        reply = session.post.return_value
        app = FastAPI()
        bridge["install"](app, BASE, lambda:{}, "http://127.0.0.1:7860")
        async def verify():
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 12345)),
                                         base_url="http://127.0.0.1") as client:
                route = "/forge-remote/v1/job-control"
                payload = {"action":"poll","proof":PROOF}
                self.assertEqual((await client.post(route, json=payload, headers={"Origin":"https://other.test"})).status_code,403)
                self.assertEqual((await client.post(route, json={"action":"other"})).status_code,422)
                self.assertEqual((await client.post(route, json={"body":"a"*5000})).status_code,413)
                session.post.assert_not_called()
                reply.status_code = 404
                self.assertEqual((await client.post(route, json=payload)).json(), {"available":False,"active":False})
                reply.status_code = 409
                self.assertEqual((await client.post(route, json={**payload,"action":"skip"})).status_code,409)
                reply.status_code = 200
                reply.json.return_value = {"available":True,"active":False}
                self.assertEqual((await client.post(route, json=payload)).json(), reply.json.return_value)
                session.post.assert_called_with("http://127.0.0.1:7860"+API["INTERNAL_ROUTE"], json=payload,
                                                headers={"X-Forge-Remote-Internal":"1"},timeout=(2,4),allow_redirects=False)
                self.assertFalse(session.trust_env)
        with patch.object(bridge["requests"], "Session", upstream):
            asyncio.run(verify())


if __name__ == "__main__":
    unittest.main()
