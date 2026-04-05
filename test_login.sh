#!/bin/sh
DATA='{"username":"admin","password":"admin123"}'
curl -s -X POST "http://host.docker.internal:8080/api/admin/auth/login" -H "Content-Type: application/json" -d "$DATA" --max-time 10
