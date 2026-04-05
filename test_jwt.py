import requests
import json
import jwt  # PyJWT

# JWT secret - same as .env
SECRET = "mySecretKeyForEcommerceGraduationProject2024"

# Get token
login_url = "http://localhost:8080/api/admin/auth/login"
login_data = {"username": "admin", "password": "admin123"}
response = requests.post(login_url, json=login_data, timeout=30)
result = response.json()
print(f"Login Status: {response.status_code}, Code: {result.get('code')}")

if result.get("code") == 200:
    token = result["data"]["token"]
    print(f"\nToken: {token[:50]}...")

    # Decode token (without verification)
    decoded = jwt.decode(token, options={"verify_signature": False})
    print(f"\nDecoded Token Payload: {json.dumps(decoded, indent=2)}")

    # Try to verify with same secret
    try:
        verified = jwt.decode(token, SECRET, algorithms=["HS256"])
        print(f"\nToken verified successfully with secret!")
        print(f"Verified Payload: {json.dumps(verified, indent=2)}")
    except Exception as e:
        print(f"\nToken verification failed: {e}")

    # Try to verify with longer secret (padding)
    longer_secret = SECRET + "=" * (32 - len(SECRET) % 32) if len(SECRET) % 32 != 0 else SECRET
    try:
        verified = jwt.decode(token, longer_secret, algorithms=["HS256"])
        print(f"\nToken verified with padded secret!")
    except Exception as e:
        print(f"\nToken verification with padded secret failed: {e}")
