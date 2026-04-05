# Redis Data Export Script
# Export all Redis data to SQL-like format

$redisHost = "localhost"
$redisPort = 6379

# Get all keys
$keys = docker exec ecommerce-redis redis-cli --scan

$output = @()
$output += "-- ================================================`r`n"
$output += "-- Redis Data Export`r`n"
$output += "-- Date: 2026-03-29`r`n"
$output += "-- Total Keys: $($keys.Count)`r`n"
$output += "-- ================================================`r`n"
$output += ""

# Group keys by type
$keyTypes = @{}
foreach ($key in $keys) {
    $type = docker exec ecommerce-redis redis-cli TYPE $key 2>$null
    if (-not $keyTypes.ContainsKey($type)) {
        $keyTypes[$type] = @()
    }
    $keyTypes[$type] += $key
}

# Export by type
foreach ($type in $keyTypes.Keys | Sort-Object) {
    $output += "`r`n-- ================================================`r`n"
    $output += "-- Type: $type`r`n"
    $output += "-- Count: $($keyTypes[$type].Count)`r`n"
    $output += "-- ================================================`r`n"
    
    foreach ($key in $keyTypes[$type]) {
        $output += "`r`n-- Key: $key`r`n"
        
        switch ($type) {
            "string" {
                $value = docker exec ecommerce-redis redis-cli GET $key 2>$null
                if ($value) {
                    $escapedValue = $value -replace '([\\"])', '\$1'
                    $output += "SET `"$key`" `"$escapedValue`"`r`n"
                }
            }
            "hash" {
                $fields = docker exec ecommerce-redis redis-cli HGETALL $key 2>$null
                if ($fields) {
                    for ($i = 0; $i -lt $fields.Count; $i += 2) {
                        $field = $fields[$i]
                        $value = $fields[$i + 1]
                        $escapedField = $field -replace '([\\"])', '\$1'
                        $escapedValue = $value -replace '([\\"])', '\$1'
                        $output += "HSET `"$key`" `"$escapedField`" `"$escapedValue`"`r`n"
                    }
                }
            }
            "list" {
                $len = docker exec ecommerce-redis redis-cli LLEN $key 2>$null
                for ($i = 0; $i -lt [int]$len; $i++) {
                    $value = docker exec ecommerce-redis redis-cli LINDEX $key $i 2>$null
                    $escapedValue = $value -replace '([\\"])', '\$1'
                    $output += "RPUSH `"$key`" `"$escapedValue`"`r`n"
                }
            }
            "set" {
                $members = docker exec ecommerce-redis redis-cli SMEMBERS $key 2>$null
                foreach ($member in $members) {
                    $escapedMember = $member -replace '([\\"])', '\$1'
                    $output += "SADD `"$key`" `"$escapedMember`"`r`n"
                }
            }
            "zset" {
                $members = docker exec ecommerce-redis redis-cli ZREVRANGE $key 0 -1 WITHSCORES 2>$null
                for ($i = 0; $i -lt $members.Count; $i += 2) {
                    $member = $members[$i]
                    $score = $members[$i + 1]
                    $escapedMember = $member -replace '([\\"])', '\$1'
                    $output += "ZADD `"$key`" $score `"$escapedMember`"`r`n"
                }
            }
            "stream" {
                $entries = docker exec ecommerce-redis redis-cli XRANGE $key - + 2>$null
                foreach ($entry in $entries) {
                    $output += "-- Stream Entry: $entry`r`n"
                }
            }
        }
    }
}

# Save to file
$output | Out-File -FilePath "c:\Users\ROG\microservices-ecommerce\scripts\sql\redis_full_data_20260329.sql" -Encoding UTF8
Write-Host "Export completed. Total keys: $($keys.Count)"
