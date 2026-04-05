$r = Invoke-WebRequest -Uri "http://localhost:8080/api/admin/auth/login" -Method POST -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15 -UseBasicParsing
Write-Host "Status: $($r.StatusCode)"
Write-Host "Content: $($r.Content)"
