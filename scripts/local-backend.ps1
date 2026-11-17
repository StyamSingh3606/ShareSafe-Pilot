$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$postgres = 'C:/Program Files/PostgreSQL/17/bin'
if (!(Test-Path (Join-Path $postgres '../lib/dict_snowball.dll')) -and
    (Test-Path '.local/portable-postgres/pgsql/bin/initdb.exe')) {
    $postgres = (Resolve-Path '.local/portable-postgres/pgsql/bin').Path
}
$env:Path = 'C:/Program Files/nodejs;' + $env:Path
$local = Join-Path (Get-Location) '.local'
New-Item -ItemType Directory -Force $local | Out-Null
$credentials = Join-Path $local 'backend.env'
if (!(Test-Path -LiteralPath $credentials)) {
    $secrets = @{}
    foreach ($name in 'PG_PASSWORD','LOCAL_WRAP_KEY','OTP_SECRET','DEV_SENDER_TOKEN','DEV_INBOX_TOKEN') {
        $secrets[$name] = & node.exe -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))"
    }
    $secrets.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -LiteralPath $credentials
}
foreach ($line in Get-Content -LiteralPath $credentials) {
    $name, $value = $line.Split('=', 2)
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
$env:DATABASE_URL = "postgresql://sharesafe:${env:PG_PASSWORD}@127.0.0.1:55432/sharesafe"
if ($args -contains '-Test') {
    npm.cmd --prefix backend test
    exit $LASTEXITCODE
}
$data = Join-Path $local 'pgdata'
if (!(Test-Path (Join-Path $data 'PG_VERSION'))) {
    $passwordFile = Join-Path $local 'pg-password'
    $env:PG_PASSWORD | Set-Content -LiteralPath $passwordFile
    try {
        & "$postgres/initdb.exe" -D $data -U sharesafe --auth=scram-sha-256 "--pwfile=$passwordFile" -E UTF8
        if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed' }
    } finally { Remove-Item -LiteralPath $passwordFile }
}
& "$postgres/pg_ctl.exe" status -D $data
if ($LASTEXITCODE -ne 0) {
    & "$postgres/pg_ctl.exe" start -D $data -l (Join-Path $local 'postgres.log') -o '-p 55432 -h 127.0.0.1' -w
    if ($LASTEXITCODE -ne 0) { throw 'Database did not start' }
}
$env:PGPASSWORD = $env:PG_PASSWORD
$exists = & "$postgres/psql.exe" -h 127.0.0.1 -p 55432 -U sharesafe -d postgres -tA -c "SELECT 1 FROM pg_database WHERE datname='sharesafe'"
if ($LASTEXITCODE -ne 0) { throw 'Database connectivity check failed' }
if ($exists -ne '1') {
    & "$postgres/createdb.exe" -h 127.0.0.1 -p 55432 -U sharesafe sharesafe
    if ($LASTEXITCODE -ne 0) { throw 'Database creation failed' }
}
$env:NODE_ENV = 'development'
npm.cmd --prefix backend run dev
