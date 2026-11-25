# Masks secrets in the Bruno JSON reports before they are committed: the gateway secret sent as X-Gateway-Secret and
# every JWT (Keycloak access/refresh tokens in responses and Authorization headers). Statuses, bodies and test
# results stay as they are, so bruno_results.py reads the same verdicts.
#
#   GATEWAY_INTERNAL_SECRET=... python redact_reports.py results/round1
import glob
import os
import re
import sys

JWT = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")

folder = sys.argv[1]
secret = os.environ.get("GATEWAY_INTERNAL_SECRET", "")
for path in glob.glob(os.path.join(folder, "*.json")):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    masked = JWT.sub("<redacted:jwt>", text)
    # stack traces and file names carry the tester's machine paths (JSON escapes each backslash)
    masked = re.sub(r"[A-Za-z]:(?:\\\\|/)(?:[^\"\\\\/]+(?:\\\\|/))*?(?=backend(?:\\\\|/)bruno)", "<repo>/", masked)
    masked = re.sub(r"[A-Za-z]:(?:\\\\|/)Users(?:\\\\|/)[^\"\\\\/]+", "<home>", masked)
    if secret:
        masked = masked.replace(secret, "<redacted:GATEWAY_INTERNAL_SECRET>")
    if masked != text:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(masked)
        print("redacted", path)
