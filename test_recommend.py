import urllib.request, json

url = 'http://localhost:8002/api/recommendation/personal/products?userId=1001&limit=10'
req = urllib.request.Request(url, headers={'Content-Type': 'application/json'})

try:
    resp = urllib.request.urlopen(req, timeout=30)
    result = json.loads(resp.read().decode())
    print('Java Recommendation API works!')
    products = result.get('products', [])
    print('Number of recommendations:', len(products))
    for i, prod in enumerate(products[:5]):
        print(f'  {i+1}. ID={prod.get("id")}, Name={prod.get("name","N/A")[:30]}, Score={prod.get("score", 0):.4f}, Method={prod.get("method", "N/A")}')
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print('HTTP Error:', e.code)
    print(body[:500])
except Exception as e:
    print('Error:', type(e).__name__, str(e))
