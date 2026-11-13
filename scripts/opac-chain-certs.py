#!/usr/bin/env python3
"""중간 인증서를 보내지 않는 도서관 서버의 **빠진 중간 인증서를 모아 묶음을 만듭니다.**

    ./scripts/opac-chain-certs.py            # chain-hosts.txt 의 호스트를 보고 chain-certs.pem 을 고쳐 씁니다
    ./scripts/opac-chain-certs.py --check    # 보기만 하고 쓰지 않습니다
    ./scripts/opac-chain-certs.py --rebuild  # 이번에 확인된 것만 남깁니다(전부 답했을 때만 쓰세요)
    ./scripts/opac-chain-certs.py lib.nyj.go.kr lib.nonsan.go.kr:8443   # 호스트를 직접 줍니다

## 왜 필요한가

남양주·구미·울진을 비롯한 열 개 호스트(도서관 51곳)는 TLS 에서 자기 인증서만 보내고 그것을 발급한
중간 인증서를 보내지 않습니다. 브라우저는 인증서에 적힌 「발급자 인증서 주소」(AIA caIssuers)에서
중간 인증서를 받아 와 사슬을 잇지만, 자바와 파이썬은 그러지 않고 연결을 끊습니다. 그래서 서버도
조사 도구도 그 호스트의 검색 화면을 받지 못했습니다(docs/가정과-검증상태.md 2-32).

서버는 그 호스트로 갈 때만 `backend/src/main/resources/opac/chain-certs.pem` 의 중간 인증서를
보태어 사슬을 잇습니다(kr.wimb.opac.IntermediateCerts). 이 도구가 그 파일을 만듭니다.

## 무엇을 하고 무엇을 하지 않는가

호스트마다 다음을 차례로 합니다. **도서관 서버로 가는 것은 TLS 핸드셰이크뿐이고 화면은 한 장도 받지
않습니다.** 한 호스트에 서너 번이고 사이에 간격을 둡니다.

  1. 아무것도 보태지 않고 붙어 봅니다. 붙으면 사슬이 멀쩡한 것이라 그 호스트는 chain-hosts.txt 에서
     빼야 합니다.
  2. 검증 없이 붙어 서버가 보낸 인증서를 읽고, 거기 적힌 발급자 인증서 주소에서 중간 인증서를 받습니다.
     받은 인증서에도 주소가 있으면 그 위의 것도 받습니다(네 단계까지).
  3. **받은 것을 보태고 검증을 켠 채 다시 붙어 봅니다.** 이때 믿는 것은 이 기계의 공개 루트뿐이고,
     받은 인증서는 사슬을 잇는 재료로만 씁니다. 붙으면 그 인증서를 묶음에 적습니다.

3번이 이 도구의 전부입니다. 2번에서 읽는 주소는 **아직 검증하지 않은 상대가 내민 인증서**에 적힌
것이라, 거기서 받은 것을 그대로 믿으면 안 됩니다. 받은 인증서가 공개 루트까지 이어지지 않으면
3번에서 떨어지고 묶음에 들어가지 않습니다. 루트(자기 서명) 인증서는 받아도 넣지 않습니다.

## 어디서 돌리나

**그 호스트에 닿는 회선에서 돌립니다.** 클라우드의 작업 환경은 도서관 사이트로 나가는 길이 막혀
있고 프록시가 인증서를 바꿔 끼워서 쓸 수 없습니다. 국내 회선의 로컬에서 돌리세요. 중간 인증서는
어디서 받든 같은 공개 인증서이므로, 국내에서 만든 묶음을 미국 리전의 서버가 그대로 씁니다. 서버가
그 호스트에 **닿는지**는 따로입니다. 배포한 뒤 `/api/diagnose/opac/reach?lib=<부호>` 로 봅니다.

## 묶음은 낡습니다

도서관이 인증서를 갱신하면서 발급자가 바뀌면 묶음의 것이 더는 맞지 않습니다. 중간 인증서에도
만료일이 있습니다. 그러면 그 호스트는 오류 없이 예전처럼 검색 결과로 내려갑니다. `/api/status` 의
`opacChainCertsExpire` 가 가까워지거나 진단이 `PKIX path building failed` 를 다시 말하면 이 도구를
다시 돌리세요.

테스트: ./scripts/test-opac-chain-certs.py
"""

from __future__ import annotations

import argparse
import base64
import datetime
import importlib.util
import os
import re
import socket
import ssl
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("od", os.path.join(HERE, "opac-discover.py"))
od = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(od)

