import urllib.request
import json

# Test login via admin-service directly
data = json.dumps({"username": "admin", "password": "admin123"}).encode()
req = urllib.request.Request(
    "http://localhost:8006/api/admin/auth/login",
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST"
)
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        print("Status:", r.status)
        print("Body:", r.read().decode())
except Exception as e:
    print("Error:", e)
