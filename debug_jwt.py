import base64
import json
import requests

# 首先登录获取 token
response = requests.post(
    "http://localhost:8080/api/admin/auth/login",
    json={"username": "admin", "password": "admin123"},
    headers={"Content-Type": "application/json"},
    timeout=10
)

print("登录响应:")
print(f"状态码: {response.status_code}")
data = response.json()
print(json.dumps(data, ensure_ascii=False, indent=2))

if data.get("code") != 200:
    print("登录失败!")
    exit(1)

token = data["data"]["token"]
print(f"\nToken: {token[:50]}...")

# 解析 JWT header
def decode_jwt_part(part):
    # JWT 的第二部分是 payload，需要添加 padding 进行 base64 解码
    padding = 4 - len(part) % 4
    if padding != 4:
        part += "=" * padding
    decoded = base64.urlsafe_b64decode(part)
    return json.loads(decoded)

parts = token.split(".")
header = json.loads(base64.urlsafe_b64decode(parts[0] + "=="))
payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=="))

print("\nJWT Header:")
print(json.dumps(header, indent=2))

print("\nJWT Payload:")
print(json.dumps(payload, indent=2))

print("\n" + "=" * 50)
print("测试各个 API 端点")
print("=" * 50)

headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/json"
}

# 测试各个端点
endpoints = [
    ("/api/admin/auth/info", "GET"),
    ("/api/admin/dashboard/stats", "GET"),
    ("/api/admin/orders?page=1&size=3", "GET"),
    ("/api/admin/products?page=1&size=3", "GET"),
    ("/api/admin/seckills?page=1&size=3", "GET"),
]

for path, method in endpoints:
    print(f"\n{'-' * 40}")
    print(f"测试: {method} {path}")

    try:
        if method == "GET":
            resp = requests.get(f"http://localhost:8080{path}", headers=headers, timeout=10)
        else:
            resp = requests.post(f"http://localhost:8080{path}", headers=headers, timeout=10)

        print(f"状态码: {resp.status_code}")
        print(f"响应: {resp.text[:300]}")

    except Exception as e:
        print(f"错误: {e}")
