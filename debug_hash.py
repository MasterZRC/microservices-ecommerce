import pymysql
import bcrypt
import urllib.request, json

# Check what the DB actually contains
conn = pymysql.connect(host='mysql', user='root', password='root123', database='ecommerce', charset='utf8mb4')
cursor = conn.cursor()
cursor.execute("SELECT password FROM admin_user WHERE username='admin'")
raw_bytes = cursor.fetchone()[0]  # pymysql returns bytes
cursor.close()
conn.close()

print(f"Type: {type(raw_bytes)}")
print(f"Raw bytes: {raw_bytes}")

# Decode
if isinstance(raw_bytes, bytes):
    raw = raw_bytes.decode('utf-8')
else:
    raw = raw_bytes

print(f"Decoded: [{raw}]")
print(f"Length: {len(raw)}")
print(f"Starts with $: {raw.startswith('$')}")

# Check BCrypt format
import re
bcrypt_pattern = r'^\$2[abxy]?\$\d{2}\$[./A-Za-z0-9]{53}$'
if re.match(bcrypt_pattern, raw):
    print("Valid BCrypt format!")
    result = bcrypt.checkpw(b'admin123', raw.encode('utf-8'))
    print(f"bcrypt.checkpw: {result}")
else:
    print("INVALID BCrypt format!")
    print(f"First 60 chars: [{raw[:60]}]")

# Now test the login API
data = json.dumps({"username": "admin", "password": "admin123"}).encode()
req = urllib.request.Request(
    "http://admin-service:8006/api/admin/auth/login",
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST"
)
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        print(f"\nLogin Status: {r.status}")
        print(f"Login Body: {r.read().decode()}")
except Exception as e:
    print(f"\nLogin Error: {e}")
