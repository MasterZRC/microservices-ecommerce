import requests
import json

BASE_URL = "http://localhost:8080/api/admin"

def get_token():
    """获取管理员token"""
    response = requests.post(
        f"{BASE_URL}/auth/login",
        json={"username": "admin", "password": "admin123"},
        headers={"Content-Type": "application/json"},
        timeout=10
    )
    if response.status_code == 200:
        data = response.json()
        if data.get("code") == 200:
            return data["data"]["token"]
    return None

def get_headers(token):
    """获取带token的请求头"""
    return {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}"
    }

def test_dashboard(token):
    """测试仪表盘功能"""
    print("\n" + "=" * 50)
    print("测试仪表盘功能")
    print("=" * 50)

    try:
        response = requests.get(
            f"{BASE_URL}/dashboard/stats",
            headers=get_headers(token),
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        data = response.json()
        print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)}")
    except Exception as e:
        print(f"错误: {e}")

def test_orders(token):
    """测试订单管理功能"""
    print("\n" + "=" * 50)
    print("测试订单管理功能")
    print("=" * 50)

    # 1. 获取订单列表
    print("\n1. 获取订单列表")
    try:
        response = requests.get(
            f"{BASE_URL}/orders",
            headers=get_headers(token),
            params={"page": 1, "size": 5},
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        data = response.json()
        print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)[:500]}...")

        # 获取第一个订单ID
        if data.get("code") == 200 and data.get("data", {}).get("records"):
            first_order = data["data"]["records"][0]
            order_id = first_order.get("id")
            print(f"\n订单总数: {data['data']['total']}, 第一页显示: {len(data['data']['records'])}")

            # 2. 获取订单详情
            print(f"\n2. 获取订单详情 (ID: {order_id})")
            response = requests.get(
                f"{BASE_URL}/orders/{order_id}",
                headers=get_headers(token),
                timeout=10
            )
            print(f"状态码: {response.status_code}")
            data = response.json()
            print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)[:500]}...")
    except Exception as e:
        print(f"错误: {e}")

def test_products(token):
    """测试商品管理功能"""
    print("\n" + "=" * 50)
    print("测试商品管理功能")
    print("=" * 50)

    # 1. 获取商品列表
    print("\n1. 获取商品列表")
    try:
        response = requests.get(
            f"{BASE_URL}/products",
            headers=get_headers(token),
            params={"page": 1, "size": 5},
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        data = response.json()
        print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)[:500]}...")

        if data.get("code") == 200 and data.get("data", {}).get("records"):
            print(f"\n商品总数: {data['data']['total']}, 第一页显示: {len(data['data']['records'])}")
    except Exception as e:
        print(f"错误: {e}")

def test_seckill(token):
    """测试秒杀活动功能"""
    print("\n" + "=" * 50)
    print("测试秒杀活动功能")
    print("=" * 50)

    # 1. 获取秒杀活动列表
    print("\n1. 获取秒杀活动列表")
    try:
        response = requests.get(
            f"{BASE_URL}/seckills",
            headers=get_headers(token),
            params={"page": 1, "size": 5},
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        data = response.json()
        print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)[:500]}...")

        if data.get("code") == 200 and data.get("data", {}).get("records"):
            print(f"\n秒杀活动总数: {data['data']['total']}, 第一页显示: {len(data['data']['records'])}")
    except Exception as e:
        print(f"错误: {e}")

def test_admin_info(token):
    """测试获取管理员信息"""
    print("\n" + "=" * 50)
    print("测试获取管理员信息")
    print("=" * 50)

    try:
        response = requests.get(
            f"{BASE_URL}/auth/info",
            headers=get_headers(token),
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        data = response.json()
        print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)}")
    except Exception as e:
        print(f"错误: {e}")

if __name__ == "__main__":
    print("开始测试管理端功能...")
    token = get_token()
    if token:
        print(f"\n成功获取 Token: {token[:50]}...")

        # 测试各项功能
        test_admin_info(token)
        test_dashboard(token)
        test_orders(token)
        test_products(token)
        test_seckill(token)

        print("\n" + "=" * 50)
        print("所有测试完成!")
        print("=" * 50)
    else:
        print("无法获取 Token，测试终止")
