"""rayl-widget/1 dev provider: repeatable widgets and failure cases for testing Rayl.

Serves definitions, invoice, delivery, validate and status per rayl-widget-spec-v1.md.
Invoices are correctly signed mainnet BOLT11 from a throwaway key: they pass Rayl's checks, but
no wallet can pay them (there is no node behind the key). Prices use the Rayl staging backend's
rate, so price checks behave like they will with real providers.

Runs on Railway staging; see README.md. Locally, Rayl needs a public HTTPS host:

    python provider.py                                   # 127.0.0.1:8787
    PUBLIC_BASE=https://<tunnel> python provider.py      # behind ngrok or similar

Every URL is /<scenario>/<widget>.json; GET / lists them all.
"""

import base64
import hashlib
import json
import os
import secrets
import struct
import time
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ecdsa import SECP256k1, SigningKey, VerifyingKey
from ecdsa.util import sigdecode_string, sigencode_string_canonize

SPEC = "rayl-widget/1"
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8787"))
PUBLIC_BASE = os.environ.get("PUBLIC_BASE", f"http://127.0.0.1:{PORT}").rstrip("/")
PRICES_URL = os.environ.get("PRICES_URL", "https://rayl-staging.up.railway.app/v1/bitcoin/prices")
NODE_KEY = SigningKey.from_secret_exponent(
    int.from_bytes(hashlib.sha256(b"rayl-dev-provider").digest(), "big") % SECP256k1.order,
    curve=SECP256k1,
    hashfunc=hashlib.sha256,
)
ORDERS = {}

# Scenario -> what it does. The title of every non-"ok" widget says it, so tests read clearly.
SCENARIOS = {
    "ok": "",
    "down": "invoice answers 503",
    "slow": "invoice takes 25 s",
    "mismatch": "invoice 4x the price",
    "higher": "invoice 5% over",
    "lower": "invoice 5% under",
    "refuse": "rejects the input",
    "soldout": "products sold out",
    "nothingdue": "nothing due",
    "undelivered": "paid, not delivered",
}

# ---------------------------------------------------------------------------- BOLT11

CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def _polymod(values):
    gen = [0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3]
    chk = 1
    for v in values:
        top = chk >> 25
        chk = (chk & 0x1FFFFFF) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if ((top >> i) & 1) else 0
    return chk


def _hrp_expand(hrp):
    return [ord(x) >> 5 for x in hrp] + [0] + [ord(x) & 31 for x in hrp]


def _checksum(hrp, data):
    polymod = _polymod(_hrp_expand(hrp) + data + [0] * 6) ^ 1
    return [(polymod >> 5 * (5 - i)) & 31 for i in range(6)]


def _convertbits(data, frombits, tobits, pad=True):
    acc, bits, ret, maxv = 0, 0, [], (1 << tobits) - 1
    for value in data:
        acc = (acc << frombits) | value
        bits += frombits
        while bits >= tobits:
            bits -= tobits
            ret.append((acc >> bits) & maxv)
    if pad and bits:
        ret.append((acc << (tobits - bits)) & maxv)
    return ret


def _int_words(value, length=None):
    words = []
    while value:
        words.insert(0, value & 31)
        value >>= 5
    words = words or [0]
    if length:
        words = [0] * (length - len(words)) + words
    return words


def _tag(char, words):
    return [CHARSET.index(char)] + _int_words(len(words), 2) + words


def make_invoice(amount_sat, description, expiry=600):
    preimage = secrets.token_bytes(32)
    payment_hash = hashlib.sha256(preimage).digest()
    hrp = f"lnbc{amount_sat * 10}n"
    data = _int_words(int(time.time()), 7)
    data += _tag("p", _convertbits(payment_hash, 8, 5))
    data += _tag("s", _convertbits(secrets.token_bytes(32), 8, 5))
    data += _tag("d", _convertbits(description.encode()[:600], 8, 5))
    data += _tag("x", _int_words(expiry))
    data += _tag("c", _int_words(18))
    data += _tag("9", _int_words((1 << 8) | (1 << 14)))
    message = hrp.encode() + bytes(_convertbits(data, 5, 8))
    digest = hashlib.sha256(message).digest()
    signature = NODE_KEY.sign_digest_deterministic(
        digest, sigencode=sigencode_string_canonize, hashfunc=hashlib.sha256
    )
    candidates = VerifyingKey.from_public_key_recovery_with_digest(
        signature, digest, SECP256k1, hashfunc=hashlib.sha256, sigdecode=sigdecode_string
    )
    recid = next(
        i for i, key in enumerate(candidates)
        if key.to_string() == NODE_KEY.get_verifying_key().to_string()
    )
    data += _convertbits(signature + bytes([recid]), 8, 5)
    return hrp + "1" + "".join(CHARSET[d] for d in data + _checksum(hrp, data))


