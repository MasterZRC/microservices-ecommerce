"""
Recommendation System Data Generator
Generates realistic user and behavior data for DeepFM training

Usage:
    python data_generator.py --generate-all      # Generate all data
    python data_generator.py --generate-users    # Only users
    python data_generator.py --generate-behaviors # Only behaviors
"""

import pymysql
import random
import argparse
from datetime import datetime, timedelta
from typing import List, Dict, Tuple
import time

# ========== Configuration ==========
DB_CONFIG = {
    "host": "localhost",
    "port": 3306,
    "user": "root",
    "password": "root123",
    "database": "ecommerce",
    "charset": "utf8mb4"
}

# User type distribution (simulating real users)
USER_DISTRIBUTION = {
    "high_active": 0.15,      # 15% high active users
    "medium_active": 0.30,   # 30% medium active users
    "low_active": 0.35,      # 35% low active users
    "silent": 0.20            # 20% silent users
}

# User behavior parameters per type
USER_BEHAVIOR_PARAMS = {
    "high_active": {"min": 150, "max": 300, "buy_rate": 0.05, "cart_rate": 0.12, "favorite_rate": 0.18},
    "medium_active": {"min": 50, "max": 150, "buy_rate": 0.04, "cart_rate": 0.10, "favorite_rate": 0.15},
    "low_active": {"min": 15, "max": 50, "buy_rate": 0.03, "cart_rate": 0.08, "favorite_rate": 0.10},
    "silent": {"min": 3, "max": 15, "buy_rate": 0.02, "cart_rate": 0.05, "favorite_rate": 0.05}
}

# Behavior weights (for recommendation scoring)
BEHAVIOR_WEIGHTS = {
    "view": 1,
    "click": 2,
    "cart": 4,
    "favorite": 5,
    "buy": 8
}