TEMPLATES_FILE = os.path.join(os.path.dirname(od.CHAIN_CERTS_FILE), "templates.csv")
MAX_DEPTH = 4                 # 잎 위로 몇 단계까지 받아 볼지. 공개 사슬은 대개 하나나 둘입니다.
MAX_CERT_BYTES = 65536
HOST_DELAY = 1.0              # 같은 호스트에 핸드셰이크를 잇달아 걸지 않습니다.

OK_ALREADY = "사슬 정상"
REPAIRED = "보태면 붙음"
STILL_BROKEN = "보태도 안 붙음"
UNREACHABLE = "닿지 못함"

HEADER = """\
# chain-hosts.txt 의 호스트가 보내지 않는 중간 인증서 묶음.
#
# 손으로 붙이지 마세요. scripts/opac-chain-certs.py 가 씁니다. 그 도구는 호스트의 인증서에 적힌
# 발급자 주소에서 중간 인증서를 받아, 그것을 보태면 사슬이 공개 루트까지 이어지는지 확인한 것만
# 여기에 적습니다. 루트 인증서는 넣지 않습니다(신뢰의 뿌리는 JDK 의 것 그대로입니다).
"""
EMPTY_NOTE = """\
#
# 아직 비어 있습니다. 비어 있는 동안 chain-hosts.txt 의 호스트는 예전처럼 서버가 붙지 못하고
# 검색 결과로 내려갑니다.
"""


# ── DER 읽기 ─────────────────────────────────────────────────────────────────
#
# 표준 라이브러리에는 검증하지 않은 인증서의 내용을 읽는 공개 함수가 없습니다. 필요한 것이 넷뿐이라
# (발급자, 주체, 만료일, 발급자 인증서 주소) 그만큼만 읽습니다.

OID_CA_ISSUERS = bytes.fromhex("06082b06010505073002")      # 1.3.6.1.5.5.7.48.2
OID_COMMON_NAME = bytes.fromhex("0603550403")               # 2.5.4.3
OID_SIGNED_DATA = bytes.fromhex("06092a864886f70d010702")   # PKCS#7 signedData


def tlv(data: bytes, pos: int) -> tuple[int, int, int]:
    """`pos` 에서 시작하는 항목의 (태그, 값의 시작, 값의 끝). 잘렸으면 ValueError 입니다."""
    if pos + 2 > len(data):
        raise ValueError("DER 이 잘렸습니다")
    tag = data[pos]
    first = data[pos + 1]
    start = pos + 2
    if first < 0x80:
        length = first
    else:
        n = first & 0x7F
        if n == 0 or n > 4 or start + n > len(data):
            raise ValueError("DER 길이를 읽을 수 없습니다")
        length = int.from_bytes(data[start:start + n], "big")
        start += n
    if start + length > len(data):
        raise ValueError("DER 이 잘렸습니다")
    return tag, start, start + length


def children(data: bytes, start: int, end: int) -> list[tuple[int, int, int, int]]:
    """[start, end) 안의 항목들을 (태그, 항목의 시작, 값의 시작, 값의 끝)으로."""
    out = []
    pos = start
    while pos < end:
        tag, vstart, vend = tlv(data, pos)
        out.append((tag, pos, vstart, vend))
        pos = vend
    return out


def cert_info(der: bytes) -> dict:
    """인증서에서 발급자·주체(바이트 그대로)·이름·만료일·발급자 인증서 주소를 읽습니다."""
    tag, start, end = tlv(der, 0)
    if tag != 0x30:
        raise ValueError("인증서가 아닙니다")
    tbs = children(der, start, end)[0]
    if tbs[0] != 0x30:
        raise ValueError("인증서가 아닙니다")
    fields = children(der, tbs[2], tbs[3])
    if fields and fields[0][0] == 0xA0:            # 버전. 없으면 v1 입니다.
        fields = fields[1:]
    if len(fields) < 6:
        raise ValueError("인증서가 아닙니다")
    issuer, validity, subject = fields[2], fields[3], fields[4]
    times = children(der, validity[2], validity[3])
    return {
        "issuer": der[issuer[1]:issuer[3]],
        "subject": der[subject[1]:subject[3]],
        "name": common_name(der[subject[1]:subject[3]]),
        "issuer_name": common_name(der[issuer[1]:issuer[3]]),
        "not_after": parse_time(times[1][0], der[times[1][2]:times[1][3]]),
        "ca_issuers": ca_issuer_urls(der),
    }


