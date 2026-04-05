import requests
import json
import http.client
import time

# 调试用 - 打印原始响应
GATEWAY_URL = "http://localhost:8080"

# 1. 登录获取 token
print("=" * 60)
print("步骤 1: 登录")
print("=" * 60)

response = requests.post(
    f"{GATEWAY_URL}/api/admin/auth/login",
    json={"username": "admin", "password": "admin123"},
    headers={"Content-Type": "application/json"},
    timeout=5
)

print(f"状态码: {response.status_code}")
print(f"响应: {response.text[:500] if response.text else '(empty)'}")

if response.status_code != 200:
    print("登录失败!")
    exit(1)

data = response.json()
token = data["data"]["token"]
print(f"\n成功获取 Token: {token[:50]}...")

# 2. 测试获取管理员信息
print("\n" + "=" * 60)
print("步骤 2: 测试获取管理员信息")
print("=" * 60)

headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/json"
}

# 直接使用 http.client 获取原始响应
conn = http.client.HTTPConnection("localhost", 8080, timeout=5)
conn.request("GET", "/api/admin/auth/info", headers=headers)
resp = conn.getresponse()

print(f"状态码: {resp.status}")
print(f"响应头: {dict(resp.getheaders())}")
body = resp.read()
print(f"响应体: {body.decode('utf-8', errors='replace')[:500]}")
conn.close()

# 3. 测试获取仪表盘
print("\n" + "=" * 60)
print("步骤 3: 测试获取仪表盘统计")
print("=" * 60)

conn = http.client.HTTPConnection("localhost", 8080, timeout=5)
conn.request("GET", "/api/admin/dashboard/stats", headers=headers)
resp = conn.getresponse()

print(f"状态码: {resp.status}")
print(f"响应头: {dict(resp.getheaders())}")
body = resp.read()
print(f"响应体: {body.decode('utf-8', errors='replace')[:500]}")
conn.close()
