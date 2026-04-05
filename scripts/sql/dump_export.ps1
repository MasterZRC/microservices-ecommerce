$ErrorActionPreference = 'SilentlyContinue'
$process = Start-Process -FilePath "docker" -ArgumentList "exec","ecommerce-mysql","sh","-c","mysqldump -uroot -proot123 --single-transaction --quick --hex-blob --skip-lock-tables --default-character-set=utf8mb4 ecommerce" -NoNewWindow -Wait -PassThru -RedirectStandardOutput "c:\Users\ROG\microservices-ecommerce\scripts\sql\ecommerce_full_dump_20260329.sql"
Write-Host "Done, exit code: $($process.ExitCode)"
