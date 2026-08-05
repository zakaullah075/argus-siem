#!/usr/bin/env python3
"""
Argus agent — tails a log file or journald and ships security events.

Standard library only, so it runs on any machine with Python 3.8+ and needs no
install step. Deliberately small: an agent that is hard to audit is hard to
justify running as root.

    export ARGUS_API_KEY=argus_...
    python3 argus-agent.py --follow /var/log/auth.log

    # or, on a systemd host
    journalctl -f -u ssh | python3 argus-agent.py --stdin --source sshd

    # send one event and exit, to prove connectivity
    python3 argus-agent.py --test
"""

import argparse
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

DEFAULT_ENDPOINT = "https://argus-siem.onrender.com"

# Each rule maps a log line to a normalised event. Ordered: the first match
# wins, so more specific patterns must come first.
PATTERNS = [
    (
        re.compile(r"Failed password for (?:invalid user )?(?P<actor>\S+) from (?P<ip>\S+)"),
        {"source": "sshd", "event_type": "auth.failed", "severity": "HIGH"},
    ),
    (
        re.compile(r"Invalid user (?P<actor>\S+) from (?P<ip>\S+)"),
        {"source": "sshd", "event_type": "auth.invalid_user", "severity": "HIGH"},
    ),
    (
        re.compile(r"Accepted (?:password|publickey) for (?P<actor>\S+) from (?P<ip>\S+)"),
        {"source": "sshd", "event_type": "auth.success", "severity": "LOW"},
    ),
    (
        re.compile(r"sudo:\s+(?P<actor>\S+).*COMMAND=(?P<command>.+)$"),
        {"source": "sudo", "event_type": "privilege.escalation", "severity": "CRITICAL"},
    ),
    (
        re.compile(r"session opened for user (?P<actor>\S+)"),
        {"source": "systemd", "event_type": "session.opened", "severity": "LOW"},
    ),
    (
        re.compile(r"authentication failure.*user=(?P<actor>\S+)"),
        {"source": "pam", "event_type": "auth.failed", "severity": "MEDIUM"},
    ),
]


class Shipper:
    """Posts events, retrying on transport errors.

    The event id is generated here rather than by the server, so a retry after a
    timeout resends the same id and the server treats it as a duplicate instead
    of recording the event twice.
    """

    def __init__(self, endpoint, api_key, hostname, verbose=False):
        self.url = endpoint.rstrip("/") + "/v1/events"
        self.api_key = api_key
        self.hostname = hostname
        self.verbose = verbose
        self.sent = 0
        self.failed = 0

    def send(self, event, attempts=3):
        event_id = str(uuid.uuid4())
        body = json.dumps({
            "id": event_id,
            "source": event["source"],
            "eventType": event["event_type"],
            "severity": event["severity"],
            "actor": event.get("actor"),
            "target": event.get("target") or self.hostname,
            "payload": event.get("payload", {}),
            "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }).encode()

        request = urllib.request.Request(
            self.url, data=body, method="POST",
            headers={"Content-Type": "application/json", "X-Api-Key": self.api_key},
        )

        backoff = 1
        for attempt in range(1, attempts + 1):
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    self.sent += 1
                    if self.verbose:
                        payload = json.loads(response.read())
                        marker = "dup" if payload.get("duplicate") else "new"
                        print(f"  -> {response.status} {marker} {event['event_type']}")
                    return True

            except urllib.error.HTTPError as e:
                # 4xx means the request itself is wrong; resending it unchanged
                # will fail identically, so stop rather than spin.
                if 400 <= e.code < 500 and e.code != 429:
                    self.failed += 1
                    print(f"  !! {e.code} {e.reason} — {e.read().decode()[:200]}", file=sys.stderr)
                    return False
                print(f"  .. {e.code}, retrying in {backoff}s", file=sys.stderr)

            except (urllib.error.URLError, socket.timeout, TimeoutError) as e:
                # The free tier sleeps when idle, so the first request after a
                # quiet period can take the better part of a minute.
                print(f"  .. {e}, retrying in {backoff}s", file=sys.stderr)

            if attempt < attempts:
                time.sleep(backoff)
                backoff *= 2

        self.failed += 1
        return False


def parse(line):
    for pattern, template in PATTERNS:
        match = pattern.search(line)
        if not match:
            continue

        groups = match.groupdict()
        event = dict(template)
        event["actor"] = groups.get("actor")
        event["payload"] = {k: v for k, v in groups.items() if v is not None}
        event["payload"]["raw"] = line.strip()[:500]
        if groups.get("ip"):
            event["target"] = groups["ip"]
        return event
    return None


def follow(path):
    """Yields new lines, surviving log rotation.

    Reopening on inode change matters: without it the agent keeps a handle on a
    rotated-away file and goes quiet without ever reporting an error.
    """
    handle = open(path, "r", errors="replace")
    handle.seek(0, os.SEEK_END)
    inode = os.fstat(handle.fileno()).st_ino

    while True:
        line = handle.readline()
        if line:
            yield line
            continue

        time.sleep(0.4)
        try:
            if os.stat(path).st_ino != inode:
                handle.close()
                handle = open(path, "r", errors="replace")
                inode = os.fstat(handle.fileno()).st_ino
        except FileNotFoundError:
            time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description="Ship security events to Argus")
    parser.add_argument("--follow", metavar="FILE", help="tail a log file")
    parser.add_argument("--stdin", action="store_true", help="read lines from stdin")
    parser.add_argument("--test", action="store_true", help="send one event and exit")
    parser.add_argument("--endpoint", default=os.environ.get("ARGUS_ENDPOINT", DEFAULT_ENDPOINT))
    parser.add_argument("--api-key", default=os.environ.get("ARGUS_API_KEY"))
    parser.add_argument("--source", help="override the detected source")
    parser.add_argument("--hostname", default=socket.gethostname())
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    if not args.api_key:
        parser.error("set ARGUS_API_KEY or pass --api-key")

    shipper = Shipper(args.endpoint, args.api_key, args.hostname, args.verbose)

    if args.test:
        print(f"Sending a test event to {args.endpoint} as {args.hostname}")
        ok = shipper.send({
            "source": args.source or "argus-agent",
            "event_type": "agent.test",
            "severity": "LOW",
            "actor": os.environ.get("USER", "unknown"),
            "payload": {"agent": "python", "hostname": args.hostname},
        })
        print("OK — check the dashboard" if ok else "Failed — see the error above")
        return 0 if ok else 1

    if args.follow:
        print(f"Tailing {args.follow}, shipping to {args.endpoint}")
        lines = follow(args.follow)
    elif args.stdin:
        print(f"Reading stdin, shipping to {args.endpoint}")
        lines = sys.stdin
    else:
        parser.error("pass --follow FILE, --stdin, or --test")

    try:
        for line in lines:
            event = parse(line)
            if not event:
                continue
            if args.source:
                event["source"] = args.source
            shipper.send(event)
    except KeyboardInterrupt:
        pass
    finally:
        print(f"\nsent={shipper.sent} failed={shipper.failed}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
