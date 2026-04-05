import requests
import json
import base64

GATEWAY_URL = "http://localhost:8080"

# 解码 JWT
token = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoic3VwZXJfYWRtaW4iLCJhZG1pbklkIjoxLCJ1c2VybmFtZSI6ImFkbWluIiwic3ViIjoiYWRtaW4iLCJpYXQiOjE3NzUzMDg5MzUsImV4cCI6MTc3NTM5NTMzNX0.Qeza9mkPs0B-zL_PlwaxA3_cDLiHNJ7t9NxUWnn24sg"

parts = token.split(".")
header = json.loads(base64.urlsafe_b64decode(parts[0] + "=="))
payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=="))

print("Header:", header)
print("Payload:", payload)

# 尝试使用不同的用户服务 token
# 1. 尝试登录普通用户服务获取 token
print("\n" + "=" * 60)
print("尝试登录用户服务获取 token")
print("=" * 60)

response = requests.post(
    f"{GATEWAY_URL}/api/user/login",
    json={"username": "test", "password": "test123"},
    headers={"Content-Type": "application/json"},
    timeout=5
)

print(f"状态码: {response.status_code}")
print(f"响应: {response.text[:500] if response.text else '(empty)'}")
