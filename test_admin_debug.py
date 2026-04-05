import requests
import json

# Get token first
login_url = "http://localhost:8080/api/admin/auth/login"
login_data = {"username": "admin", "password": "admin123"}
response = requests.post(login_url, json=login_data, timeout=30)
print(f"Login Status: {response.status_code}")
result = response.json()
print(f"Login Response: {json.dumps(result, indent=2, ensure_ascii=False)}")

if result.get("code") == 200:
    token = result["data"]["token"]

    # Test direct access to admin-service (bypass gateway)
    print("\n" + "=" * 50)
    print("Test: Direct access to admin-service (port 8006)")
    print("=" * 50)
    headers = {"Authorization": f"Bearer {token}"}
    dashboard_url = "http://localhost:8006/api/admin/dashboard/stats"
    try:
        response = requests.get(dashboard_url, headers=headers, timeout=10)
        print(f"Status: {response.status_code}")
        print(f"Response: {response.text[:500]}")
    except Exception as e:
        print(f"Error: {e}")

    # Test through gateway
    print("\n" + "=" * 50)
    print("Test: Through API Gateway")
    print("=" * 50)
    headers = {"Authorization": f"Bearer {token}"}
    dashboard_url = "http://localhost:8080/api/admin/dashboard/stats"
    response = requests.get(dashboard_url, headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    print(f"Response: {response.text[:500]}")
