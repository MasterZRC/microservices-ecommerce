import urllib.request, json
r = urllib.request.urlopen("http://admin-service:8006/actuator/env", timeout=10)
d = json.loads(r.read())
for p in d.get('propertySources', []):
    name = p.get('name', '')
    pv = p.get('property', {}).get('value', '')
    if any(x in name.lower() for x in ['datasource', 'jdbc', 'db-', 'url', 'mysql']) or any(x in str(pv).lower() for x in ['jdbc', 'mysql', 'ecommerce']):
        print(name, ':', pv)
