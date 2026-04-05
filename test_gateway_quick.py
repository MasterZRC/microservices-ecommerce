import requests
import json

# 测试 URL
GATEWAY_URL = "http://localhost:8080"

def test_gateway():
    print("=" * 60)
    print("测试 API Gateway")
    print("=" * 60)

    # 1. 登录
    print("\n1. 登录")
    response = requests.post(
        f"{GATEWAY_URL}/api/admin/auth/login",
        json={"username": "admin", "password": "admin123"},
        headers={"Content-Type": "application/json"},
        timeout=5  # 短超时
    )
    print(f"状态码: {response.status_code}")
    print(f"响应: {response.text[:500] if response.text else '(empty)'}")

    if response.status_code != 200:
        print("登录失败!")
        return

    data = response.json()
    token = data["data"]["token"]
    print(f"\n成功获取 Token!")

    # 2. 测试获取管理员信息
    print("\n2. 测试获取管理员信息")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    resp = requests.get(f"{GATEWAY_URL}/api/admin/auth/info", headers=headers, timeout=5)
    print(f"状态码: {resp.status_code}")
    print(f"响应: {resp.text[:500] if resp.text else '(empty)'}")

if __name__ == "__main__":
    test_gateway()