def parse_time(tag: int, raw: bytes) -> datetime.datetime:
    text = raw.decode("ascii")
    if tag == 0x17:                                # UTCTime: 두 자리 해. 50 미만이면 20xx 입니다.
        year = int(text[:2])
        text = ("20" if year < 50 else "19") + text
    return datetime.datetime.strptime(text[:14], "%Y%m%d%H%M%S").replace(tzinfo=datetime.timezone.utc)


def common_name(name: bytes) -> str:
    at = name.rfind(OID_COMMON_NAME)
    if at < 0:
        return "(이름 없음)"
    try:
        tag, start, end = tlv(name, at + len(OID_COMMON_NAME))
    except ValueError:
        return "(이름 없음)"
    raw = name[start:end]
    return raw.decode("utf-16-be" if tag == 0x1E else "utf-8", errors="replace")


def ca_issuer_urls(der: bytes) -> list[str]:
    """발급자 인증서 주소(AIA caIssuers)들. http(s) 주소만 돌려줍니다."""
    urls = []
    pos = 0
    while True:
        at = der.find(OID_CA_ISSUERS, pos)
        if at < 0:
            return urls
        pos = at + len(OID_CA_ISSUERS)
        try:
            tag, start, end = tlv(der, pos)
        except ValueError:
            continue
        if tag != 0x86:                            # [6] 주소(URI)가 아닌 위치 표기는 받을 수 없습니다.
            continue
        url = der[start:end].decode("ascii", errors="replace")
        if urllib.parse.urlparse(url).scheme in ("http", "https") and url not in urls:
            urls.append(url)


def self_signed(info: dict) -> bool:
    return info["issuer"] == info["subject"]


def to_pem(der: bytes) -> str:
    body = base64.encodebytes(der).decode("ascii")
    return "-----BEGIN CERTIFICATE-----\n" + body + "-----END CERTIFICATE-----\n"


PEM_BLOCK = re.compile(r"-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\s]*?)-----END CERTIFICATE-----")


def from_pem(text: str) -> list[bytes]:
    return [base64.b64decode(m.group(1)) for m in PEM_BLOCK.finditer(text)]


def certs_in(data: bytes) -> list[bytes]:
    """받은 파일에서 인증서들을 꺼냅니다. DER 한 장, PEM, PKCS#7 묶음(.p7c)을 읽습니다."""
    if b"-----BEGIN CERTIFICATE-----" in data:
        return from_pem(data.decode("ascii", errors="replace"))
    try:
        tag, start, end = tlv(data, 0)
        if tag != 0x30:
            return []
        outer = children(data, start, end)
        if outer and data[outer[0][1]:outer[0][3]] == OID_SIGNED_DATA and len(outer) > 1:
            # ContentInfo { signedData, [0] { SignedData { 버전, 알고리즘들, 내용, [0] 인증서들, … } } }
            signed = children(data, outer[1][2], outer[1][3])[0]
            for tag2, pos2, vstart2, vend2 in children(data, signed[2], signed[3]):
                if tag2 == 0xA0:
                    return [data[p:e] for t, p, _, e in children(data, vstart2, vend2) if t == 0x30]
            return []
        cert_info(data[:end])                      # 인증서 한 장인지 읽어 봅니다.
        return [data[:end]]
    except (ValueError, IndexError):
        return []


# ── 네트워크 ─────────────────────────────────────────────────────────────────

def handshake(host: str, port: int, ctx: ssl.SSLContext, timeout: float):
    with socket.create_connection((host, port), timeout=timeout) as raw:
        with ctx.wrap_socket(raw, server_hostname=host) as tls:
            chain = getattr(tls, "get_unverified_chain", None)       # 파이썬 3.13 부터 있습니다.
            sent = [bytes(c) for c in chain()] if chain else []
            return sent or [tls.getpeercert(binary_form=True)]


def verifies(host: str, port: int, pem: str, roots, timeout: float) -> tuple[str, str]:
    """검증을 켠 채 붙어 봅니다. ("ok" | "verify" | "unreachable", 사유)."""
    try:
        handshake(host, port, od.chain_context(pem, roots), timeout)
        return "ok", ""
    except ssl.SSLCertVerificationError as e:
        return "verify", e.verify_message or str(e)
    except (OSError, ssl.SSLError) as e:
        return "unreachable", f"{type(e).__name__}: {e}"


