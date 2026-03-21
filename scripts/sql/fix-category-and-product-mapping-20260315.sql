USE ecommerce;

-- 1) 修复分类中文名称（解决前端分类乱码）
UPDATE category SET name = '电子产品', status = 1, sort = 1 WHERE id = 1;
UPDATE category SET name = '服装鞋包', status = 1, sort = 2 WHERE id = 2;
UPDATE category SET name = '食品生鲜', status = 1, sort = 3 WHERE id = 3;
UPDATE category SET name = '家用电器', status = 1, sort = 4 WHERE id = 4;
UPDATE category SET name = '家居日用', status = 1, sort = 5 WHERE id = 5;
UPDATE category SET name = '美妆个护', status = 1, sort = 6 WHERE id = 6;
UPDATE category SET name = '运动户外', status = 1, sort = 7 WHERE id = 7;
UPDATE category SET name = '母婴玩具', status = 1, sort = 8 WHERE id = 8;

-- 2) 先按现有 category_id 回填 category_name（保证名称一致）
UPDATE product p
JOIN category c ON p.category_id = c.id
SET p.category_name = c.name
WHERE p.status = 1;

-- 3) 基于商品名/品牌做一轮归类修复（可审阅后执行）
-- 3.1 电子产品
UPDATE product
SET category_id = 1, category_name = '电子产品'
WHERE status = 1
  AND (
    name REGEXP 'iPhone|AirPods|MacBook|iPad|Mate|Galaxy|Pixel|Watch|Earbuds|Tablet|Speaker|Keyboard|Mouse|PowerBank|Router|Monitor|Projector|Camera|DJI|GoPro|Kindle|ThinkPad|ROG|Type-C|DDR|SSD|Wi-?Fi|Lamy'
    OR brand IN ('Apple','Huawei','Xiaomi','Samsung','Sony','Lenovo','OPPO','vivo','JBL','Anker','DJI','Bose','Dell','HP','AOC','Keychron','TP-LINK','GoPro')
  );

-- 3.2 服装鞋包
UPDATE product
SET category_id = 2, category_name = '服装鞋包'
WHERE status = 1
  AND (
    name REGEXP 'TShirt|Hoodie|Jacket|Shorts|Joggers|Sneakers|CanvasShoes|Backpack|CrossbodyBag|Belt|Old Skool|Levi|HEATTECH|G-SHOCK|行李箱|太阳镜'
    OR brand IN ('Nike','Adidas','LiNing','ANTA','Uniqlo','Levis','Vans','Converse','Puma','New Balance','HLA','RayBan','Samsonite','Casio')
  );

-- 3.3 食品生鲜
UPDATE product
SET category_id = 3, category_name = '食品生鲜'
WHERE status = 1
  AND (
    name REGEXP 'Rice|Milk|Yogurt|Cheese|Salmon|Beef|Pork|Chicken|Egg|Fruit|Apple|Orange|Banana|Vegetable|Tea|Coffee|Snack|Chocolate|Biscuit|Noodle|Seafood|三文鱼|牛肉|鸡蛋|大米|水果|酸奶'
    OR brand IN ('Yili','Mengniu','Nestle','Nongfu Spring','Three Squirrels','百草味','良品铺子')
  );

-- 3.4 家用电器
UPDATE product
SET category_id = 4, category_name = '家用电器'
WHERE status = 1
  AND (
    name REGEXP 'TV|Refrigerator|Fridge|Washer|Dryer|Dishwasher|Air Conditioner|Purifier|Vacuum|Microwave|Rice Cooker|Kettle|Heater|Fan|Humidifier|Dehumidifier|Water Heater|Oven|Cooker|扫地机器人|空调|冰箱|洗衣机|油烟机|净水器'
    OR brand IN ('Haier','Midea','Gree','Hisense','TCL','Dyson','Roborock','小米','美的','海尔','格力')
  );

-- 3.5 家居日用
UPDATE product
SET category_id = 5, category_name = '家居日用'
WHERE status = 1
  AND (
    name REGEXP 'Towel|Toothbrush|Toothpaste|Detergent|Tissue|Mop|Broom|Trash Bag|Pillow|Quilt|Bedding|Pan|Pot|Storage|Shelf|Curtain|Desk Lamp|保温杯|毛巾|纸巾|洗洁精|收纳'
    OR brand IN ('MUJI','IKEA','NITORI','Lock&Lock','苏泊尔','炊大皇')
  );

-- 3.6 美妆个护
UPDATE product
SET category_id = 6, category_name = '美妆个护'
WHERE status = 1
  AND (
    name REGEXP 'Lipstick|Serum|Essence|Lotion|Cream|Sunscreen|Perfume|Makeup|Mask|Cleanser|Shampoo|Conditioner|Body Wash|口红|精华|面霜|香水|防晒|洗面奶'
    OR brand IN ('MAC','SK-II','YSL','Lancome','Shiseido','L''Oreal','Estee Lauder','欧莱雅','资生堂','兰蔻')
  );

-- 3.7 运动户外
UPDATE product
SET category_id = 7, category_name = '运动户外'
WHERE status = 1
  AND (
    name REGEXP 'Running|Basketball|Football|Tennis|Yoga|Dumbbell|Cycling|Bicycle|Camping|Tent|Hiking|Trekking|Swim|Goggles|Garmin|Manduka|Burton|Nalgene|Osprey|骑行|露营|帐篷|登山|瑜伽'
    OR brand IN ('Garmin','Black Diamond','Osprey','Manduka','Speedo','Burton','Nalgene','迪卡侬')
  );

-- 3.8 母婴玩具
UPDATE product
SET category_id = 8, category_name = '母婴玩具'
WHERE status = 1
  AND (
    name REGEXP 'Baby|Infant|Diaper|Stroller|FeedingBottle|Milk Powder|Toy|Puzzle|BuildingBlocks|PlushToy|StoryMachine|PlayMat|Pampers|Huggies|Pigeon|LEGO|FisherPrice|MeadJohnson|Feihe|婴儿|奶粉|尿不湿|玩具|积木'
    OR brand IN ('Pampers','Huggies','Pigeon','LEGO','FisherPrice','Babycare','MeadJohnson','Feihe','好奇','飞鹤')
  );

-- 4) 二次兜底：任何 category_name 与 id 对应不一致的，全部按 category_id 同步
UPDATE product p
JOIN category c ON p.category_id = c.id
SET p.category_name = c.name
WHERE p.status = 1
  AND (p.category_name IS NULL OR p.category_name <> c.name);

-- 5) 核验 SQL（执行后手动检查）
-- SELECT id, name FROM category ORDER BY id;
-- SELECT category_id, category_name, COUNT(*) AS cnt FROM product WHERE status = 1 GROUP BY category_id, category_name ORDER BY category_id;
-- SELECT id, name, category_id, category_name, brand FROM product WHERE status = 1 ORDER BY id LIMIT 200;
