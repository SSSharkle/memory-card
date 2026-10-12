param([string]$JavaHome = $env:JAVA_HOME, [string]$SdkRoot = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JavaHome -and (Test-Path 'D:\develop\JAVA21')) { $JavaHome = 'D:\develop\JAVA21' }
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set JAVA_HOME or pass -JavaHome to Java 21.' }
$env:JAVA_HOME = $JavaHome
if ($SdkRoot) { $sdkPath = $SdkRoot.Replace('\','/'); [IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'),"sdk.dir=$sdkPath`n",[Text.UTF8Encoding]::new($false)) }
$signingDir=Join-Path $projectRoot '.signing'
New-Item -ItemType Directory -Force $signingDir | Out-Null
$keystore=Join-Path $signingDir 'release.jks'
$properties=Join-Path $signingDir 'signing.properties'
if (-not (Test-Path $keystore)) {
    if (Test-Path $properties) { throw 'Signing properties exists without key; preserve/restore your original key.' }
    $random=[byte[]]::new(32);$rng=[Security.Cryptography.RandomNumberGenerator]::Create();try{$rng.GetBytes($random)}finally{$rng.Dispose()}
    $env:FLASHCARD_STORE_PASSWORD=[Convert]::ToBase64String($random)
    $env:FLASHCARD_KEY_PASSWORD=$env:FLASHCARD_STORE_PASSWORD
    & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $keystore -storetype JKS -alias examflashcards -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Exam Flashcards, O=Local Offline App, C=CN' -storepass:env FLASHCARD_STORE_PASSWORD -keypass:env FLASHCARD_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed.' }
    [IO.File]::WriteAllText($properties,"storePassword=$($env:FLASHCARD_STORE_PASSWORD)`nkeyPassword=$($env:FLASHCARD_KEY_PASSWORD)`n",[Text.UTF8Encoding]::new($false))
    Remove-Item Env:FLASHCARD_STORE_PASSWORD,Env:FLASHCARD_KEY_PASSWORD
}
if (-not (Test-Path $properties)) { throw 'Missing local signing.properties. Restore original signing credentials.' }
Push-Location $projectRoot
try {
    & .\gradlew.bat :core:test :app:lintRelease :app:assembleRelease --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or required tests failed.' }
    $dist=Join-Path $projectRoot 'dist';New-Item -ItemType Directory -Force $dist | Out-Null
    $apk=Join-Path $dist '考研闪卡-1.0.0.apk'
    Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination $apk -Force
    $digest=Get-FileHash -LiteralPath $apk -Algorithm SHA256
    [IO.File]::WriteAllText((Join-Path $dist 'SHA256SUMS.txt'),"$($digest.Hash.ToLowerInvariant())  考研闪卡-1.0.0.apk`n",[Text.UTF8Encoding]::new($false))
    Write-Output "Signed APK: $apk"
    Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Select-Object Algorithm,Hash,Path
} finally { Pop-Location }
