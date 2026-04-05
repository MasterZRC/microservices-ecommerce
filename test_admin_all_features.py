import requests
import json

BASE_URL = "http://localhost:8080"

def run_tests():
    print("=" * 70)
    print("管理端功能测试")
    print("=" * 70)

    # 1. 登录
    print("\n[1] 登录测试...")
    login_url = f"{BASE_URL}/api/admin/auth/login"
    login_data = {"username": "admin", "password": "admin123"}
    response = requests.post(login_url, json=login_data, timeout=30)
    print(f"    状态码: {response.status_code}")

    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            print("    结果: 登录成功")
            token = result["data"]["token"]
            headers = {"Authorization": f"Bearer {token}"}
        else:
            print(f"    结果: 登录失败 - {result.get('message')}")
            return
    else:
        print(f"    结果: 登录失败")
        return

    # 2. 仪表盘统计
    print("\n[2] 仪表盘统计测试...")
    dashboard_url = f"{BASE_URL}/api/admin/dashboard/stats"
    response = requests.get(dashboard_url, headers=headers, timeout=30)
    print(f"    状态码: {response.status_code}")
    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            data = result["data"]
            print(f"    结果: 成功")
            print(f"    - 用户数: {data.get('userCount', 'N/A')}")
            print(f"    - 商品数: {data.get('productCount', 'N/A')}")
            print(f"    - 订单数: {data.get('orderCount', 'N/A')}")
            print(f"    - 今日订单: {data.get('todayOrderCount', 'N/A')}")
            print(f"    - 总销售额: {data.get('totalSales', 'N/A')}")
        else:
            print(f"    结果: 失败 - {result.get('message')}")
    else:
        print(f"    结果: 请求失败")

    # 3. 最新订单
    print("\n[3] 最新订单测试...")
    recent_orders_url = f"{BASE_URL}/api/admin/dashboard/recent-orders"
    response = requests.get(recent_orders_url, headers=headers, timeout=30)
    print(f"    状态码: {response.status_code}")
    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            print(f"    结果: 成功")
            orders = result.get("data", [])
            print(f"    - 订单数量: {len(orders)}")
            if orders:
                print(f"    - 最新订单: {orders[0].get('orderNo', 'N/A')}")
        else:
            print(f"    结果: 失败 - {result.get('message')}")

    # 4. 订单列表
    print("\n[4] 订单列表测试...")
    orders_url = f"{BASE_URL}/api/admin/orders"
    response = requests.get(orders_url, headers=headers, timeout=30)
    print(f"    状态码: {response.status_code}")
    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            print(f"    结果: 成功")
            records = result.get("data", {}).get("records", [])
            print(f"    - 订单数量: {len(records)}")
        else:
            print(f"    结果: 失败 - {result.get('message')}")

    # 5. 商品列表
    print("\n[5] 商品列表测试...")
    products_url = f"{BASE_URL}/api/admin/products"
    response = requests.get(products_url, headers=headers, timeout=30)
    print(f"    状态码: {response.status_code}")
    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            print(f"    结果: 成功")
            records = result.get("data", {}).get("records", [])
            print(f"    - 商品数量: {len(records)}")
        else:
            print(f"    结果: 失败 - {result.get('message')}")

    # 6. 秒杀列表 - 注意：路径是 /api/admin/seckill/activities (单数)
    print("\n[6] 秒杀列表测试...")
    seckill_url = f"{BASE_URL}/api/admin/seckill/activities"
    response = requests.get(seckill_url, headers=headers, timeout=30)
    print(f"    状态码: {response.status_code}")
    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            print(f"    结果: 成功")
            records = result.get("data", {}).get("records", [])
            print(f"    - 秒杀活动数量: {len(records)}")
        else:
            print(f"    结果: 失败 - {result.get('message')}")
    else:
        print(f"    结果: 请求失败, 响应: {response.text[:200] if response.text else 'EMPTY'}")

    # 7. 创建秒杀活动
    print("\n[7] 创建秒杀活动测试...")
    from datetime import datetime, timedelta
    seckill_create_url = f"{BASE_URL}/api/admin/seckill/activities"
    now = datetime.now()
    seckill_data = {
        "productId": 319,  # Bose QuietComfort Earbuds II
        "name": "测试秒杀活动",
        "seckillPrice": 1299.00,
        "stock": 50,
        "startTime": (now + timedelta(hours=1)).isoformat(),
        "endTime": (now + timedelta(hours=25)).isoformat(),
        "status": 1
    }
    response = requests.post(seckill_create_url, json=seckill_data, headers=headers, timeout=30)
    print(f"    状态码: {response.status_code}")
    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            print(f"    结果: 创建成功!")
            print(f"    - 秒杀ID: {result['data'].get('id')}")
            print(f"    - 活动名称: {result['data'].get('name')}")
            print(f"    - 秒杀价格: {result['data'].get('seckillPrice')}")
            print(f"    - 库存: {result['data'].get('stock')}")
        else:
            print(f"    结果: 业务失败 - {result.get('message')}")
            print(f"    响应: {response.text}")
    else:
        print(f"    结果: 请求失败")
        print(f"    响应: {response.text[:500] if response.text else 'EMPTY'}")

    # 8. 再次获取秒杀列表验证
    print("\n[8] 验证秒杀列表...")
    response = requests.get(f"{BASE_URL}/api/admin/seckill/activities", headers=headers, timeout=30)
    if response.status_code == 200:
        result = response.json()
        if result.get("code") == 200:
            records = result.get("data", {}).get("records", [])
            print(f"    秒杀活动数量: {len(records)}")
            for r in records[:3]:
                print(f"    - ID:{r.get('id')} | {r.get('name')} | 状态:{r.get('statusName')} | 价格:{r.get('seckillPrice')}")

    print("\n" + "=" * 70)
    print("测试完成!")
    print("=" * 70)

if __name__ == "__main__":
    run_tests()
