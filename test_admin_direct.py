import requests
import json

# 直接访问 admin-service（不通过网关）
BASE_URL = "http://localhost:8006/api/admin"

def test_login_direct():
    """直接测试 admin-service 登录"""
    print("=" * 50)
    print("直接测试 admin-service 登录")
    print("=" * 50)

    response = requests.post(
        f"{BASE_URL}/auth/login",
        json={"username": "admin", "password": "admin123"},
        headers={"Content-Type": "application/json"},
        timeout=10
    )

    print(f"状态码: {response.status_code}")
    print(f"响应头: {dict(response.headers)}")

    if response.text:
        try:
            data = response.json()
            print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)}")
            return data.get("data", {}).get("token")
        except json.JSONDecodeError as e:
            print(f"JSON解析错误: {e}")
            print(f"原始响应: {response.text[:500]}")
    return None

def test_with_token(token):
    """使用 token 测试其他接口"""
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}"
    }

    # 测试管理员信息
    print("\n" + "-" * 40)
    print("测试获取管理员信息")
    try:
        response = requests.get(f"{BASE_URL}/auth/info", headers=headers, timeout=10)
        print(f"状态码: {response.status_code}")
        if response.text:
            data = response.json()
            print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)}")
    except Exception as e:
        print(f"错误: {e}")

    # 测试仪表盘
    print("\n" + "-" * 40)
    print("测试仪表盘")
    try:
        response = requests.get(f"{BASE_URL}/dashboard/stats", headers=headers, timeout=10)
        print(f"状态码: {response.status_code}")
        if response.text:
            data = response.json()
            print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)}")
    except Exception as e:
        print(f"错误: {e}")

    # 测试订单列表
    print("\n" + "-" * 40)
    print("测试订单列表")
    try:
        response = requests.get(f"{BASE_URL}/orders", headers=headers, params={"page": 1, "size": 3}, timeout=10)
        print(f"状态码: {response.status_code}")
        if response.text:
            data = response.json()
            print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)[:500]}...")
    except Exception as e:
        print(f"错误: {e}")

    # 测试商品列表
    print("\n" + "-" * 40)
    print("测试商品列表")
    try:
        response = requests.get(f"{BASE_URL}/products", headers=headers, params={"page": 1, "size": 3}, timeout=10)
        print(f"状态码: {response.status_code}")
        if response.text:
            data = response.json()
            print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)[:500]}...")
    except Exception as e:
        print(f"错误: {e}")

    # 测试秒杀列表
    print("\n" + "-" * 40)
    print("测试秒杀列表")
    try:
        response = requests.get(f"{BASE_URL}/seckills", headers=headers, params={"page": 1, "size": 3}, timeout=10)
        print(f"状态码: {response.status_code}")
        if response.text:
            data = response.json()
            print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)[:500]}...")
    except Exception as e:
        print(f"错误: {e}")

if __name__ == "__main__":
    token = test_login_direct()
    if token:
        print(f"\n成功获取 Token!")
        test_with_token(token)
    else:
        print("\n登录失败!")
