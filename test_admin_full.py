import requests
import json

BASE_URL = "http://localhost:8080"

# Test 1: Login
print("=" * 50)
print("Test 1: Admin Login")
print("=" * 50)
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
    print("\n" + "=" * 50)
    print("Test 2: Dashboard Stats")
    print("=" * 50)
    dashboard_url = f"{BASE_URL}/api/admin/dashboard/stats"
    response = requests.get(dashboard_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    print(f"Response: {response.text[:500]}")

    # Test 3: Order List
    print("\n" + "=" * 50)
    print("Test 3: Order List")
    print("=" * 50)
    orders_url = f"{BASE_URL}/api/admin/orders"
    response = requests.get(orders_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    print(f"Response: {response.text[:500]}")

    # Test 4: Product List
    print("\n" + "=" * 50)
    print("Test 4: Product List")
    print("=" * 50)
    products_url = f"{BASE_URL}/api/admin/products"
    response = requests.get(products_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    print(f"Response: {response.text[:500]}")

    # Test 5: Seckill List
    print("\n" + "=" * 50)
    print("Test 5: Seckill List")
    print("=" * 50)
    seckill_url = f"{BASE_URL}/api/admin/seckills"
    response = requests.get(seckill_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    print(f"Response: {response.text[:500]}")
else:
    print("Login failed!")