def sent_certs(host: str, port: int, timeout: float) -> list[bytes]:
    """검증 없이 붙어 서버가 보낸 인증서를 읽습니다. 여기서 읽은 것은 아직 아무것도 믿지 않습니다."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return handshake(host, port, ctx, timeout)


def fetch_certs(url: str, timeout: float) -> list[bytes]:
    req = urllib.request.Request(url, headers={"User-Agent": od.UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return certs_in(resp.read(MAX_CERT_BYTES))
    except Exception:
        return []


def examine(host: str, port: int, roots=None, timeout: float = 10.0, delay: float = HOST_DELAY) -> dict:
    """호스트 하나를 봅니다. {"status", "note", "found": [DER, …]}"""
    state, why = verifies(host, port, "", roots, timeout)
    if state == "ok":
        return {"status": OK_ALREADY, "note": "", "found": []}
    if state == "unreachable":
        return {"status": UNREACHABLE, "note": why, "found": []}
    time.sleep(delay)
    try:
        sent = sent_certs(host, port, timeout)
        last = top_of_sent_chain(sent)
    except (OSError, ssl.SSLError, ValueError, IndexError) as e:
        return {"status": UNREACHABLE, "note": f"{type(e).__name__}: {e}", "found": []}
    found: list[bytes] = []
    for _ in range(MAX_DEPTH):
        if not last["ca_issuers"]:
            why = why + " (인증서에 발급자 인증서 주소가 없습니다)" if not found else why
            break
        fetched = []
        for url in last["ca_issuers"]:
            fetched = fetch_certs(url, timeout)
            if fetched:
                break
        if not fetched:
            why = f"발급자 인증서를 받지 못했습니다: {', '.join(last['ca_issuers'])}"
            break
        # 주소가 묶음(.p7c)을 주면 여러 장이 옵니다. 지금 인증서를 발급한 것을 고릅니다.
        issuer = next((c for c in fetched if _info(c) and _info(c)["subject"] == last["issuer"]), None)
        if issuer is None:
            why = "받은 것이 이 인증서의 발급자가 아닙니다"
            break
        info = cert_info(issuer)
        if self_signed(info):                      # 루트입니다. 믿을지는 이 기계의 루트 목록이 정합니다.
            break
        found.append(issuer)
        time.sleep(delay)
        state, why = verifies(host, port, "".join(to_pem(c) for c in found), roots, timeout)
        if state == "ok":
            return {"status": REPAIRED, "note": "", "found": found}
        if state == "unreachable":
            return {"status": UNREACHABLE, "note": why, "found": []}
        last = info
    return {"status": STILL_BROKEN, "note": why, "found": []}


def _info(der: bytes):
    try:
        return cert_info(der)
    except (ValueError, IndexError):
        return None


def top_of_sent_chain(sent: list[bytes]) -> dict:
    """서버가 보낸 것 가운데 **잎에서부터 실제로 이어지는** 사슬의 맨 위 인증서.

    보낸 차례의 마지막 장을 그대로 쓰면 안 됩니다. 사슬이 끊긴 서버는 잎 뒤에 엉뚱한 것을 붙여 보내는
    일이 있습니다(2026-11-13 실측). 남양주는 잎의 발급자가 아닌 다른 중간 인증서를 보내고, 괴산은 중간
    인증서를 건너뛰고 루트를 보냅니다. 마지막 장에서 시작하면 잎의 발급자 주소를 읽지도 않은 채
    「보태도 안 붙음」으로 끝납니다. (파이썬 3.12 까지는 잎 한 장만 읽혀서 드러나지 않습니다.)
    """
    last = cert_info(sent[0])
    rest = [i for i in map(_info, sent[1:]) if i and not self_signed(i)]
    for _ in rest:
        upper = next((i for i in rest if i["subject"] == last["issuer"]), None)
        if upper is None:
            break
        last = upper
    return last


# ── 대상과 묶음 ──────────────────────────────────────────────────────────────

def targets_from_rules(hosts, templates_path: str = TEMPLATES_FILE) -> list[tuple[str, int, int]]:
    """(호스트, 포트, 걸린 도서관 수). 포트는 규칙 표의 주소에서 읽습니다."""
    found: dict[tuple[str, int], int] = {}
    try:
        with open(templates_path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("#") or line.count(",") < 3:
                    continue
                url = line.rstrip("\n").split(",", 3)[3]
                parts = urllib.parse.urlsplit(url)
                host = (parts.hostname or "").lower()
                if parts.scheme == "https" and host in hosts:
                    key = (host, parts.port or 443)
                    found[key] = found.get(key, 0) + 1
    except FileNotFoundError:
        pass
    for host in hosts:
        if not any(h == host for h, _ in found):
            found[(host, 443)] = 0
    return sorted((h, p, n) for (h, p), n in found.items())


def parse_target(text: str) -> tuple[str, int, int]:
    host, _, port = text.partition(":")
    return host.lower(), int(port) if port else 443, 0


def write_bundle(path: str, entries: list[tuple[bytes, list[str]]]) -> None:
    """entries: (인증서, 그 인증서로 붙는 호스트들). 호스트가 비면 「이번에는 확인하지 못함」입니다."""
    out = [HEADER]
    if not entries:
        out.append(EMPTY_NOTE)
    for der, hosts in sorted(entries, key=lambda e: (cert_info(e[0])["name"], e[0])):
        info = cert_info(der)
        used = ", ".join(sorted(hosts)) if hosts else "이번에는 확인하지 못함(전에 넣은 것)"
        out.append(f"\n# {info['name']}\n#   발급자: {info['issuer_name']}\n"
                   f"#   만료: {info['not_after'].date()}\n#   쓰는 호스트: {used}\n")
        out.append(to_pem(der))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(out))


def run(targets, bundle_path, roots=None, check=False, rebuild=False, timeout=10.0,
        delay=HOST_DELAY, out=sys.stdout) -> int:
    try:
        with open(bundle_path, encoding="utf-8") as f:
            existing = from_pem(f.read())
    except FileNotFoundError:
        existing = []
    now = datetime.datetime.now(datetime.timezone.utc)
    users: dict[bytes, list[str]] = {}
    bad = 0
    for host, port, libraries in targets:
        result = examine(host, port, roots, timeout, delay)
        label = host if port == 443 else f"{host}:{port}"
        extra = ""
        if result["status"] == REPAIRED:
            names = [cert_info(c)["name"] for c in result["found"]]
            extra = "  ← " + " ← ".join(names)
            for cert in result["found"]:
                users.setdefault(cert, []).append(label)
        elif result["status"] == OK_ALREADY:
            extra = "  (chain-hosts.txt 에서 빼세요)"
        else:
            bad += 1
            extra = "  " + result["note"]
        count = f" 도서관 {libraries}곳" if libraries else ""
        print(f"{result['status']:<8} {label}{count}{extra}", file=out)

    entries = [(cert, hosts) for cert, hosts in users.items()]
    for cert in existing:
        if cert in users:
            continue
        info = _info(cert)
        if info is None or info["not_after"] <= now:
            print(f"뺌       만료됐거나 읽을 수 없는 인증서: {info['name'] if info else '?'}", file=out)
        elif rebuild:
            print(f"뺌       이번에 쓰이지 않은 인증서: {info['name']}", file=out)
        else:
            entries.append((cert, []))
    if check:
        print(f"\n쓰지 않았습니다(--check). 묶음에 들어갈 인증서 {len(entries)}장.", file=out)
    else:
        write_bundle(bundle_path, entries)
        print(f"\n{os.path.relpath(bundle_path)} 에 인증서 {len(entries)}장을 적었습니다.", file=out)
    if bad:
        print(f"붙지 못한 호스트가 {bad}곳 있습니다. 그 호스트는 서버도 붙지 못합니다.", file=out)
    return 1 if bad else 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="빠진 중간 인증서를 모아 chain-certs.pem 을 만듭니다.")
    parser.add_argument("hosts", nargs="*", help="호스트[:포트]. 없으면 chain-hosts.txt 와 규칙 표에서 읽습니다.")
    parser.add_argument("--check", action="store_true", help="보기만 하고 쓰지 않습니다.")
    parser.add_argument("--rebuild", action="store_true", help="이번에 확인된 인증서만 남깁니다.")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--bundle", default=od.CHAIN_CERTS_FILE, help=argparse.SUPPRESS)
    parser.add_argument("--roots", default=None, help=argparse.SUPPRESS)      # 테스트가 씁니다.
    args = parser.parse_args(argv)
    if args.hosts:
        targets = [parse_target(h) for h in args.hosts]
    else:
        targets = targets_from_rules(od.read_chain_hosts())
    if not targets:
        print("볼 호스트가 없습니다. chain-hosts.txt 가 비어 있습니다.")
        return 0
    return run(targets, args.bundle, roots=args.roots, check=args.check, rebuild=args.rebuild,
               timeout=args.timeout)


if __name__ == "__main__":
    sys.exit(main())
