#!/usr/bin/env python3
"""opac-chain-certs.py 가 **빠진 중간 인증서만** 묶음에 넣는지 봅니다.

    python3 scripts/test-opac-chain-certs.py

이 기계에 인증 기관과 서버를 지어 세웁니다. 바깥 네트워크는 쓰지 않습니다. 인증서는 `openssl`
명령으로 그때그때 만들므로 그 명령이 없는 기계에서는 건너뜁니다.

    broken   자기 인증서만 보내는 서버(고치려는 바로 그 상태)        → 중간 인증서를 받아 넣어야 합니다
    whole    중간 인증서까지 보내는 서버                              → 아무것도 넣지 않아야 합니다
    rogue    이 기계가 믿지 않는 기관이 발급한 인증서를 내미는 서버     → 받은 것을 넣으면 안 됩니다
    bundle   발급자 인증서 주소가 묶음(.p7c)을 주는 서버               → 그 안에서 골라 넣어야 합니다
    closed   아무도 듣지 않는 포트                                    → 전에 넣은 것을 지우면 안 됩니다

가운데 것이 가장 중요합니다. 도구가 읽는 「발급자 인증서 주소」는 **아직 검증하지 않은 상대가 내민
인증서**에 적힌 것입니다. 파이썬 3.13 부터는 신뢰 저장소에 넣은 중간 인증서에서 사슬을 끝내도 되는
것이 기본이라, 받은 것을 넣고 다시 붙어 보면 무엇이든 붙습니다. 도구가 그 기본을 끄지 않으면
엉뚱한 인증서가 「확인됨」으로 묶음에 들어갑니다(서버는 그것을 믿지 않으므로 해는 없지만, 묶음이
무엇을 확인한 것인지가 무너집니다).
"""
import http.server
import importlib.util
import io
import os
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("cc", os.path.join(HERE, "opac-chain-certs.py"))
cc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cc)
od = cc.od

failures = []


def check(name, got, want):
    ok = got == want
    print(("  통과  " if ok else "  실패  ") + name)
    if not ok:
        print(f"        받은 것: {got!r}\n        바란 것: {want!r}")
        failures.append(name)


def openssl(*args, cwd):
    subprocess.run(["openssl", *args], cwd=cwd, check=True, capture_output=True)


def make_ca(work, name, cn):
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", f"{name}.key", "-out", f"{name}.pem",
            "-days", "30", "-subj", f"/O=wimb test/CN={cn}",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign", cwd=work)


def issue(work, name, cn, ca, ext):
    openssl("req", "-newkey", "rsa:2048", "-nodes", "-keyout", f"{name}.key", "-out", f"{name}.csr",
            "-subj", f"/O=wimb test/CN={cn}", cwd=work)
    with open(os.path.join(work, f"{name}.ext"), "w") as f:
        f.write(ext)
    openssl("x509", "-req", "-in", f"{name}.csr", "-CA", f"{ca}.pem", "-CAkey", f"{ca}.key",
            "-CAcreateserial", "-out", f"{name}.pem", "-days", "30", "-extfile", f"{name}.ext", cwd=work)


CA_EXT = "basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n" \
         "subjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n"


def leaf_ext(aia_url):
    return ("basicConstraints=CA:FALSE\nsubjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n"
            "subjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n"
            + (f"authorityInfoAccess=caIssuers;URI:{aia_url}\n" if aia_url else ""))


class TlsServer:
    """핸드셰이크만 받아 주는 서버. `certs` 는 보낼 인증서 파일들(앞이 자기 것)입니다."""

    def __init__(self, work, key, certs):
        chain = os.path.join(work, f"chain-{key}-{len(certs)}.pem")
        with open(chain, "w") as out:
            for cert in certs:
                out.write(open(os.path.join(work, cert)).read())
        self.ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.ctx.load_cert_chain(chain, os.path.join(work, key))
        self.sock = socket.create_server(("127.0.0.1", 0))
        self.port = self.sock.getsockname()[1]
        self.hits = 0
        threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            self.hits += 1
            try:
                with self.ctx.wrap_socket(conn, server_side=True):
                    pass
            except (OSError, ssl.SSLError):
                pass

    def close(self):
        self.sock.close()


def serve_files(directory):
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **k):
            super().__init__(*a, directory=directory, **k)

        def log_message(self, *a):
            pass
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def held(path):
    """묶음에 든 인증서의 이름들. 실패했을 때 읽을 수 있게 바이트 대신 이름으로 견줍니다."""
    return [cc.cert_info(c)["name"] for c in cc.from_pem(open(path).read())]


MID = ["wimb test intermediate"]


def run(targets, bundle, roots, **kwargs):
    out = io.StringIO()
    code = cc.run([(h, p, 0) for h, p in targets], bundle, roots=roots, delay=0, timeout=5, out=out, **kwargs)
    return code, out.getvalue()


