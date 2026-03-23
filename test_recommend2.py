import urllib.request, json

# 先测试从宿主机直接调用 product-service batch
print("=== Test product-service batch from host ===")
try:
    resp = urllib.request.urlopen('http://localhost:8002/api/product/batch?ids=1,2,3', timeout=30)
    print('OK:', resp.read().decode()[:200])
except Exception as e:
    print('Error:', type(e).__name__, str(e)[:200])

print()

# 测试推荐服务自身的 /personal endpoint (不经过网关)
print("=== Test recommendation-service /personal from host ===")
try:
    resp = urllib.request.urlopen('http://localhost:8004/api/recommendation/personal?userId=1001&limit=5', timeout=60)
    result = json.loads(resp.read().decode())
    print('OK - recommendations:', len(result.get('recommendations', [])))
except urllib.error.HTTPError as e:
    print('HTTP Error:', e.code, e.read().decode()[:300])
except Exception as e:
    print('Error:', type(e).__name__, str(e)[:200])
