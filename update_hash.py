import bcrypt

# Generate fresh BCrypt hash and update DB
password = b'admin123'
new_hash = bcrypt.hashpw(password, bcrypt.gensalt(rounds=10)).decode()
print(f"New BCrypt hash for admin123: {new_hash}")

# Verify we can check it
result = bcrypt.checkpw(password, new_hash.encode())
print(f"Verify new hash: {result}")

# Check the stored hash
stored = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi"
stored_result = bcrypt.checkpw(password, stored.encode())
print(f"Verify stored hash: {stored_result}")