# ---------------------------------------------------------------------------- pricing

_rate = {"value": None, "at": 0.0, "history": []}


def usd_rate():
    """Bitcoin in USD from the Rayl staging backend, the rate the app checks invoices with."""
    if _rate["value"] and time.time() - _rate["at"] < 60:
        return _rate["value"]
    try:
        with urllib.request.urlopen(PRICES_URL, timeout=5) as response:
            _rate["value"] = float(json.load(response)["prices"]["USD"])
            _rate["at"] = time.time()
            _rate["history"] = (_rate["history"] + [_rate["value"]])[-48:]
    except Exception as failure:
        print(f"price fetch failed: {failure}")
    return _rate["value"]


def usd_to_sats(amount):
    rate = usd_rate()
    return None if rate is None else round(amount / rate * 100_000_000)


def money(amount):
    return {"amount": f"{amount:.2f}", "currency": "USD"}


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


# ---------------------------------------------------------------------------- icons


def png(color, size=96):
    """A flat square PNG as a data URL, well under the spec's 32 KiB."""
    r, g, b = color
    raw = b"".join(b"\x00" + bytes([r, g, b]) * size for _ in range(size))

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    image = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
             + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
    return "data:image/png;base64," + base64.b64encode(image).decode()


# ---------------------------------------------------------------------------- definitions

PHONE = {"id": "phone", "type": "phone", "label": "Phone number", "country": "SV",
         "placeholder": "7XXX XXXX"}
PACKAGES = [
    ("inet-1d", "3 GB · 1 day", 1.00, "1 day"),
    ("inet-7d", "12 GB · 7 days", 5.00, "7 days"),
    ("inet-30d", "40 GB · 30 days", 15.00, "30 days"),
    ("calls-7d", "100 minutes · 7 days", 3.00, "7 days"),
]