class DataGenerator:
    """Data generator class"""

    def __init__(self, db_config: Dict = None):
        self.db_config = db_config or DB_CONFIG
        self.conn = None
        self.products = []
        self.categories = []
        self.users = []

    def connect(self):
        """Connect to database"""
        self.conn = pymysql.connect(**self.db_config)
        print(f"[OK] Database connected: {self.db_config['host']}:{self.db_config['port']}")

    def close(self):
        """Close connection"""
        if self.conn:
            self.conn.close()
            print("[OK] Database connection closed")

    def load_products(self) -> List[Dict]:
        """Load product data"""
        if self.products:
            return self.products

        cursor = self.conn.cursor(pymysql.cursors.DictCursor)
        cursor.execute("""
            SELECT id, category_id, category_name, brand, price
            FROM product
            WHERE status = 1
        """)
        self.products = cursor.fetchall()
        cursor.close()
        print(f"[INFO] Loaded {len(self.products)} products")
        return self.products

    def load_categories(self) -> List[Dict]:
        """Load category data"""
        if self.categories:
            return self.categories

        cursor = self.conn.cursor(pymysql.cursors.DictCursor)
        cursor.execute("SELECT id, name FROM category")
        self.categories = cursor.fetchall()
        cursor.close()
        print(f"[INFO] Loaded {len(self.categories)} categories")
        return self.categories

    def load_existing_users(self) -> List[int]:
        """Load existing user IDs"""
        cursor = self.conn.cursor()
        cursor.execute("SELECT id FROM user")
        user_ids = [row[0] for row in cursor.fetchall()]
        cursor.close()
        print(f"[INFO] Existing users: {len(user_ids)}")
        return user_ids

    def get_user_category_preference(self, user_id: int) -> List[int]:
        """Get user's category preference"""
        cursor = self.conn.cursor(pymysql.cursors.DictCursor)
        cursor.execute("""
            SELECT pb.category_id, COUNT(*) as cnt
            FROM user_behavior ub
            JOIN product pb ON ub.product_id = pb.id
            WHERE ub.user_id = %s AND ub.behavior_type IN ('buy', 'cart', 'favorite')
            GROUP BY pb.category_id
            ORDER BY cnt DESC
            LIMIT 3
        """, (user_id,))
        preferences = cursor.fetchall()
        cursor.close()

        if not preferences:
            return random.sample(range(1, len(self.categories) + 1), min(3, len(self.categories)))

        return [p["category_id"] for p in preferences]

    def generate_users(self, num_users: int = 200) -> int:
        """Generate new users"""
        print(f"\n{'='*50}")
        print(f"Generating {num_users} new users...")
        print('='*50)

        existing_users = self.load_existing_users()
        start_id = max(existing_users) + 1 if existing_users else 1

        cursor = self.conn.cursor()

        usernames = []
        for i in range(num_users):
            username = f"user_{int(time.time())}_{i}"
            # password: simple hash for testing
            password = f"password_{int(time.time())}_{i}"
            usernames.append((username, password, f"{username}@example.com", 1))

        try:
            cursor.executemany(
                "INSERT INTO user (username, password, email, status) VALUES (%s, %s, %s, %s)",
                usernames
            )
            self.conn.commit()

            cursor.execute("SELECT LAST_INSERT_ID()")
            first_id = cursor.fetchone()[0]
            generated_ids = list(range(first_id, first_id + num_users))

            print(f"[OK] Generated {num_users} users, ID range: {generated_ids[0]} ~ {generated_ids[-1]}")
            return num_users

        except Exception as e:
            self.conn.rollback()
            print(f"[ERROR] Failed to generate users: {e}")
            return 0
        finally:
            cursor.close()

    def generate_behaviors(self, num_new_users: int = 200,
                          start_user_id: int = None,
                          days_back: int = 30) -> Tuple[int, int]:
        """Generate user behavior data"""
        print(f"\n{'='*50}")
        print(f"Generating user behavior data...")
        print('='*50)

        self.load_products()
        self.load_categories()

        if not self.products:
            print("[ERROR] No products available")
            return 0, 0

        cursor = self.conn.cursor(pymysql.cursors.DictCursor)
        cursor.execute("SELECT id FROM user WHERE status = 1")
        all_users = [row["id"] for row in cursor.fetchall()]

        if start_user_id:
            new_users = list(range(start_user_id, start_user_id + num_new_users))
            all_users.extend(new_users)

        cursor.close()

        print(f"[INFO] Total users for behavior generation: {len(all_users)}")

        total_behaviors = 0
        total_purchases = 0
        behavior_records = []

        for idx, user_id in enumerate(all_users):
            behaviors, user_purchases = self._generate_user_behavior(user_id, days_back)
            behavior_records.extend(behaviors)
            total_behaviors += len(behaviors)
            total_purchases += user_purchases

            if len(behavior_records) >= 5000:
                self._batch_insert_behaviors(behavior_records)
                print(f"  Progress: {idx + 1}/{len(all_users)} users, {total_behaviors} behaviors inserted...")
                behavior_records = []

        if behavior_records:
            self._batch_insert_behaviors(behavior_records)

        print(f"\n[OK] Behavior data generation complete!")
        print(f"   - Total behaviors: {total_behaviors}")
        print(f"   - Total purchases: {total_purchases}")
        if total_behaviors > 0:
            print(f"   - Purchase conversion rate: {total_purchases/total_behaviors*100:.2f}%")

        return total_behaviors, total_purchases

    def _generate_user_behavior(self, user_id: int, days_back: int) -> Tuple[List[Tuple], int]:
        """Generate behavior for single user"""
        user_type = self._determine_user_type()
        params = USER_BEHAVIOR_PARAMS[user_type]

        num_behaviors = random.randint(params["min"], params["max"])

        if random.random() > 0.3 or len(self.users) == 0:
            prefer_categories = random.sample(
                range(1, len(self.categories) + 1),
                random.randint(1, 3)
            )
        else:
            prefer_categories = self.get_user_category_preference(user_id)

        base_time = datetime.now() - timedelta(days=days_back)

        behaviors = []
        purchases = 0

        for _ in range(num_behaviors):
            rand = random.random()
            if rand < params["buy_rate"]:
                behavior_type = "buy"
            elif rand < params["buy_rate"] + params["cart_rate"]:
                behavior_type = "cart"
            elif rand < params["buy_rate"] + params["cart_rate"] + params["favorite_rate"]:
                behavior_type = "favorite"
            elif rand < 0.6:
                behavior_type = "click"
            else:
                behavior_type = "view"

            product = self._select_product(prefer_categories)
            if not product:
                continue

            days_offset = random.betavariate(2, 1) * days_back
            seconds_offset = random.randint(0, 86399)
            behavior_time = base_time + timedelta(days=days_offset, seconds=seconds_offset)

            score = self._calculate_behavior_score(behavior_type)

            behaviors.append((
                user_id,
                product["id"],
                behavior_type,
                score,
                behavior_time
            ))

            if behavior_type == "buy":
                purchases += 1

        return behaviors, purchases  # return list and count

    def _determine_user_type(self) -> str:
        """Determine user type by distribution"""
        rand = random.random()
        cumulative = 0
        for user_type, ratio in USER_DISTRIBUTION.items():
            cumulative += ratio
            if rand < cumulative:
                return user_type
        return "low_active"

    def _select_product(self, prefer_categories: List[int]) -> Dict:
        """Select product based on user preference"""
        if random.random() < 0.7 and prefer_categories:
            prefer_products = [
                p for p in self.products
                if p["category_id"] in prefer_categories
            ]
            if prefer_products:
                return random.choice(prefer_products)

        return random.choice(self.products)

    def _calculate_behavior_score(self, behavior_type: str) -> float:
        """Calculate behavior score"""
        base_score = BEHAVIOR_WEIGHTS.get(behavior_type, 1)
        return round(base_score * random.uniform(0.8, 1.2), 2)

    def _batch_insert_behaviors(self, behaviors: List[Tuple]):
        """Batch insert behaviors"""
        cursor = self.conn.cursor()
        try:
            cursor.executemany("""
                INSERT INTO user_behavior
                (user_id, product_id, behavior_type, score, create_time)
                VALUES (%s, %s, %s, %s, %s)
            """, behaviors)
            self.conn.commit()
        except Exception as e:
            self.conn.rollback()
            print(f"[WARN] Batch insert failed: {e}")
            for behavior in behaviors:
                try:
                    cursor.execute("""
                        INSERT INTO user_behavior
                        (user_id, product_id, behavior_type, score, create_time)
                        VALUES (%s, %s, %s, %s, %s)
                    """, behavior)
                    self.conn.commit()
                except:
                    pass
        finally:
            cursor.close()

    def print_statistics(self):
        """Print data statistics"""
        print(f"\n{'='*50}")
        print("Data Statistics")
        print('='*50)

        cursor = self.conn.cursor(pymysql.cursors.DictCursor)

        cursor.execute("SELECT COUNT(*) as cnt FROM user WHERE status = 1")
        user_count = cursor.fetchone()["cnt"]
        print(f"\n[INFO] Total users: {user_count}")

        cursor.execute("""
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN behavior_type = 'view' THEN 1 ELSE 0 END) as views,
                SUM(CASE WHEN behavior_type = 'click' THEN 1 ELSE 0 END) as clicks,
                SUM(CASE WHEN behavior_type = 'cart' THEN 1 ELSE 0 END) as carts,
                SUM(CASE WHEN behavior_type = 'favorite' THEN 1 ELSE 0 END) as favorites,
                SUM(CASE WHEN behavior_type = 'buy' THEN 1 ELSE 0 END) as buys
            FROM user_behavior
        """)
        stats = cursor.fetchone()
        print(f"\n[STAT] Behavior Statistics:")
        print(f"   - Total behaviors: {stats['total']}")
        print(f"   - Views: {stats['views']} ({stats['views']/stats['total']*100:.1f}%)")
        print(f"   - Clicks: {stats['clicks']} ({stats['clicks']/stats['total']*100:.1f}%)")
        print(f"   - Cart: {stats['carts']} ({stats['carts']/stats['total']*100:.1f}%)")
        print(f"   - Favorites: {stats['favorites']} ({stats['favorites']/stats['total']*100:.1f}%)")
        print(f"   - Purchases: {stats['buys']} ({stats['buys']/stats['total']*100:.1f}%)")

        cursor.execute("""
            SELECT user_id, COUNT(*) as cnt
            FROM user_behavior
            GROUP BY user_id
            ORDER BY cnt DESC
            LIMIT 5
        """)
        print(f"\n[TOP] Top 5 Active Users:")
        for row in cursor.fetchall():
            print(f"   - User {row['user_id']}: {row['cnt']} behaviors")

        cursor.execute("""
            SELECT p.category_name, COUNT(*) as cnt
            FROM user_behavior ub
            JOIN product p ON ub.product_id = p.id
            WHERE ub.behavior_type = 'buy'
            GROUP BY p.category_name
            ORDER BY cnt DESC
        """)
        print(f"\n[BUY] Category Purchase Distribution:")
        for row in cursor.fetchall():
            print(f"   - {row['category_name']}: {row['cnt']} purchases")

        cursor.close()


