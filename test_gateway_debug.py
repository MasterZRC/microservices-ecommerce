import requests
import json
import base64
import time

# 直接访问 admin-service
ADMIN_SERVICE_URL = "http://localhost:8006"

# 通过 API Gateway 访问
GATEWAY_URL = "http://localhost:8080"

def decode_jwt_part(part):
    padding = 4 - len(part) % 4
    if padding != 4:
        part += "=" * padding
    return json.loads(base64.urlsafe_b64decode(part))

def test_direct_to_admin_service():
    """直接测试 admin-service"""
    print("=" * 60)
    print("直接测试 admin-service (绕过 API Gateway)")
    print("=" * 60)

    try:
        response = requests.post(
            f"{ADMIN_SERVICE_URL}/api/admin/auth/login",
            json={"username": "admin", "password": "admin123"},
            headers={"Content-Type": "application/json"},
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        if response.text:
            print(f"响应: {response.text[:500]}")
        return None
    except requests.exceptions.ConnectionError as e:
        print(f"连接失败: {e}")
        print("\n原因: admin-service 的 8006 端口没有映射到宿主机")
        return None

def test_through_gateway():
    """通过 API Gateway 测试"""
    print("\n" + "=" * 60)
    print("通过 API Gateway 测试")
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

    if response.status_code != 200:
        print(f"响应: {response.text}")
        return

    data = response.json()
    print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)}")

    if data.get("code") != 200:
        print("登录失败!")
        return

    token = data["data"]["token"]
    print(f"\n成功获取 Token!")

    # 解析 JWT
    parts = token.split(".")
    payload = decode_jwt_part(parts[1] + "==")
    print(f"JWT Payload: {json.dumps(payload, indent=2)}")

    # 2. 测试获取管理员信息
    print("\n" + "-" * 40)
    print("2. 测试获取管理员信息")
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.get(f"{GATEWAY_URL}/api/admin/auth/info", headers=headers, timeout=10)
    print(f"状态码: {resp.status_code}")
    print(f"响应: {resp.text[:500] if resp.text else '(empty)'}")

    # 3. 测试获取仪表盘统计
    print("\n" + "-" * 40)
    print("3. 测试获取仪表盘统计")
    resp = requests.get(f"{GATEWAY_URL}/api/admin/dashboard/stats", headers=headers, timeout=10)
    print(f"状态码: {resp.status_code}")
    print(f"响应: {resp.text[:500] if resp.text else '(empty)'}")

if __name__ == "__main__":
    print("\n" + "=" * 60)
    print("测试 1: 直接访问 admin-service")
    print("=" * 60)
    test_direct_to_admin_service()

    print("\n\n")
    test_through_gateway()
