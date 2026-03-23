import urllib.request, json

print("=== Test via API Gateway with correct path ===")
try:
    resp = urllib.request.urlopen('http://localhost:8080/api/recommendation/personal/products?userId=1001&limit=5', timeout=60)
    result = json.loads(resp.read().decode())
    print('OK!')
    print('Products:', len(result.get('products', [])))
    for p in result.get('products', [])[:3]:
        print(f'  ID={p.get("id")}, Name={p.get("name","N/A")[:30]}, Score={p.get("score", 0):.4f}')
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print('HTTP Error:', e.code)
    print('Body:', body[:500])
except Exception as e:
    print('Error:', type(e).__name__, str(e)[:200])
