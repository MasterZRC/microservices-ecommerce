import urllib.request, json

print("=== DeepFM-Attention 增强推荐链路测试 ===")
try:
    resp = urllib.request.urlopen('http://localhost:8080/api/recommendation/personal/products?userId=1001&limit=10', timeout=60)
    result = json.loads(resp.read().decode())
    products = result.get('products', [])
    print(f'推荐结果共 {len(products)} 个商品:')
    print()
    for i, p in enumerate(products[:10]):
        print(f'{i+1}. ID={p.get("id")}, Score={p.get("score", 0):.4f}, Method={p.get("method", "N/A")}')
    print()

    # 检查是否有 diversity info
    if 'diversity' in result:
        print('多样性:', result['diversity'])
    if 'abTest' in result:
        print('A/B Test:', result['abTest'])
    if 'scene' in result:
        print('场景:', result['scene'])
    if 'timestamp' in result:
        print('生成时间:', result['timestamp'])

except urllib.error.HTTPError as e:
    body = e.read().decode()
    print('HTTP Error:', e.code)
    print('Body:', body[:500])
except Exception as e:
    print('Error:', type(e).__name__, str(e)[:200])
