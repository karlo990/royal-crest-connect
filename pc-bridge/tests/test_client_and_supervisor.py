import importlib
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import rca_supervisor as sup


class _Handler(BaseHTTPRequestHandler):
    hits = {"GET": 0, "PATCH": 0}
    auth = []

    def _do(self):
        type(self).hits[self.command] += 1
        type(self).auth.append(self.headers.get("Authorization"))
        n = type(self).hits[self.command]
        code = 503 if (self.command == "PATCH" or n <= 2) else 200
        self.send_response(code)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    do_GET = do_PATCH = _do

    def log_message(self, *a):
        pass


def _serve():
    srv = HTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def test_get_is_retried_on_503_but_patch_is_not(monkeypatch):
    monkeypatch.setenv("RCA_API_KEY", "s3cret")
    import local_config, rca_client
    importlib.reload(local_config)
    importlib.reload(rca_client)
    _Handler.hits.update(GET=0, PATCH=0)
    _Handler.auth.clear()
    srv = _serve()
    base = f"http://127.0.0.1:{srv.server_port}"
    s = rca_client.make_session()
    assert s.get(base + "/x", timeout=10).status_code == 200
    assert _Handler.hits["GET"] == 3                     # 503, 503, 200
    assert s.patch(base + "/claim", timeout=10).status_code == 503
    assert _Handler.hits["PATCH"] == 1                   # a lost claim response is never replayed
    assert set(_Handler.auth) == {"Bearer s3cret"}
    srv.shutdown()
    monkeypatch.delenv("RCA_API_KEY")
    importlib.reload(local_config)
    importlib.reload(rca_client)


def test_sessions_are_per_thread():
    import rca_client
    seen = []
    t = threading.Thread(target=lambda: seen.append(rca_client.get_session()))
    t.start(); t.join()
    assert seen[0] is not rca_client.get_session()
    assert rca_client.get_session() is rca_client.get_session()


def test_supervisor_backoff_is_exponential_and_capped():
    assert [sup.compute_backoff(n) for n in range(8)] == [5, 10, 20, 40, 80, 160, 300, 300]
