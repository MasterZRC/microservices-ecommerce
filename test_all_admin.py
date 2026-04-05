import requests
import json

BASE_URL = "http://localhost:8080"

# Test 1: Login
print("=" * 60)
print("Test 1: Admin Login")
print("=" * 60)
login_url = f"{BASE_URL}/api/admin/auth/login"
login_data = {"username": "admin", "password": "admin123"}
response = requests.post(login_url, json=login_data, timeout=30)
print(f"Status: {response.status_code}")
result = response.json()
print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")

if result.get("code") == 200:
    token = result["data"]["token"]
    headers = {"Authorization": f"Bearer {token}"}

    # Test 2: Dashboard Stats
    print("\n" + "=" * 60)
    print("Test 2: Dashboard Stats")
    print("=" * 60)
    dashboard_url = f"{BASE_URL}/api/admin/dashboard/stats"
    response = requests.get(dashboard_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    if response.text:
        print(f"Response: {response.text[:500]}")
    else:
        print("Response: EMPTY")

    # Test 3: Order List
    print("\n" + "=" * 60)
    print("Test 3: Order List")
    print("=" * 60)
    orders_url = f"{BASE_URL}/api/admin/orders"
    response = requests.get(orders_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    if response.text:
        print(f"Response: {response.text[:500]}")
    else:
        print("Response: EMPTY")

    # Test 4: Product List
    print("\n" + "=" * 60)
    print("Test 4: Product List")
    print("=" * 60)
    products_url = f"{BASE_URL}/api/admin/products"
    response = requests.get(products_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    if response.text:
        print(f"Response: {response.text[:500]}")
    else:
        print("Response: EMPTY")

    # Test 5: Seckill List
    print("\n" + "=" * 60)
    print("Test 5: Seckill List")
    print("=" * 60)
    seckill_url = f"{BASE_URL}/api/admin/seckills"
    response = requests.get(seckill_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    if response.text:
        print(f"Response: {response.text[:500]}")
    else:
        print("Response: EMPTY")

    print("\n" + "=" * 60)
    print("ALL TESTS COMPLETED!")
    print("=" * 60)
else:
    print("Login failed!")
