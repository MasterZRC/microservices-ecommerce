import urllib.request
import urllib.parse
import json
import time

BASE_URL = "http://localhost:8004/api"

def make_request(url, data=None, method=None):
    headers = {"Content-Type": "application/json"}
    if data:
        body = json.dumps(data).encode()
        req = urllib.request.Request(url, data=body, headers=headers, method="POST" if method == "POST" else "GET")
    else:
        req = urllib.request.Request(url, headers=headers, method=method or "GET")
    try:
        resp = urllib.request.urlopen(req, timeout=30)
        return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return {"error": e.code, "body": e.read().decode()}
    except Exception as e:
        return {"error": str(e)}

# Test 1: Record a cart behavior for user 3 on product 319
print("=" * 60)
print("Test 1: Record cart behavior")
print("=" * 60)
url = f"{BASE_URL}/recommendation/behavior"
params = urllib.parse.urlencode({"userId": 3, "productId": 319, "behaviorType": "cart"})
full_url = f"{url}?{params}"
result = make_request(full_url, method="POST")
print(f"Record behavior result: {result}")

# Test 2: Get personalized recommendations
print()
print("=" * 60)
print("Test 2: Get personalized recommendations for user 3")
print("=" * 60)
url = f"{BASE_URL}/recommendation/personal/products?userId=3&limit=20"
result = make_request(url)
if "error" in result:
    print(f"Error: {result}")
else:
    products = result.get("products", [])
    print(f"Got {len(products)} products")
    print()
    # Analyze recommendation reasons
    reasons = {}
    for p in products:
        reason = p.get("recommendation_reason", "N/A")
        reason_short = reason.split("，")[0] if reason else "N/A"
        reasons[reason_short] = reasons.get(reason_short, 0) + 1
    print("Recommendation reason distribution:")
    for reason, count in sorted(reasons.items(), key=lambda x: -x[1]):
        print(f"  {reason}: {count}")
    print()
    print("Top 10 products:")
    for i, p in enumerate(products[:10]):
        reason = p.get("recommendation_reason", "N/A")
        score = p.get("score", 0)
        cf_score = p.get("cf_score", 0)
        print(f"  {i+1}. ID={p.get('id')}, Name={p.get('name','?')[:25]}, "
              f"reason={reason[:20]}, score={score:.4f}, cf_score={cf_score:.4f}")

# Test 3: Check user 3's recent behaviors
print()
print("=" * 60)
print("Test 3: Check user 3's recent behaviors in DB (via recordBehavior response)")
print("=" * 60)
# We'll check by looking at whether the recommendation changed after recording