def main():
    if not shutil.which("openssl"):
        print("openssl 명령이 없어 건너뜁니다.")
        return 0
    work = tempfile.mkdtemp(prefix="wimb-chain-")
    aia = serve_files(work)
    base = f"http://127.0.0.1:{aia.server_address[1]}"

    make_ca(work, "root", "wimb test root")
    issue(work, "mid", "wimb test intermediate", "root", CA_EXT)
    openssl("x509", "-in", "mid.pem", "-outform", "DER", "-out", "mid.der", cwd=work)
    issue(work, "leaf", "localhost", "mid", leaf_ext(f"{base}/mid.der"))
    # 이 기계가 믿지 않는 기관. 그 중간 인증서도 주소에서 멀쩡히 받아집니다.
    make_ca(work, "rogueroot", "rogue root")
    issue(work, "roguemid", "rogue intermediate", "rogueroot", CA_EXT)
    openssl("x509", "-in", "roguemid.pem", "-outform", "DER", "-out", "roguemid.der", cwd=work)
    issue(work, "rogueleaf", "localhost", "roguemid", leaf_ext(f"{base}/roguemid.der"))
    # 발급자 인증서 주소가 묶음을 주는 경우. 묶음에는 상관없는 인증서도 함께 듭니다.
    with open(os.path.join(work, "both.pem"), "w") as f:
        f.write(open(os.path.join(work, "roguemid.pem")).read() + open(os.path.join(work, "mid.pem")).read())
    openssl("crl2pkcs7", "-nocrl", "-certfile", "both.pem", "-outform", "DER", "-out", "mid.p7c", cwd=work)
    issue(work, "p7leaf", "localhost", "mid", leaf_ext(f"{base}/mid.p7c"))
    issue(work, "noaia", "localhost", "mid", leaf_ext(None))

    roots = os.path.join(work, "root.pem")
    mid_der = open(os.path.join(work, "mid.der"), "rb").read()
    rogue_der = open(os.path.join(work, "roguemid.der"), "rb").read()

    print("인증서 읽기")
    info = cc.cert_info(open(os.path.join(work, "leaf.pem"), "rb").read() and cc.from_pem(
        open(os.path.join(work, "leaf.pem")).read())[0])
    check("잎에서 발급자 인증서 주소를 읽는다", info["ca_issuers"], [f"{base}/mid.der"])
    check("이름과 발급자 이름을 읽는다", (info["name"], info["issuer_name"]), ("localhost", "wimb test intermediate"))
    check("만료일을 읽는다", info["not_after"].year in (2026, 2027), True)
    check("중간 인증서는 자기 서명이 아니고 루트는 자기 서명이다",
          (cc.self_signed(cc.cert_info(mid_der)),
           cc.self_signed(cc.cert_info(cc.from_pem(open(roots).read())[0]))), (False, True))
    check("묶음(.p7c)에서 인증서 두 장을 꺼낸다",
          sorted(cc.cert_info(c)["name"] for c in cc.certs_in(open(os.path.join(work, "mid.p7c"), "rb").read())),
          ["rogue intermediate", "wimb test intermediate"])
    check("인증서가 아닌 것은 버린다", (cc.certs_in(b"<html>not found</html>"), cc.certs_in(b"")), ([], []))

    print("사슬이 끊긴 서버")
    broken = TlsServer(work, "leaf.key", ["leaf.pem"])
    bundle = os.path.join(work, "bundle.pem")
    code, text = run([("localhost", broken.port)], bundle, roots)
    check("붙는다고 답하고 성공으로 끝난다", (code, cc.REPAIRED in text), (0, True))
    check("묶음에 그 중간 인증서 한 장만 적는다", held(bundle), MID)
    check("묶음에 이름·만료일·쓰는 호스트를 주석으로 적는다",
          all(s in open(bundle).read() for s in
              ("# wimb test intermediate", "#   만료: ", f"#   쓰는 호스트: localhost:{broken.port}")), True)
    check("그 묶음으로 조사 도구가 붙는다 (서버가 읽는 것과 같은 파일 형식)",
          cc.verifies("localhost", broken.port, open(bundle).read(), roots, 5)[0], "ok")
    check("묶음 없이는 여전히 붙지 못한다", cc.verifies("localhost", broken.port, "", roots, 5)[0], "verify")
    check("도서관 서버로는 핸드셰이크만 서너 번 보낸다", broken.hits <= 5, True)

    print("--check 는 쓰지 않는다")
    untouched = os.path.join(work, "untouched.pem")
    code, text = run([("localhost", broken.port)], untouched, roots, check=True)
    check("파일을 만들지 않는다", (code, os.path.exists(untouched)), (0, False))

    print("사슬이 멀쩡한 서버")
    whole = TlsServer(work, "leaf.key", ["leaf.pem", "mid.pem"])
    fresh = os.path.join(work, "fresh.pem")
    code, text = run([("localhost", whole.port)], fresh, roots)
    check("사슬 정상이라고 답하고 호스트 파일에서 빼라고 한다",
          (code, cc.OK_ALREADY in text, "빼세요" in text), (0, True, True))
    check("아무것도 넣지 않는다", held(fresh), [])

    print("믿지 않는 기관의 인증서를 내미는 서버")
    rogue = TlsServer(work, "rogueleaf.key", ["rogueleaf.pem"])
    code, text = run([("localhost", rogue.port)], bundle, roots)
    check("붙지 못한다고 답하고 실패로 끝난다", (code, cc.STILL_BROKEN in text), (1, True))
    check("받은 인증서를 묶음에 넣지 않는다", "rogue intermediate" in held(bundle), False)
    check("전에 넣은 것은 그대로 둔다", held(bundle), MID)
    check("받은 중간 인증서를 재료로 줘도 그것에서 사슬을 끝내지 않는다",
          cc.verifies("localhost", rogue.port, cc.to_pem(rogue_der), roots, 5)[0], "verify")

    print("발급자 인증서 주소가 묶음을 주는 서버")
    p7 = TlsServer(work, "p7leaf.key", ["p7leaf.pem"])
    p7bundle = os.path.join(work, "p7bundle.pem")
    code, text = run([("localhost", p7.port)], p7bundle, roots)
    check("묶음에서 발급자만 골라 넣는다", (code, held(p7bundle)), (0, MID))

    print("발급자 인증서 주소가 없는 서버")
    noaia = TlsServer(work, "noaia.key", ["noaia.pem"])
    code, text = run([("localhost", noaia.port)], os.path.join(work, "noaia-bundle.pem"), roots)
    check("붙지 못한다고 답하고 이유를 말한다", (code, "발급자 인증서 주소가 없습니다" in text), (1, True))

    print("잎 뒤에 엉뚱한 인증서를 붙여 보내는 서버")
    # 남양주: 잎의 발급자가 아닌 다른 중간 인증서를 보냅니다. 괴산: 중간을 건너뛰고 루트를 보냅니다.
    stray = TlsServer(work, "leaf.key", ["leaf.pem", "roguemid.pem"])
    stray_bundle = os.path.join(work, "stray-bundle.pem")
    code, text = run([("localhost", stray.port)], stray_bundle, roots)
    check("상관없는 중간 인증서가 붙어 와도 잎의 발급자를 받아 넣는다", (code, held(stray_bundle)), (0, MID))
    skipped = TlsServer(work, "leaf.key", ["leaf.pem", "root.pem"])
    skipped_bundle = os.path.join(work, "skipped-bundle.pem")
    code, text = run([("localhost", skipped.port)], skipped_bundle, roots)
    check("중간을 건너뛰고 루트를 보내도 잎의 발급자를 받아 넣는다", (code, held(skipped_bundle)), (0, MID))

    print("닿지 않는 호스트")
    closed = socket.create_server(("127.0.0.1", 0))
    closed_port = closed.getsockname()[1]
    closed.close()
    code, text = run([("localhost", closed_port)], bundle, roots)
    check("닿지 못했다고 답하고 실패로 끝난다", (code, cc.UNREACHABLE in text), (1, True))
    check("전에 넣은 인증서를 지우지 않는다", held(bundle), MID)
    check("「이번에는 확인하지 못함」이라고 적는다", "이번에는 확인하지 못함" in open(bundle).read(), True)
    code, text = run([("localhost", closed_port)], bundle, roots, rebuild=True)
    check("--rebuild 는 이번에 쓰이지 않은 것을 뺀다", held(bundle), [])
    check("비면 비었다고 적는다", "아직 비어 있습니다" in open(bundle).read(), True)

    print("호스트 파일과 규칙 표")
    hosts = od.read_chain_hosts()
    check("호스트 파일은 소문자 이름뿐이다 (서버가 뜰 때 같은 것을 봅니다)",
          [h for h in hosts if h != h.lower() or ":" in h or "/" in h], [])
    targets = cc.targets_from_rules(hosts)
    check("호스트마다 규칙 표에서 포트와 도서관 수를 읽는다",
          (sorted({h for h, _, _ in targets}) == sorted(hosts), all(n > 0 for _, _, n in targets)), (True, True))
    check("포트가 붙은 규칙은 그 포트로 본다",
          {h: p for h, p, _ in targets}.get("lib.nonsan.go.kr"), 8443)
    ctx = od.tls_context(f"https://{sorted(hosts)[0]}/search")
    check("조사 도구는 그 호스트에 따로 만든 설정을 쓴다", isinstance(ctx, ssl.SSLContext), True)
    partial = getattr(ssl, "VERIFY_X509_PARTIAL_CHAIN", 0)
    check("그 설정은 중간 인증서에서 사슬을 끝내지 않는다", bool(ctx.verify_flags & partial), False)
    check("다른 호스트는 기본 설정 그대로다", od.tls_context("https://lib.example.kr/search"), None)

    for server in (broken, whole, rogue, p7, noaia):
        server.close()
    aia.shutdown()
    shutil.rmtree(work, ignore_errors=True)

    print()
    if failures:
        print(f"실패 {len(failures)}건: {', '.join(failures)}")
        return 1
    print("전부 통과했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
