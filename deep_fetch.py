#!/usr/bin/env python3
"""deep_fetch: dual-engine async web scraper and API client with zero external dependencies.
Features a pure-asyncio HTTP/1.1 wire framing client (chunked transfer, gzip, redirects, custom SSL)
and automatic Chrome DevTools Protocol (CDP port 9222) rendering for dynamic JavaScript SPAs without Playwright.
Use when you need to fetch, stream or scrape dynamic JavaScript pages, SPAs requiring a real browser DOM,
or APIs requiring native async streaming, local caching, proxies, or custom SSL verification.
Usage: deep_fetch.py URL [--dynamic] [--json] [--stream] [--proxy PROXY] [--port 9222] [-k] [--ca-cert PATH]
"""
from __future__ import annotations
import argparse
import asyncio
import base64
import hashlib
import json
import os
import re
import socket
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from dataclasses import dataclass
from email.parser import BytesHeaderParser
from typing import Any, AsyncGenerator


@dataclass
class HTTPResponse:
    status: int
    headers: dict[str, str]
    body: bytes
    url: str
    elapsed: float
    from_cache: bool = False
    cached_at: float = 0.0


class Cache:
    def __init__(self, max_entries: int = 1000, ttl_seconds: int = 300):
        self.max_entries = max_entries
        self.ttl = ttl_seconds
        self._store: dict[str, dict[str, Any]] = {}

    def get(self, key: str) -> HTTPResponse | None:
        data = self._store.get(key)
        if not data or data["expires_at"] < time.time():
            return None
        return HTTPResponse(
            status=data["status"],
            headers=dict(data["headers"]),
            body=data["body"],
            url=data["url"],
            elapsed=data["elapsed"],
            from_cache=True,
            cached_at=time.time(),
        )

    def set(self, key: str, resp: HTTPResponse) -> None:
        while len(self._store) >= self.max_entries:
            oldest = min(self._store, key=lambda k: self._store[k].get("created_at", 0))
            self._store.pop(oldest, None)
        now = time.time()
        self._store[key] = {
            "status": resp.status,
            "headers": dict(resp.headers),
            "body": resp.body,
            "url": resp.url,
            "elapsed": resp.elapsed,
            "created_at": now,
            "expires_at": now + self.ttl,
        }


