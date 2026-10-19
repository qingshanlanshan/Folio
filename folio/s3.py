"""A small S3 client (AWS Signature V4) for Cloudflare R2: just what sync needs: put, get, head, list."""
import datetime
import hashlib
import hmac
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"


class S3Error(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def _q(s: str, safe: str = "-_.~") -> str:
    return urllib.parse.quote(s, safe=safe)


class S3:
    def __init__(self, endpoint: str, bucket: str, key_id: str, secret: str, region: str = "auto"):
        self.endpoint = endpoint.rstrip("/")
        self.host = urllib.parse.urlparse(self.endpoint).netloc
        self.bucket, self.key_id, self.secret, self.region = bucket, key_id, secret, region

    def _sign(self, key: bytes, msg: str) -> bytes:
        return hmac.new(key, msg.encode(), hashlib.sha256).digest()

    def request(self, method: str, key: str = "", query: dict | None = None, body: bytes = b"",
                timeout: float = 60) -> tuple[int, bytes]:
        now = datetime.datetime.now(datetime.timezone.utc)
        amz_date, day = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
        path = "/" + _q(self.bucket) + ("/" + _q(key, safe="-_.~/") if key else "")
        qs = "&".join(f"{_q(k)}={_q(str(v))}" for k, v in sorted((query or {}).items()))
        payload = hashlib.sha256(body).hexdigest()
        headers = {"host": self.host, "x-amz-content-sha256": payload, "x-amz-date": amz_date}
        signed = ";".join(sorted(headers))
        canonical = "\n".join([method, path, qs, "".join(f"{k}:{headers[k]}\n" for k in sorted(headers)), signed, payload])
        scope = f"{day}/{self.region}/s3/aws4_request"
        to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical.encode()).hexdigest()])
        k = self._sign(("AWS4" + self.secret).encode(), day)
        for part in (self.region, "s3", "aws4_request"):
            k = self._sign(k, part)
        sig = hmac.new(k, to_sign.encode(), hashlib.sha256).hexdigest()
        headers["authorization"] = f"AWS4-HMAC-SHA256 Credential={self.key_id}/{scope}, SignedHeaders={signed}, Signature={sig}"
        del headers["host"]
        if method == "PUT":
            headers["content-type"] = "application/octet-stream"
        req = urllib.request.Request(self.endpoint + path + ("?" + qs if qs else ""), data=body if method == "PUT" else None,
                                     method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            data = e.read()
            if method == "HEAD" and e.code == 404 or method == "GET" and e.code == 404 and b"NoSuchKey" in data:
                return 404, b""
            msg = data.decode("utf-8", "replace")
            try:
                msg = ET.fromstring(data).findtext("Message") or msg
            except ET.ParseError:
                pass
            raise S3Error(e.code, msg[:300] or f"HTTP {e.code}") from None

    def put(self, key: str, body: bytes) -> None:
        self.request("PUT", key, body=body, timeout=300)

    def get(self, key: str) -> bytes | None:
        status, body = self.request("GET", key, timeout=300)
        return None if status == 404 else body

    def exists(self, key: str) -> bool:
        return self.request("HEAD", key)[0] != 404

    def list(self, prefix: str, start_after: str = "", delimiter: str = "") -> tuple[list[str], list[str]]:
        """→ (keys, common prefixes), all pages."""
        keys, prefixes, token = [], [], None
        while True:
            q = {"list-type": "2", "prefix": prefix}
            if start_after:
                q["start-after"] = start_after
            if delimiter:
                q["delimiter"] = delimiter
            if token:
                q["continuation-token"] = token
            root = ET.fromstring(self.request("GET", query=q)[1])
            keys += [c.findtext(f"{NS}Key") for c in root.findall(f"{NS}Contents")]
            prefixes += [c.findtext(f"{NS}Prefix") for c in root.findall(f"{NS}CommonPrefixes")]
            if root.findtext(f"{NS}IsTruncated") != "true":
                return keys, prefixes
            token = root.findtext(f"{NS}NextContinuationToken")