def definition(name, scenario):
    if scenario not in SCENARIOS:
        return None
    base = f"{PUBLIC_BASE}/{scenario}"
    suffix = f" · {SCENARIOS[scenario]}" if SCENARIOS[scenario] else ""
    common = {
        "spec": SPEC,
        "invoiceUrl": f"{base}/invoice",
        "deliveryUrl": f"{base}/order",
        "terms": {"url": f"{PUBLIC_BASE}/terms", "version": "2026-09-29"},
    }
    if name == "packages":
        return {**common, "id": "dev-packages", "title": "Dev packages" + suffix,
                "description": "Mobile packages from the Rayl dev provider.",
                "provider": {"name": "Rayl Dev Mobile"},
                "brand": {"name": "Dev Mobile", "icon": png((200, 38, 27)), "tint": "#C8261B"},
                "inputs": [PHONE], "validateUrl": f"{base}/validate",
                "purchase": {"type": "product", "products": [
                    {"id": pid, "name": label, "price": money(price), "validity": validity,
                     "available": scenario != "soldout" or pid == "inet-1d"}
                    for pid, label, price, validity in PACKAGES]},
                "families": {"small": {"label": "Dev Mobile", "products": ["inet-7d"]},
                             "medium": {"label": "Dev packages"},
                             "large": {"label": "Dev packages"}}}
    if name == "topup":
        return {**common, "id": "dev-topup", "title": "Dev top-up" + suffix,
                "description": "Prepaid balance from the Rayl dev provider.",
                "provider": {"name": "Rayl Dev Mobile"},
                "brand": {"name": "Dev Mobile", "icon": png((200, 38, 27)), "tint": "#C8261B"},
                "inputs": [PHONE], "validateUrl": f"{base}/validate",
                "purchase": {"type": "amount", "currency": "USD",
                             "amount": {"min": "1.00", "max": "100.00", "step": "0.01",
                                        "presets": ["2.00", "5.00", "10.00"]}},
                "families": {"small": {"label": "Top up", "presets": ["5.00"]},
                             "medium": {"label": "Dev top-up"}}}
    if name == "giftcard":
        return {**common, "id": "dev-giftcard", "title": "Dev gift card" + suffix,
                "description": "A gift card delivered as a voucher.",
                "provider": {"name": "Rayl Dev Cards"},
                "brand": {"name": "Dev Eats", "icon": png((255, 40, 63)), "tint": "#FF283F"},
                "purchase": {"type": "product", "products": [
                    {"id": f"card-{v}", "name": f"Dev Eats ${v}", "price": money(v)}
                    for v in (10, 25, 50)]},
                "families": {"small": {"label": "Dev Eats", "products": ["card-10"]},
                             "medium": {"label": "Dev Eats cards"}}}
    if name == "bill":
        return {**common, "id": "dev-bill", "title": "Dev electricity" + suffix,
                "description": "Pays what is due on an electricity account.",
                "provider": {"name": "Rayl Dev Bills"},
                "brand": {"name": "Dev Power", "icon": png((0, 90, 170)), "tint": "#005AAA"},
                "inputs": [{"id": "biller", "type": "choice", "label": "Company",
                            "options": [{"value": "north", "label": "Dev North"},
                                        {"value": "south", "label": "Dev South"}]},
                           {"id": "account", "type": "number", "label": "Account number",
                            "placeholder": "1234567"}],
                "purchase": {"type": "lookup", "currency": "USD"},
                "statusUrl": f"{base}/status",
                "snapshot": {"status": {"title": "Due", "value": "$23.45",
                                        "typedValue": {"type": "money", **money(23.45)}}},
                "families": {"small": {"label": "Dev Power"}, "medium": {"label": "Dev electricity"}}}
    if name == "price":
        return {"spec": SPEC, "id": "dev-price", "title": "Dev bitcoin price" + suffix,
                "description": "A status-only widget.", "provider": {"name": "Rayl Dev Data"},
                "statusUrl": f"{base}/status",
                "snapshot": {"status": {"title": "Bitcoin", "value": "$100,000",
                                        "typedValue": {"type": "money", **money(100000)}}},
                "families": {"small": {"label": "Bitcoin"}}}
    return None


WIDGETS = ("packages", "topup", "giftcard", "bill", "price")


def widget_for(widget_id, scenario):
    return next((d for d in (definition(n, scenario) for n in WIDGETS) if d and d["id"] == widget_id), None)


