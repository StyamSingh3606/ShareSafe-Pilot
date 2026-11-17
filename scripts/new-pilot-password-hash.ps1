$ErrorActionPreference = 'Stop'
$node = 'C:/Program Files/nodejs/node.exe'
$password = Read-Host 'Choose your ShareSafe sender password' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try {
    $plaintext = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($plaintext.Length -lt 12 -or $plaintext.Length -gt 256) {
        throw 'Choose a password between 12 and 256 characters.'
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($plaintext))
    $script = @"
const crypto = require('node:crypto');
const fs = require('node:fs');
const secret = Buffer.from(fs.readFileSync(0, 'utf8').trim(), 'base64');
const salt = crypto.randomBytes(16);
const hash = crypto.scryptSync(secret, salt, 64);
secret.fill(0);
console.log(salt.toString('hex') + ':' + hash.toString('hex'));
"@
    $hash = $encoded | & $node -e $script
    if ($LASTEXITCODE -ne 0 -or $hash -cnotmatch '^[a-f0-9]{32}:[a-f0-9]{128}$') {
        throw 'Password hash generation failed.'
    }
    Set-Clipboard -Value $hash
    Write-Host 'Hash copied. Paste it into Render as SENDER_PASSWORD_HASH.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $password.Dispose()
}
