import requests
import json

url = "http://localhost:8080/api/admin/auth/login"
headers = {"Content-Type": "application/json"}
data = {"username": "admin", "password": "admin123"}

try:
    response = requests.post(url, headers=headers, json=data, timeout=30)
    print(f"Status: {response.status_code}")
    print(f"Response: {response.text}")
except Exception as e:
    print(f"Error: {e}")
