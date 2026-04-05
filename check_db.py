import pymysql
conn = pymysql.connect(host='mysql', user='root', password='root123', database='ecommerce', charset='utf8mb4')
cursor = conn.cursor()
cursor.execute("SELECT id, username, password FROM admin_user WHERE username='admin'")
row = cursor.fetchone()
print(f"id={row[0]}, username={row[1]}, password={row[2]}")
conn.close()
