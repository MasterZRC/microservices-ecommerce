import bcrypt
stored = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi"
test = "admin123"
result = bcrypt.checkpw(test.encode(), stored.encode())
print(f"Direct check: {result}")

# Also try decoding as different encodings
for enc in ['utf-8', 'latin-1', 'ascii']:
    try:
        result2 = bcrypt.checkpw(test.encode(), stored.encode(enc))
        print(f"With {enc}: {result2}")
    except Exception as e:
        print(f"With {enc}: error - {e}")

# Generate and verify
fresh = bcrypt.hashpw(b"admin123", bcrypt.gensalt(rounds=10))
print(f"Fresh hash: {fresh}")
verify = bcrypt.checkpw(b"admin123", fresh)
print(f"Verify fresh: {verify}")