def main():
    parser = argparse.ArgumentParser(description="Recommendation System Data Generator")
    parser.add_argument("--generate-all", action="store_true", help="Generate all data")
    parser.add_argument("--generate-users", action="store_true", help="Only generate users")
    parser.add_argument("--generate-behaviors", action="store_true", help="Only generate behaviors")
    parser.add_argument("--num-users", type=int, default=200, help="Number of users to generate (default: 200)")
    parser.add_argument("--days-back", type=int, default=30, help="Days to look back (default: 30)")

    args = parser.parse_args()

    generator = DataGenerator()

    try:
        generator.connect()

        if args.generate_all:
            num_users = generator.generate_users(args.num_users)

            cursor = generator.conn.cursor()
            cursor.execute("SELECT LAST_INSERT_ID()")
            start_id = cursor.fetchone()[0]
            cursor.close()

            generator.generate_behaviors(
                num_new_users=num_users,
                start_user_id=start_id,
                days_back=args.days_back
            )

        elif args.generate_users:
            generator.generate_users(args.num_users)

        elif args.generate_behaviors:
            cursor = generator.conn.cursor()
            cursor.execute("SELECT MAX(id) FROM user")
            max_id = cursor.fetchone()[0] or 1
            cursor.close()

            print("\n[WARN] This will regenerate behavior data for ALL users")

            confirm = input("Confirm to clear user_behavior table? (y/N): ")
            if confirm.lower() == 'y':
                cursor = generator.conn.cursor()
                cursor.execute("DELETE FROM user_behavior")
                generator.conn.commit()
                cursor.close()
                print("[OK] user_behavior table cleared")

                generator.generate_behaviors(
                    num_new_users=0,
                    days_back=args.days_back
                )
        else:
            print("Please specify operation:")
            print("  --generate-all       Generate all data")
            print("  --generate-users     Only users")
            print("  --generate-behaviors Only behaviors")

        generator.print_statistics()

    finally:
        generator.close()


if __name__ == "__main__":
    main()
