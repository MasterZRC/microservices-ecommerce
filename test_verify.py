import urllib.request
import json

# Test login via admin-service using proper JSON
data = json.dumps({"username": "admin", "password": "admin123"}).encode()
req = urllib.request.Request(
    "http://admin-service:8006/api/admin/auth/login",
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

# Also verify BCrypt hash matches
import subprocess
result = subprocess.run(
    ["docker", "exec", "ecommerce-mysql", "mysql", "-u", "root", "-proot123", "-D", "ecommerce", "-e",
     "SELECT password FROM admin_user WHERE username='admin';"],
    capture_output=True, text=True
)
print("DB password output:", result.stdout)
