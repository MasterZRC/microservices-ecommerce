import requests
import json

# 测试 URL
GATEWAY_URL = "http://localhost:8080"

# 1. 登录
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

resp = requests.get(f"{GATEWAY_URL}/api/admin/auth/info", headers=headers, timeout=5)
print(f"状态码: {resp.status_code}")
print(f"响应: {resp.text[:500] if resp.text else '(empty)'}")

# 3. 测试获取仪表盘
print("\n" + "=" * 60)
print("步骤 3: 测试获取仪表盘统计")
print("=" * 60)

resp = requests.get(f"{GATEWAY_URL}/api/admin/dashboard/stats", headers=headers, timeout=5)
print(f"状态码: {resp.status_code}")
print(f"响应: {resp.text[:500] if resp.text else '(empty)'}")
