import requests
import json
import threading
import time

# Get token first
login_url = "http://localhost:8080/api/admin/auth/login"
login_data = {"username": "admin", "password": "admin123"}
response = requests.post(login_url, json=login_data, timeout=30)
result = response.json()
print(f"Login Status: {response.status_code}, Code: {result.get('code')}")

if result.get("code") == 200:
    token = result["data"]["token"]
    print(f"Token: {token[:50]}...")

    # Test dashboard
    headers = {"Authorization": f"Bearer {token}"}

    print("\nTesting dashboard through gateway...")
    response = requests.get("http://localhost:8080/api/admin/dashboard/stats", headers=headers, timeout=30)
    print(f"Status: {response.status_code}")
    print(f"Headers: {dict(response.headers)}")
    print(f"Response: {response.text[:1000] if response.text else 'EMPTY'}")

    # Test direct to admin-service
    print("\nTesting dashboard direct to admin-service...")
    response = requests.get("http://localhost:8006/api/admin/dashboard/stats", headers=headers, timeout=10)
    print(f"Status: {response.status_code}")
    print(f"Headers: {dict(response.headers)}")
    print(f"Response: {response.text[:1000] if response.text else 'EMPTY'}")
