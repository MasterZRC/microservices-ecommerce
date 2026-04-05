import pymysql
import bcrypt
import urllib.request, json

# Generate fresh BCrypt hash
password = b'admin123'
new_hash = bcrypt.hashpw(password, bcrypt.gensalt(rounds=10)).decode()
print(f"New hash: {new_hash}")

# Verify
print(f"Verify: {bcrypt.checkpw(password, new_hash.encode())}")

# Update DB
conn = pymysql.connect(host='mysql', user='root', password='root123', database='ecommerce', charset='utf8mb4')
cursor = conn.cursor()
cursor.execute("UPDATE admin_user SET password=%s WHERE username=%s", (new_hash, 'admin'))
conn.commit()
cursor.execute("SELECT password FROM admin_user WHERE username='admin'")
stored = cursor.fetchone()[0]
cursor.close()
conn.close()
print(f"Stored after update: {stored}")

# Verify with admin-service
data = json.dumps({"username": "admin", "password": "admin123"}).encode()
req = urllib.request.Request(
    "http://admin-service:8006/api/admin/auth/login",
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST"
)
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        print(f"Login Status: {r.status}")
        print(f"Login Body: {r.read().decode()}")
except Exception as e:
    print(f"Login Error: {e}")