# ---------------------------------------------------------------------------- server


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="application/json", headers=None):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def _error(self, status, code, message=None, **extra):
        self._send(status, {"error": code, **({"message": message} if message else {}), **extra})

    def do_GET(self):
        parts = self.path.split("?")[0].strip("/").split("/")
        if parts == [""]:
            links = [f"{PUBLIC_BASE}/{s}/{w}.json" for s in SCENARIOS for w in WIDGETS]
            return self._send(200, ("\n".join(links) + "\n").encode(), "text/plain; charset=utf-8")
        if parts == ["terms"]:
            return self._send(200, b"Rayl dev provider: nothing here is real.", "text/plain")
        if len(parts) == 2 and parts[1].endswith(".json"):
            body = definition(parts[1][:-5], parts[0])
            if body:
                data = json.dumps(body, ensure_ascii=False).encode()
                etag = '"' + hashlib.sha256(data).hexdigest()[:16] + '"'
                if self.headers.get("If-None-Match") == etag:
                    return self._send(304, b"", headers={"ETag": etag})
                return self._send(200, data, headers={"ETag": etag})
        self._error(404, "not_found")

    def do_POST(self):
        parts = self.path.strip("/").split("/")
        length = int(self.headers.get("Content-Length") or 0)
        try:
            request = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self._error(400, "invalid_request")
        if len(parts) != 2 or parts[0] not in SCENARIOS:
            return self._error(404, "not_found")
        scenario, action = parts
        handler = {"invoice": self._invoice, "order": self._order,
                   "validate": self._validate, "status": self._status}.get(action)
        if handler is None:
            return self._error(404, "not_found")
        handler(scenario, request)

    def _validate(self, scenario, request):
        if scenario == "refuse":
            return self._error(400, "invalid_input", "This number is not on our network.", inputId="phone")
        phone = (request.get("inputs") or {}).get("phone", "")
        body = {"ok": True}
        if phone:
            body["message"] = f"Line {phone[-8:-4]} ···· {phone[-2:]}"
        self._send(200, body)

    def _invoice(self, scenario, request):
        if scenario == "down":
            return self._error(503, "unavailable", "Our system is down for maintenance.")
        if scenario == "slow":
            time.sleep(25)
        widget = widget_for(request.get("widgetId"), scenario)
        if widget is None or "purchase" not in widget:
            return self._error(404, "not_found")
        if scenario == "refuse" and any(i["id"] == "phone" for i in widget.get("inputs", [])):
            return self._error(400, "invalid_input", "This number is not on our network.", inputId="phone")
        purchase = widget["purchase"]
        if purchase["type"] == "product":
            product = next((p for p in purchase["products"] if p["id"] == request.get("productId")), None)
            if product is None or product.get("available") is False:
                return self._error(409, "product_unavailable", "This package is sold out.")
            price, item = float(product["price"]["amount"]), product["name"]
        elif purchase["type"] == "amount":
            try:
                price = float((request.get("amount") or {}).get("amount"))
            except (TypeError, ValueError):
                return self._error(400, "invalid_request")
            if not 1.0 <= price <= 100.0:
                return self._error(400, "out_of_range", "Top up between $1 and $100.")
            item = f"Top-up {price:.2f} USD"
        else:
            if scenario == "nothingdue":
                return self._send(200, {"payable": False, "message": "Nothing due on this account."})
            price, item = 23.45, "Electricity, September"
        factor = {"mismatch": 4.0, "higher": 1.05, "lower": 0.95}.get(scenario, 1.0)
        sats = usd_to_sats(price * factor)
        if sats is None:
            return self._error(503, "unavailable", "No exchange rate right now.")
        order_id = secrets.token_urlsafe(24)
        ORDERS[order_id] = {"widget": widget["id"], "scenario": scenario, "polls": 0,
                            "created": time.time()}
        print(f"order {order_id[:6]}…: {item} {price:.2f} USD, {scenario}")
        self._send(200, {"invoice": make_invoice(sats, f"{widget['provider']['name']}: {item}"),
                         "orderId": order_id, "price": money(price), "description": item})

    def _order(self, scenario, request):
        order = ORDERS.get(request.get("orderId"))
        if not order:
            return self._error(404, "not_found")
        order["polls"] += 1
        if order["polls"] < 3:
            return self._send(200, {"state": "processing", "retryAfterSeconds": 3})
        if order["scenario"] == "undelivered":
            return self._send(200, {"state": "failed",
                                    "message": "We couldn't deliver this. Your payment is refunded within 24 hours."})
        if order["widget"] == "dev-giftcard":
            return self._send(200, {"state": "delivered", "presentation": "voucher",
                                    "message": "Your card is ready.",
                                    "items": [{"kind": "code", "label": "Card code", "value": "DEV7-K4QX-92PD"},
                                              {"kind": "pin", "label": "PIN", "value": "4821"},
                                              {"kind": "barcode", "label": "Barcode", "value": "061569420001",
                                               "format": "code128"},
                                              {"kind": "date", "label": "Expires", "value": "2027-09-29"}]})
        return self._send(200, {"state": "delivered", "message": "Done. Thanks for testing.",
                                "items": [{"kind": "date", "label": "Active until", "value": "2026-10-29"}]})

    def _status(self, scenario, request):
        if scenario == "down":
            return self._error(503, "unavailable")
        if request.get("widgetId") == "dev-price":
            rate = usd_rate()
            if rate is None:
                return self._error(503, "unavailable")
            body = {"title": "Bitcoin", "value": f"${rate:,.0f}",
                    "typedValue": {"type": "money", **money(rate)}, "asOf": now_iso(),
                    "refreshAfterSeconds": 60}
            if len(_rate["history"]) >= 2:
                body["trend"] = {"points": _rate["history"], "period": "session"}
            return self._send(200, body)
        due = 0.0 if scenario == "nothingdue" else 23.45
        self._send(200, {"title": "Due", "value": f"${due:.2f}",
                         "typedValue": {"type": "money", **money(due)},
                         "subtitle": "Nothing due" if due == 0 else "Due Oct 15",
                         "asOf": now_iso(), "refreshAfterSeconds": 900})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print(f"dev provider on {HOST}:{PORT}, public base {PUBLIC_BASE}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
