$client = New-Object System.Net.Sockets.TcpClient
$client.Connect('127.0.0.1', 6379)
$stream = $client.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$reader = New-Object System.IO.StreamReader($stream)

function Send-Command([string]$cmd) {
    $writer.WriteLine($cmd)
    $writer.Flush()
    Start-Sleep -Milliseconds 100
    return $reader.ReadToEnd()
}

Write-Host "=== Recommendation Keys ==="
Send-Command "KEYS recommendation:*"

Write-Host "`n=== Seckill Keys ==="
Send-Command "KEYS seckill:*"

Write-Host "`n=== User Profile Keys ==="
Send-Command "KEYS user:profile:*"

Write-Host "`n=== Popular Items Cache ==="
Send-Command "GET recommendation:popular:decay-0.95:all"

Write-Host "`n=== Similarity Matrix (first 500 chars) ==="
$sim = Send-Command "GET recommendation:similarity:v1:all"
if ($sim.Length -gt 500) { Write-Host ($sim.Substring(0, 500) + "...") } else { Write-Host $sim }

Write-Host "`n=== Item Category Map (first 500 chars) ==="
$cat = Send-Command "GET recommendation:item:category:all"
if ($cat.Length -gt 500) { Write-Host ($cat.Substring(0, 500) + "...") } else { Write-Host $cat }

$writer.Close()
$reader.Close()
$stream.Close()
$client.Close()