class AsyncWireClient:
    """Zero-dependency HTTP/1.1 framing client over asyncio streams.
    Handles DNS, TCP, TLS, status line, RFC 7230 headers, chunked decoding, and gzip.
    """

    def __init__(self, timeout: float = 30.0, verify_ssl: bool = True, ca_bundle: str | None = None):
        self.timeout = timeout
        self.ssl_ctx = ssl.create_default_context(cafile=ca_bundle) if ca_bundle else ssl.create_default_context()
        if not verify_ssl:
            self.ssl_ctx.check_hostname = False
            self.ssl_ctx.verify_mode = ssl.CERT_NONE

    async def fetch(self, url: str, accept_json: bool = False, max_redirects: int = 5) -> HTTPResponse:
        current_url = url
        start_time = time.time()

        for _ in range(max_redirects):
            p = urllib.parse.urlsplit(current_url)
            is_ssl = p.scheme == "https"
            port = p.port or (443 if is_ssl else 80)
            host = p.hostname or "localhost"
            path = (p.path or "/") + (f"?{p.query}" if p.query else "")

            accept_hdr = "application/json, text/plain, */*" if accept_json else "*/*"

            async with asyncio.timeout(self.timeout):
                reader, writer = await asyncio.open_connection(
                    host, port, ssl=self.ssl_ctx if is_ssl else None
                )
                try:
                    req = (
                        f"GET {path} HTTP/1.1\r\n"
                        f"Host: {host}\r\n"
                        f"User-Agent: DeepFetch/1.0 (compatible; AgentDeck)\r\n"
                        f"Accept: {accept_hdr}\r\n"
                        f"Accept-Encoding: gzip, deflate\r\n"
                        f"Connection: close\r\n\r\n"
                    )
                    writer.write(req.encode("latin1"))
                    await writer.drain()

                    # Status line
                    status_raw = await reader.readline()
                    if not status_raw:
                        raise ConnectionResetError("Server closed connection without HTTP response")
                    parts = status_raw.decode("latin1", errors="replace").split(" ", 2)
                    status_code = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 200

                    # Headers
                    header_lines = []
                    while True:
                        line = await reader.readline()
                        if line in (b"\r\n", b"\n", b""):
                            break
                        header_lines.append(line)

                    msg = BytesHeaderParser().parsebytes(b"".join(header_lines))
                    headers = {k.lower(): v for k, v in msg.items()}

                    # Redirects
                    if status_code in (301, 302, 303, 307, 308) and "location" in headers:
                        current_url = urllib.parse.urljoin(current_url, headers["location"])
                        continue

                    # Body framing
                    raw_body = await self._read_body(reader, headers)
                    body = self._decompress(raw_body, headers.get("content-encoding", ""))

                    return HTTPResponse(
                        status=status_code,
                        headers=headers,
                        body=body,
                        url=current_url,
                        elapsed=time.time() - start_time,
                    )
                finally:
                    writer.close()
                    await writer.wait_closed()

        raise RuntimeError(f"Too many redirects (stopped at {current_url})")

    async def _read_body(self, reader: asyncio.StreamReader, headers: dict[str, str]) -> bytes:
        if headers.get("transfer-encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size_line = await reader.readline()
                if not size_line:
                    break
                chunk_len = int(size_line.split(b";")[0].strip(), 16)
                if chunk_len == 0:
                    while (await reader.readline()) not in (b"\r\n", b"\n", b""):
                        pass
                    break
                chunk_data = await reader.readexactly(chunk_len)
                chunks.append(chunk_data)
                await reader.readexactly(2)
            return b"".join(chunks)

        if "content-length" in headers:
            length = int(headers["content-length"])
            return await reader.readexactly(length)

        return await reader.read(-1)

    def _decompress(self, data: bytes, encoding: str) -> bytes:
        if not data or not encoding:
            return data
        enc = encoding.lower()
        if "gzip" in enc or "deflate" in enc:
            try:
                return zlib.decompressobj(wbits=31).decompress(data)
            except Exception:
                return data
        return data


class ChromeCDPWrapper:
    """Minimal stdlib Chrome DevTools Protocol client over WebSockets (RFC 6455)."""
    CHROME_BINS = [
        "google-chrome", "chromium", "google-chrome-stable", "chromium-browser", "chrome",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium"
    ]

    def __init__(self, host: str = "127.0.0.1", port: int = 9222, timeout: int = 25, ignore_certs: bool = False):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.ignore_certs = ignore_certs
        self.base_url = f"http://{host}:{port}"
        self._proc: subprocess.Popen | None = None

    def is_alive(self) -> bool:
        try:
            req = urllib.request.Request(f"{self.base_url}/json/version")
            with urllib.request.urlopen(req, timeout=1.5) as r:
                return r.status == 200
        except Exception:
            return False

    def ensure_browser(self) -> None:
        if self.is_alive():
            return
        cmd = None
        for bin_name in self.CHROME_BINS:
            found = os.path.isfile(bin_name) if os.path.isabs(bin_name) else subprocess.run(
                ["which", bin_name], capture_output=True
            ).returncode == 0
            if found:
                cmd = bin_name
                break
        if not cmd:
            raise RuntimeError(
                f"Chrome CDP not responding on {self.base_url} and no Chrome binary found. "
                f"Start Chrome with: google-chrome --remote-debugging-port={self.port} --headless=new"
            )

        args = [cmd, f"--remote-debugging-port={self.port}", "--headless=new", "--disable-gpu", "--no-first-run"]
        if self.ignore_certs:
            args.append("--ignore-certificate-errors")

        self._proc = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(25):
            time.sleep(0.2)
            if self.is_alive():
                return
        raise RuntimeError(f"Spawned {cmd} but CDP failed to answer on {self.base_url}")

    def _ws_handshake(self, ws_url: str) -> socket.socket:
        p = urllib.parse.urlparse(ws_url)
        host, port, path = p.hostname or self.host, p.port or self.port, p.path
        sock = socket.create_connection((host, port), timeout=self.timeout)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(req.encode("ascii"))
        res = b""
        while b"\r\n\r\n" not in res:
            chunk = sock.recv(1024)
            if not chunk:
                break
            res += chunk
        if b"101 " not in res:
            sock.close()
            raise RuntimeError(f"WebSocket handshake failed: {res[:120]}")
        return sock

    def _ws_send(self, sock: socket.socket, payload: dict[str, Any]) -> None:
        data = json.dumps(payload).encode("utf-8")
        ln = len(data)
        hdr = bytearray([0x81])
        mask = os.urandom(4)
        if ln < 126:
            hdr.append(0x80 | ln)
        elif ln < 65536:
            hdr.append(0x80 | 126)
            hdr.extend(struct.pack(">H", ln))
        else:
            hdr.append(0x80 | 127)
            hdr.extend(struct.pack(">Q", ln))
        hdr.extend(mask)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        sock.sendall(hdr + masked)

    def _ws_recv(self, sock: socket.socket) -> dict[str, Any] | None:
        hdr = sock.recv(2)
        if len(hdr) < 2:
            return None
        b1, b2 = hdr[0], hdr[1]
        opcode = b1 & 0x0F
        is_masked = bool(b2 & 0x80)
        plen = b2 & 0x7F
        if plen == 126:
            plen = struct.unpack(">H", sock.recv(2))[0]
        elif plen == 127:
            plen = struct.unpack(">Q", sock.recv(8))[0]
        mask = sock.recv(4) if is_masked else None
        buf = bytearray()
        while len(buf) < plen:
            part = sock.recv(plen - len(buf))
            if not part:
                break
            buf.extend(part)
        if mask:
            buf = bytearray(b ^ mask[i % 4] for i, b in enumerate(buf))
        if opcode == 0x8:
            return None
        try:
            return json.loads(buf.decode("utf-8", errors="replace"))
        except Exception:
            return None

    def fetch(self, url: str) -> HTTPResponse:
        self.ensure_browser()
        start = time.time()
        tab_req = urllib.request.Request(f"{self.base_url}/json/new", method="PUT")
        try:
            with urllib.request.urlopen(tab_req, timeout=5) as r:
                tab = json.loads(r.read())
        except Exception:
            with urllib.request.urlopen(f"{self.base_url}/json/new", timeout=5) as r:
                tab = json.loads(r.read())

        tab_id = tab["id"]
        ws_url = tab.get("webSocketDebuggerUrl")
        if not ws_url:
            raise RuntimeError(f"No websocket debugger URL for tab {tab_id}")

        sock = self._ws_handshake(ws_url)
        html_out = ""
        try:
            self._ws_send(sock, {"id": 1, "method": "Page.enable"})
            self._ws_send(sock, {"id": 2, "method": "Page.navigate", "params": {"url": url}})

            t_end = time.time() + self.timeout
            while time.time() < t_end:
                msg = self._ws_recv(sock)
                if not msg:
                    break
                if msg.get("method") in ("Page.loadEventFired", "Page.frameStoppedLoading"):
                    break
            time.sleep(0.4)

            self._ws_send(
                sock,
                {
                    "id": 3,
                    "method": "Runtime.evaluate",
                    "params": {"expression": "document.documentElement.outerHTML", "returnByValue": True},
                },
            )
            while time.time() < t_end + 5:
                msg = self._ws_recv(sock)
                if not msg:
                    break
                if msg.get("id") == 3:
                    html_out = msg.get("result", {}).get("result", {}).get("value", "")
                    break
        finally:
            sock.close()
            try:
                with urllib.request.urlopen(f"{self.base_url}/json/close/{tab_id}", timeout=3):
                    pass
            except Exception:
                pass

        return HTTPResponse(
            status=200,
            headers={"content-type": "text/html; charset=utf-8"},
            body=html_out.encode("utf-8"),
            url=url,
            elapsed=time.time() - start,
        )

    def close(self) -> None:
        if self._proc:
            try:
                self._proc.terminate()
            except Exception:
                pass
            self._proc = None


class DeepFetchAgent:
    """Unified web researcher combining wire-framing async HTTP and Chrome CDP."""

    def __init__(
        self,
        proxy_url: str | None = None,
        cdp_port: int = 9222,
        verify_ssl: bool = True,
        ca_bundle: str | None = None,
    ):
        self.cache = Cache()
        self.proxy_url = proxy_url
        self.verify_ssl = verify_ssl
        self.ca_bundle = ca_bundle
        self.wire = AsyncWireClient(verify_ssl=verify_ssl, ca_bundle=ca_bundle)
        self.cdp = ChromeCDPWrapper(port=cdp_port, ignore_certs=not verify_ssl)

    async def fetch(self, url: str, force_dynamic: bool = False, accept_json: bool = False) -> HTTPResponse:
        cache_key = hashlib.md5(f"GET:{url}:{force_dynamic}:{accept_json}".encode()).hexdigest()
        cached = self.cache.get(cache_key)
        if cached:
            return cached

        # Explicit dynamic rendering requested
        if force_dynamic:
            try:
                res = await asyncio.to_thread(self.cdp.fetch, url)
                self.cache.set(cache_key, res)
                return res
            except Exception:
                pass  # Fall back to wire client if CDP is down or unconfigured

        # Default wire client path (or proxy fallback)
        if self.proxy_url:
            res = await self._fetch_via_proxy(url)
        else:
            res = await self.wire.fetch(url, accept_json=accept_json)

        self.cache.set(cache_key, res)
        return res

    async def _fetch_via_proxy(self, url: str) -> HTTPResponse:
        """urllib fallback when proxy routing is required."""
        def _sync_proxy():
            start = time.time()
            handlers: list[urllib.request.BaseHandler] = [
                urllib.request.ProxyHandler({"http": self.proxy_url, "https": self.proxy_url})
            ]
            ssl_ctx = ssl.create_default_context(cafile=self.ca_bundle) if self.ca_bundle else ssl.create_default_context()
            if not self.verify_ssl:
                ssl_ctx.check_hostname = False
                ssl_ctx.verify_mode = ssl.CERT_NONE
            handlers.append(urllib.request.HTTPSHandler(context=ssl_ctx))
            opener = urllib.request.build_opener(*handlers)
            req = urllib.request.Request(url, headers={"User-Agent": "DeepFetch/1.0"})
            with opener.open(req, timeout=30) as r:
                return HTTPResponse(
                    status=r.status,
                    headers={k.lower(): v for k, v in r.headers.items()},
                    body=r.read(),
                    url=url,
                    elapsed=time.time() - start,
                )
        return await asyncio.to_thread(_sync_proxy)

    async def fetch_json(self, url: str, force_dynamic: bool = False) -> dict[str, Any]:
        # By default, APIs should be fetched via the fast wire client with application/json headers
        resp = await self.fetch(url, force_dynamic=force_dynamic, accept_json=True)
        text = resp.body.decode("utf-8", errors="replace").strip()

        # If a browser rendered HTML around JSON, strip tags/pre
        if text.startswith("<"):
            match = re.search(r"<(?:pre|code)[^>]*>(.*?)</(?:pre|code)>", text, re.S)
            if match:
                text = match.group(1).strip()
            else:
                text = re.sub(r"<[^>]+>", "", text).strip()

        try:
            return json.loads(text)
        except json.JSONDecodeError as err:
            ct = resp.headers.get("content-type", "unknown")
            snippet = (text[:160] + "…") if len(text) > 160 else text
            raise ValueError(
                f"Endpoint did not return valid JSON (HTTP {resp.status}, Content-Type: {ct}): {snippet}"
            ) from err

    async def fetch_stream(self, url: str, chunk_size: int = 8192) -> AsyncGenerator[bytes, None]:
        resp = await self.fetch(url)
        for i in range(0, len(resp.body), chunk_size):
            yield resp.body[i : i + chunk_size]
            await asyncio.sleep(0)

    async def close(self) -> None:
        self.cdp.close()


def main():
    ap = argparse.ArgumentParser(prog="deep_fetch")
    ap.add_argument("url", nargs="?", default="")
    ap.add_argument("--dynamic", action="store_true", help="force Chrome CDP DOM rendering")
    ap.add_argument("--json", action="store_true", help="parse and format output as JSON")
    ap.add_argument("--proxy", help="optional HTTP proxy URL")
    ap.add_argument("--port", type=int, default=9222, help="Chrome CDP port (default 9222)")
    ap.add_argument("-k", "--insecure", action="store_true", help="disable SSL certificate verification")
    ap.add_argument("--ca-cert", help="path to custom CA certificate bundle")
    a = ap.parse_args()

    if not a.url:
        print("deep_fetch: dual-engine async HTTP & Chrome CDP scraper.")
        print("Usage: deep_fetch.py URL [--dynamic] [--json] [--proxy P] [-k] [--ca-cert PATH]")
        return

    async def _run():
        agent = DeepFetchAgent(
            proxy_url=a.proxy,
            cdp_port=a.port,
            verify_ssl=not a.insecure,
            ca_bundle=a.ca_cert,
        )
        try:
            if a.json:
                try:
                    res = await agent.fetch_json(a.url, force_dynamic=a.dynamic)
                    print(json.dumps(res, indent=2))
                except ValueError as err:
                    print(f"error: {err}", file=sys.stderr)
                    sys.exit(1)
            else:
                resp = await agent.fetch(a.url, force_dynamic=a.dynamic)
                print(f"Status: {resp.status}  Elapsed: {resp.elapsed:.2f}s  FromCache: {resp.from_cache}")
                print(resp.body.decode("utf-8", errors="replace")[:4000])
        finally:
            await agent.close()

    asyncio.run(_run())


if __name__ == "__main__":
    main()