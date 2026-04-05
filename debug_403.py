import urllib.request
import json

BASE = "http://api-gateway:8080/api/admin"

# Login
req = urllib.request.Request(
    BASE + "/auth/login",
    data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST"
)
try:
    with urllib.request.urlopen(req, timeout=10) as r:
        resp = json.loads(r.read())
        token = resp["data"]["token"]
        print("Login OK, token: " + token[:30])
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
except Exception as e:
    print("Login FAILED: " + str(e))
    import sys
    sys.exit(1)

# Test via gateway
def test_via_gateway(path):
    req = urllib.request.Request(BASE + path, data=None, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read()
            return r.status, body.decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        body = e.read()
        return e.code, body.decode("utf-8", errors="replace")
    except Exception as e:
        return 0, str(e)

# Test via gateway and print full response
for path in ["/dashboard/stats", "/products?page=1&size=5"]:
    status, body = test_via_gateway(path)
    print("\nGateway -> " + path + " | Status: " + str(status))
    print("Body: " + body[:200])
