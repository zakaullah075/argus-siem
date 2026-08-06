#!/usr/bin/env python3
"""End-to-end smoke test. Each check proves one guarantee the README claims.

    ./scripts/smoke.py                          # against the deployed instance
    ./scripts/smoke.py http://localhost:8080    # against a local run

Standard library only, like the agent. Creates two throwaway tenants; signup is
rate limited per IP, so wait a minute between runs.
"""

import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

BASE = (sys.argv[1] if len(sys.argv) > 1 else "https://argus-siem.onrender.com").rstrip("/")
STAMP = int(time.time())

GREEN, RED, DIM, OFF = "\033[32m", "\033[31m", "\033[2m", "\033[0m"
passed = failed = 0


def call(method, path, body=None, headers=None, timeout=90):
    """Returns (status, parsed_body). Never raises on an HTTP error status."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        request.add_header(name, value)

    # Free-tier hosting stalls and returns HTML error pages while it wakes,
    # so transient failures are retried rather than reported as defects.
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            raw = error.read()
            if error.code in (429, 502, 503, 504) and attempt < 2:
                time.sleep(5)
                continue
            try:
                return error.code, json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return error.code, raw.decode(errors="replace").strip()[:300]
        except OSError as error:
            # URLError, socket.timeout and connection resets all land here.
            if attempt < 2:
                time.sleep(5)
                continue
            return 0, f"{type(error).__name__}: {error}"


def require(description, status, body, expected_status=201):
    """A prerequisite: everything downstream is meaningless if it fails."""
    if status != expected_status or not isinstance(body, dict):
        check(description, expected_status, status)
        print(f"        response: {body!r}")
        print(f"\naborting — {description} is a prerequisite\n")
        sys.exit(1)
    check(description, expected_status, status)


def check(description, expected, actual):
    global passed, failed
    if expected == actual:
        passed += 1
        print(f"  {GREEN}PASS{OFF}  {description}")
    else:
        failed += 1
        print(f"  {RED}FAIL{OFF}  {description}")
        if actual == 0:
            # Distinguishing this matters: it says nothing about the application.
            print("        no response after 3 attempts — transport, not the app")
        else:
            print(f"        expected {expected!r}, got {actual!r}")


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def ship(key, event_type, severity, event_id=None):
    return call("POST", "/v1/events", {
        "id": event_id or str(uuid.uuid4()),
        "source": "smoke",
        "eventType": event_type,
        "severity": severity,
        "actor": "attacker",
        "target": "10.0.0.9",
        "payload": {"run": STAMP},
        "occurredAt": now(),
    }, {"X-Api-Key": key})


def poll(key, path, predicate, seconds=40):
    """Detection runs in a consumer, so results arrive after the ingest returns."""
    body = None
    for _ in range(seconds):
        _, body = call("GET", path, headers={"X-Api-Key": key})
        if isinstance(body, dict) and predicate(body):
            return body
        time.sleep(1)
    return body


print(f"\nArgus smoke test {DIM}→ {BASE}{OFF}\n")

# ------------------------------------------------------------------ liveness
print("Liveness")
status, health = call("GET", "/actuator/health")
state = health.get("status") if isinstance(health, dict) else None
check("application reports healthy", "UP", state)
if state != "UP":
    print("\naborting — application is not up\n")
    sys.exit(1)

# ------------------------------------------------------------------ tenant A
print("\nSignup and credentials")
status, session = call("POST", "/v1/auth/signup", {
    "organisation": f"smoke-a-{STAMP}",
    "email": f"a-{STAMP}@smoke.test",
    "password": "supersecret1",
})
require("signup returns 201", status, session)

token_a = session["token"]
admin = {"Authorization": f"Bearer {token_a}"}
check("first user becomes ADMIN", "ADMIN", session.get("role"))

status, issued = call("POST", "/v1/management/api-keys", {"name": "smoke-agent"}, admin)
require("issuing a key returns 201", status, issued)
key_a = issued.get("apiKey")
check("the plaintext key is returned once", True, bool(key_a))

status, keys = call("GET", "/v1/management/api-keys", headers=admin)
if not isinstance(keys, list) or not keys:
    print(f"        response: {keys!r}")
    print("\naborting — cannot list api keys\n")
    sys.exit(1)
key_a_id = keys[0]["id"]
leaked = any(field in keys[0] for field in ("keyHash", "hash", "secret"))
check("listing never exposes the hash", False, leaked)

# ----------------------------------------------------------------- detection
print("\nDetection")
status, rule = call("POST", "/v1/management/rules", {
    "name": "smoke brute force",
    "matchSource": "smoke",
    "matchEventType": "auth.failed",
    "minSeverity": "MEDIUM",
    "thresholdCount": 3,
    "windowSeconds": 300,
    "alertSeverity": "CRITICAL",
}, admin)
require("rule created", status, rule)

duplicate_id = str(uuid.uuid4())
status, first = ship(key_a, "auth.failed", "HIGH", duplicate_id)
_, again = ship(key_a, "auth.failed", "HIGH", duplicate_id)
check("ingest returns 202, not 201", 202, status)
check("the first send is accepted", False, first.get("duplicate"))
check("resending the same id is a duplicate", True, again.get("duplicate"))

ship(key_a, "auth.failed", "HIGH")   # 2 of 3
ship(key_a, "auth.failed", "HIGH")   # 3 of 3 — threshold reached
ship(key_a, "auth.success", "LOW")   # must not match the rule

alerts = poll(key_a, "/v1/alerts", lambda body: body.get("totalElements", 0) >= 1)
total = alerts.get("totalElements", 0)
check("a threshold breach raises an alert", 1, total)

if total:
    alert = alerts["content"][0]
    occurrences = alert.get("occurrenceCount", 0)
    check("the alert takes the rule's severity", "CRITICAL", alert.get("severity"))
    # occurrenceCount is how many times the rule tripped, not how many events
    # matched — three events reaching a threshold of three is one trip.
    check("a new alert starts at one occurrence", 1, occurrences)

    ship(key_a, "auth.failed", "HIGH")
    folded = poll(key_a, "/v1/alerts",
                  lambda body: body["content"][0]["occurrenceCount"] > occurrences)
    check("a further breach folds in, not a new alert", 1, folded.get("totalElements"))
    check("folding increments the count", True,
          folded["content"][0]["occurrenceCount"] > occurrences)

# -------------------------------------------------------------- api contract
print("\nAPI contract")
_, page = call("GET", "/v1/events?size=2", headers={"X-Api-Key": key_a})
check("list endpoints are paginated", 2, len(page.get("content", [])))
check("the page reports the true total", True, page.get("totalElements", 0) >= 5)

status, problem = call("POST", "/v1/events", {
    "source": "smoke", "eventType": "auth.failed",
    "payload": {}, "occurredAt": now(),          # severity omitted
}, {"X-Api-Key": key_a})
check("an invalid body is rejected", 400, status)
check("errors use the RFC 7807 shape", True,
      isinstance(problem, dict) and {"title", "status", "detail"} <= problem.keys())

# ------------------------------------------------------ authn and isolation
print("\nAuthentication and isolation")
check("no api key is rejected", 401, call("GET", "/v1/events")[0])
check("a bad api key is rejected", 401,
      call("GET", "/v1/events", headers={"X-Api-Key": "not-a-real-key"})[0])
check("an api key cannot reach a jwt route", 401,
      call("GET", "/v1/management/rules", headers={"X-Api-Key": key_a})[0])

status, session_b = call("POST", "/v1/auth/signup", {
    "organisation": f"smoke-b-{STAMP}",
    "email": f"b-{STAMP}@smoke.test",
    "password": "supersecret1",
})
if isinstance(session_b, dict) and session_b.get("token"):
    admin_b = {"Authorization": f"Bearer {session_b['token']}"}
    _, issued_b = call("POST", "/v1/management/api-keys", {"name": "smoke-agent-b"}, admin_b)
    key_b = {"X-Api-Key": issued_b["apiKey"]}
    check("another tenant sees no events", 0,
          call("GET", "/v1/events", headers=key_b)[1].get("totalElements"))
    check("another tenant sees no alerts", 0,
          call("GET", "/v1/alerts", headers=key_b)[1].get("totalElements"))
else:
    print(f"  {DIM}SKIP{OFF}  tenant isolation (signup throttled — rerun in a minute)")

call("DELETE", f"/v1/management/api-keys/{key_a_id}", headers=admin)
check("a revoked key stops working immediately", 401,
      call("GET", "/v1/events", headers={"X-Api-Key": key_a})[0])

# -------------------------------------------------------------------- result
print()
if failed:
    print(f"{RED}FAILED{OFF}  {passed} passed, {failed} failed\n")
else:
    print(f"{GREEN}ALL PASSED{OFF}  {passed} checks\n")
sys.exit(1 if failed else 0)
