import bcrypt
password = b'admin123'
hash = bcrypt.hashpw(password, bcrypt.gensalt(rounds=10)).decode()
print(f"BCrypt hash for admin123: {hash}")
