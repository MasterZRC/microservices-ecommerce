import http.client
import json

def post_login():
    conn = http.client.HTTPConnection("api-gateway", 8080, timeout=15)
    try:
        conn.request(
            "POST",
            "/api/admin/auth/login",
            body=json.dumps({"username": "admin", "password": "admin123"}),
            headers={"Content-Type": "application/json"},
        )
        r = conn.getresponse()
        return r.status, r.read().decode()
    finally:
        conn.close()

def get_stats(token):
    conn = http.client.HTTPConnection("api-gateway", 8080, timeout=15)
    try:
        conn.request(
            "GET",
            "/api/admin/dashboard/stats",
            headers={
                "Authorization": "Bearer " + token,
                "Content-Type": "application/json",
            },
        )
        r = conn.getresponse()
        return r.status, r.read().decode()
    finally:
        conn.close()

s, body = post_login()
print("login", s, body[:120])
if s != 200:
    raise SystemExit(1)
data = json.loads(body)
token = data["data"]["token"]
s2, b2 = get_stats(token)
print("stats", s2, b2[:200])
