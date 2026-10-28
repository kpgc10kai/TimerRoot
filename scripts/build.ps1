# Builds and signs TimerRoot with only the Android SDK (no Gradle, no Maven).
#
#   git clone https://github.com/RikkaApps/Shizuku-API
#   scripts/build.ps1 -Sdk "$env:LOCALAPPDATA\Android\Sdk" -ShizukuApi ..\Shizuku-API
#
# Produces build/TimerRoot.apk.
param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$ShizukuApi = "",
    [string]$Out = "",
    [int]$VersionCode = 31,
    [string]$VersionName = "0.31"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root 'build' }
$bt = Join-Path $Sdk 'build-tools/36.0.0'
$androidJar = Join-Path $Sdk 'platforms/android-36/android.jar'
$framework = Join-Path $Sdk 'platforms/android-36/framework.aidl'
$aidlExe = Join-Path $bt 'aidl.exe'
$d8 = Join-Path $bt 'd8.bat'
$aapt2 = Join-Path $bt 'aapt2.exe'
$zipalign = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'
$jar = Join-Path (Split-Path (Get-Command javac).Source -Parent) 'jar.exe'

if (-not $ShizukuApi) {
    $ShizukuApi = Join-Path $root '..\Shizuku-API'
}
if (-not (Test-Path $ShizukuApi)) {
    throw "Shizuku-API not found at $ShizukuApi (git clone https://github.com/RikkaApps/Shizuku-API)"
}
if (-not (Test-Path (Join-Path $root 'app/src/main/assets/timer'))) {
    throw "app/src/main/assets/timer is missing; run scripts/fetch-timer.ps1 first"
}

$work = Join-Path $Out 'work'
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
$src = Join-Path $work 'src'
$gen = Join-Path $work 'gen'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
New-Item -ItemType Directory -Force -Path $src, $gen, $classes, $dex | Out-Null

# Shizuku API + provider sources
Copy-Item (Join-Path $ShizukuApi 'api/src/main/java/*') $src -Recurse -Force
Copy-Item (Join-Path $ShizukuApi 'shared/src/main/java/*') $src -Recurse -Force
Copy-Item (Join-Path $ShizukuApi 'provider/src/main/java/*') $src -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $work 'aidl') | Out-Null
Copy-Item (Join-Path $ShizukuApi 'aidl/src/main/aidl/*') (Join-Path $work 'aidl') -Recurse -Force
Copy-Item (Join-Path $root 'app/src/main/java/*') $src -Recurse -Force

# androidx.annotation stubs (the API only uses the annotations)
$stub = Join-Path $work 'stubs/androidx/annotation'
New-Item -ItemType Directory -Force -Path $stub | Out-Null
@'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
public @interface NonNull {}
'@ | Set-Content (Join-Path $stub 'NonNull.java') -Encoding ascii
@'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
public @interface Nullable {}
'@ | Set-Content (Join-Path $stub 'Nullable.java') -Encoding ascii
@'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
public @interface RestrictTo { Scope[] value(); enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, TESTS, SUBCLASSES } }
'@ | Set-Content (Join-Path $stub 'RestrictTo.java') -Encoding ascii

# The API uses android.util.ArraySet (hidden); swap it for a HashSet.
$remote = Join-Path $src 'rikka/shizuku/ShizukuRemoteProcess.java'
$text = Get-Content $remote -Raw
$text = $text -replace 'import android\.util\.ArraySet;\r?\n', ''
$text = $text.Replace('new ArraySet<>()', 'new java.util.HashSet<>()')
Set-Content $remote -Value $text -NoNewline

# AIDL
foreach ($dir in @((Join-Path $work 'aidl'), (Join-Path $root 'app/src/main/aidl'))) {
    Get-ChildItem $dir -Recurse -Filter '*.aidl' | ForEach-Object {
        & $aidlExe "-I$dir" "-I$(Join-Path $Sdk 'platforms/android-36')" "-p$framework" "-o$gen" $_.FullName
    }
}

# javac
$sources = Get-ChildItem $src, $gen, (Join-Path $work 'stubs') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName -replace '\\', '/' }
$argfile = Join-Path $work 'sources.txt'
$sources | Set-Content $argfile -Encoding ascii
& javac -source 11 -target 11 -nowarn -classpath $androidJar -d ($classes -replace '\\', '/') "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# d8
$classFiles = Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object { $_.FullName -replace '\\', '/' }
$classArg = Join-Path $work 'classes.txt'
$classFiles | Set-Content $classArg -Encoding ascii
& $d8 --lib $androidJar --min-api 30 --output ($dex -replace '\\', '/') "@$classArg"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# resources + manifest
$resZip = Join-Path $work 'res.zip'
$base = Join-Path $work 'base.apk'
& $aapt2 compile --dir (Join-Path $root 'app/src/main/res') -o $resZip
& $aapt2 link -o $base -I $androidJar --manifest (Join-Path $root 'app/src/main/AndroidManifest.xml') `
    -A (Join-Path $root 'app/src/main/assets') --min-sdk-version 30 --target-sdk-version 36 `
    --version-code $VersionCode --version-name $VersionName $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

# dex + align + sign
$unsigned = Join-Path $work 'unsigned.apk'
$aligned = Join-Path $work 'aligned.apk'
Copy-Item $base $unsigned -Force
& $jar uf $unsigned -C $dex classes.dex
& $zipalign -f 4 $unsigned $aligned

$keystore = Join-Path $work 'debug.keystore'
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}
$apk = Join-Path $Out 'TimerRoot.apk'
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "built $apk"
