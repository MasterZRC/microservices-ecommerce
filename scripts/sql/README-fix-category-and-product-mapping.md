# 分类与商品归类修复（手动执行）

## 执行

在项目根目录执行：

```powershell
docker exec -i ecommerce-mysql mysql -uroot -proot123 ecommerce < scripts/sql/fix-category-and-product-mapping-20260315.sql
```

## 执行后核验

```powershell
docker exec ecommerce-mysql mysql -uroot -proot123 -D ecommerce -e "SELECT id,name FROM category ORDER BY id;"
docker exec ecommerce-mysql mysql -uroot -proot123 -D ecommerce -e "SELECT category_id,category_name,COUNT(*) AS cnt FROM product WHERE status=1 GROUP BY category_id,category_name ORDER BY category_id;"
```

## 建议先备份

```powershell
docker exec ecommerce-mysql mysqldump -uroot -proot123 ecommerce category product > scripts/sql/backup-category-product-before-fix.sql
```

## 快速回滚（仅回滚 category+product）

```powershell
docker exec -i ecommerce-mysql mysql -uroot -proot123 ecommerce < scripts/sql/backup-category-product-before-fix.sql
```
