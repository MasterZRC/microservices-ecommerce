import pymysql
import subprocess

# First get the password from DB
conn = pymysql.connect(host='mysql', user='root', password='root123', database='ecommerce', charset='utf8mb4')
cursor = conn.cursor()
cursor.execute("SELECT password FROM admin_user WHERE username='admin'")
stored_hash = cursor.fetchone()[0]
conn.close()

print(f"Stored hash: {stored_hash}")
print(f"Hash prefix: {stored_hash[:7]}")

# Check bcrypt compatibility
import hashlib
# Verify it's a valid BCrypt format
import re
bcrypt_pattern = r'^\$2[aby]?\$\d{2}\$[./A-Za-z0-9]{53}$'
if re.match(bcrypt_pattern, stored_hash):
    print("Valid BCrypt format detected")
else:
    print(f"Not valid BCrypt format. First 10 chars: {stored_hash[:10]}")

# Test matching using bcrypt Python
try:
    import bcrypt
    test_password = b'admin123'
    test_hash = stored_hash.encode('utf-8')
    result = bcrypt.checkpw(test_password, test_hash)
    print(f"bcrypt.checkpw result: {result}")
except ImportError:
    print("bcrypt module not available")
except Exception as e:
    print(f"bcrypt error: {e}")

# Now test the actual login API
import urllib.request
import json

data = json.dumps({"username": "admin", "password": "admin123"}).encode()
req = urllib.request.Request(
    "http://admin-service:8006/api/admin/auth/login",
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST"
)
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        print(f"\nLogin API Status: {r.status}")
        print(f"Login API Body: {r.read().decode()}")
except Exception as e:
    print(f"\nLogin API Error: {e}")
