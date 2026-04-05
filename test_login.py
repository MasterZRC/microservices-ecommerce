import urllib.request
import json

data = json.dumps({"username": "admin", "password": "admin123"}).encode()
req = urllib.request.Request(
    "http://localhost:8080/api/admin/auth/login",
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST"
)
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        print(f"Status: {r.status}")
        print(f"Body: {r.read().decode()}")
except Exception as e:
    print(f"Error: {e}")
