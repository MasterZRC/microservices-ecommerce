import requests
import json

# 测试 URL
GATEWAY_URL = "http://localhost:8080"

def test_gateway():
    """测试 API Gateway"""
    print("=" * 60)
    print("测试 API Gateway")
    print("=" * 60)

    # 1. 登录
    print("\n1. 登录")
    response = requests.post(
        f"{GATEWAY_URL}/api/admin/auth/login",
        json={"username": "admin", "password": "admin123"},
        headers={"Content-Type": "application/json"},
        timeout=10
    )
    print(f"状态码: {response.status_code}")
    print(f"响应头: {dict(response.headers)}")
    print(f"响应: {response.text[:1000] if response.text else '(empty)'}")

    if response.status_code != 200:
        return

    data = response.json()
    token = data["data"]["token"]

    # 2. 测试获取管理员信息
    print("\n" + "-" * 40)
    print("2. 测试获取管理员信息")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    resp = requests.get(f"{GATEWAY_URL}/api/admin/auth/info", headers=headers, timeout=10)
    print(f"状态码: {resp.status_code}")
    print(f"响应头: {dict(resp.headers)}")
    print(f"响应: {resp.text[:1000] if resp.text else '(empty)'}")

    # 3. 测试获取仪表盘统计
    print("\n" + "-" * 40)
    print("3. 测试获取仪表盘统计")
    resp = requests.get(f"{GATEWAY_URL}/api/admin/dashboard/stats", headers=headers, timeout=10)
    print(f"状态码: {resp.status_code}")
    print(f"响应头: {dict(resp.headers)}")
    print(f"响应: {resp.text[:1000] if resp.text else '(empty)'}")

if __name__ == "__main__":
    test_gateway()
