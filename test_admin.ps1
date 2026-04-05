$body = @"
{"username":"admin","password":"admin123"}
"@
$response = Invoke-WebRequest -Uri 'http://localhost:8080/api/admin/auth/login' -Method Post -ContentType 'application/json' -Body $body
Write-Host "Status:" $response.StatusCode
Write-Host "Content:" $response.Content
