import requests
import json

# 测试管理端登录功能
BASE_URL = "http://localhost:8080/api/admin"

def test_login():
    print("=" * 50)
    print("测试管理端登录功能")
    print("=" * 50)

    # 测试数据
    test_cases = [
        {"username": "admin", "password": "admin123", "expected": "success"},
        {"username": "admin", "password": "wrongpassword", "expected": "fail"},
        {"username": "nonexistent", "password": "admin123", "expected": "fail"},
    ]

    for i, test in enumerate(test_cases, 1):
        print(f"\n测试 {i}: {test['username']} / {test['password']}")
        print("-" * 40)

        try:
            response = requests.post(
                f"{BASE_URL}/auth/login",
                json={
                    "username": test["username"],
                    "password": test["password"]
                },
                headers={"Content-Type": "application/json"},
                timeout=10
            )

            print(f"状态码: {response.status_code}")

            if response.text:
                try:
                    data = response.json()
                    print(f"响应: {json.dumps(data, ensure_ascii=False, indent=2)}")
                except json.JSONDecodeError:
                    print(f"响应 (非JSON): {response.text[:200]}")
            else:
                print("响应为空")

        except requests.exceptions.Timeout:
            print("请求超时!")
        except requests.exceptions.ConnectionError as e:
            print(f"连接错误: {e}")
        except Exception as e:
            print(f"错误: {e}")

if __name__ == "__main__":
    test_login()
