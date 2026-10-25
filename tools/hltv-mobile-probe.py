"""Single anonymous GET for an observed HLTV mobile route; developer diagnostic only.

No authentication, automatic retries, challenge solving or app-cache writes.
A valid JSON response is still an unverified schema, not successful app integration.
See docs/HLTV-MOBILE-API-RESEARCH.md.
"""
import argparse
import hashlib
import json
import re
import sys
import time
from datetime import datetime, timezone
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROUTES = {
    "matches": ("MatchesV4", None),
    "match": ("MatchScreen", "matchId"),
    "events": ("EventsData2", None),
    "event": ("EventDetails2", "eventId"),
    "team": ("TeamScreen", "teamId"),
    "player": ("PlayerScreen", "playerId"),
}
MAX_BYTES = 4 * 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_url(kind, entity_id=None):
    route, parameter = ROUTES[kind]
    if parameter:
        if not entity_id or not re.fullmatch(r"[1-9][0-9]{0,9}", entity_id):
            raise ValueError("This route requires one positive numeric --id.")
    elif entity_id is not None:
        raise ValueError("This list route does not take --id.")
    query = "?" + urlencode({parameter: entity_id}) if parameter else ""
    return "https://www.hltv.org/mobile/" + route + query


def classify(status, headers, body):
    """Return diagnostic kind and optional JSON, never inferred match data."""
    h = {k.lower(): v.strip() for k, v in headers.items()}
    if status == 401 and h.get("x-app-integrity-error", "").lower() == "required":
        return "app_integrity_required", None
    if h.get("cf-mitigated", "").lower() == "challenge":
        return "cloudflare_challenge", None
    if status == 403:
        cf = "cloudflare" in h.get("server", "").lower() or "cf-ray" in h
        return ("cloudflare_denied" if cf else "forbidden"), None
    if status == 401:
        return "unauthorized", None
    if status == 429:
        return "rate_limited", None
    if 300 <= status < 400:
        return "redirect_not_followed", None
    if not 200 <= status < 300:
        return "http_error", None
    if len(body) > MAX_BYTES:
        return "response_too_large", None
    media_type = h.get("content-type", "").split(";", 1)[0].lower().strip()
    if media_type != "application/json" and not (media_type.startswith("application/") and media_type.endswith("+json")):
        return "unexpected_content_type", None
    try:
        payload = json.loads(body)
    except (ValueError, UnicodeError, RecursionError):
        return "invalid_json", None
    if not isinstance(payload, (dict, list)):
        return "unexpected_json_shape", None
    # cf-ray alone is not evidence of blocking on a successful JSON response.
    return "json_received_schema_unverified", payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("route", choices=ROUTES)
    parser.add_argument("--id")
    parser.add_argument("--out", type=Path, default=Path(".local/hltv-api-probe"))
    args = parser.parse_args()
    try:
        url = request_url(args.route, args.id)
    except ValueError as error:
        parser.error(str(error))
    started = datetime.now(timezone.utc)
    report = {
        "url": url, "startedAt": started.isoformat(),
        "environment": "host-python-not-android",
        "authenticationSupplied": False, "officialClientIdentityImitated": False,
        "retryCount": 0, "redirectsFollowed": 0,
    }
    request = Request(url, headers={"User-Agent": "RushB/0.6 (independent open-source reader)", "Accept": "application/json"})
    before = time.monotonic()
    payload = None
    try:
        try:
            response = build_opener(NoRedirect()).open(request, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            body = response.read(MAX_BYTES + 1)
            headers = dict(response.headers.items())
            status = response.code
        kind, payload = classify(status, headers, body)
        report.update({
            "kind": kind, "status": status,
            "contentType": response.headers.get("Content-Type"),
            "retryAfter": response.headers.get("Retry-After"),
            "integrityError": response.headers.get("X-App-Integrity-Error"),
            "bytesRead": len(body), "bodyTruncated": len(body) > MAX_BYTES,
            "sha256OfReadBytes": hashlib.sha256(body).hexdigest(),
        })
    except (URLError, OSError, HTTPException) as error:
        report.update({"kind": "network_error", "errorType": type(error).__name__})
    report["seconds"] = round(time.monotonic() - before, 3)
    args.out.mkdir(parents=True, exist_ok=True)
    stem = started.strftime("%Y%m%dT%H%M%S%fZ") + "-" + args.route
    if payload is not None:
        filename = stem + ".payload.json"
        with (args.out / filename).open("x", encoding="utf-8") as output:
            json.dump(payload, output, ensure_ascii=False, indent=2)
        report["payloadFile"] = filename
    with (args.out / (stem + ".report.json")).open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if payload is not None else 2


if __name__ == "__main__":
    sys.exit(main())
