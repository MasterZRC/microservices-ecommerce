import pymysql
import bcrypt
import urllib.request, json

# Generate a FRESH BCrypt hash using THIS library and update DB
password = b'admin123'
new_hash = bcrypt.hashpw(password, bcrypt.gensalt(rounds=10)).decode('utf-8')
print(f"Fresh hash from Python bcrypt: {new_hash}")
print(f"Verify: {bcrypt.checkpw(password, new_hash.encode('utf-8'))}")

# Update DB
conn = pymysql.connect(host='mysql', user='root', password='root123', database='ecommerce', charset='utf8mb4')
cursor = conn.cursor()
cursor.execute("UPDATE admin_user SET password=%s WHERE username=%s", (new_hash, 'admin'))
conn.commit()
print("DB updated!")

# Test login
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
