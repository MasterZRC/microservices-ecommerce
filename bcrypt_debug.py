import bcrypt
import sys

print(f"Python: {sys.version}")
print(f"bcrypt version: {bcrypt.__version__}")
print(f"bcrypt __all__: {bcrypt.__all__}")

password = b'admin123'

# The known hash
stored = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi"

# Test with different approaches
print(f"\n=== Testing stored hash ===")
try:
    result = bcrypt.checkpw(password, stored.encode('utf-8'))
    print(f"UTF-8: {result}")
except Exception as e:
    print(f"UTF-8 error: {e}")

# Generate new hash with THIS library
fresh = bcrypt.hashpw(password, bcrypt.gensalt(rounds=10))
print(f"\nFresh hash from this bcrypt lib: {fresh}")
verify = bcrypt.checkpw(password, fresh)
print(f"Verify fresh: {verify}")

# Can this library verify the stored hash at all?
try:
    result = bcrypt.checkpw(password, stored)
    print(f"\nDirect string: {result}")
except TypeError as e:
    print(f"\nDirect string error: {e}")
