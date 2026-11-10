# OpenBlue US fork — portable toolchain setup + debug build. No admin needed.
# Everything lives under C:\development\android-build. Logs to setup.log there.
# Markers: SETUP_OK / BUILD_OK / BUILD_FAIL files appear in the same folder.
$ErrorActionPreference = 'Continue'
$root = 'C:\development\android-build'
$dl = "$root\downloads"
New-Item -ItemType Directory -Force $dl, "$root\sdk", "$root\jdk", "$root\gradle" | Out-Null
$log = "$root\setup.log"
function Log($m) { $line = "$(Get-Date -Format 'HH:mm:ss') $m"; Add-Content -Path $log -Value $line }

Log "=== setup started ==="

function Fetch($url, $name) {
    $out = "$dl\$name"
    if (Test-Path $out) { Log "have $name"; return $out }
    Log "downloading $name"
    curl.exe -L --retry 3 --fail -o $out $url
    if ($LASTEXITCODE -ne 0) { Log "DOWNLOAD FAILED $name"; throw "download $name" }
    return $out
}

try {
    $jdkZip = Fetch 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' 'jdk17.zip'
    if (-not (Test-Path "$root\jdk\bin\java.exe")) {
        Log "extracting JDK"
        tar.exe -xf $jdkZip -C "$root\jdk" --strip-components 1
    }
    $gradleZip = Fetch 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' 'gradle-8.9-bin.zip'
    if (-not (Test-Path "$root\gradle\bin\gradle.bat")) {
        Log "extracting Gradle"
        tar.exe -xf $gradleZip -C "$root\gradle" --strip-components 1
    }
    $ctZip = Fetch 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' 'cmdtools.zip'
    if (-not (Test-Path "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
        Log "extracting cmdline-tools"
        New-Item -ItemType Directory -Force "$root\sdk\cmdline-tools\latest" | Out-Null
        tar.exe -xf $ctZip -C "$root\sdk\cmdline-tools\latest" --strip-components 1
    }
} catch { Log "SETUP FAILED (downloads): $_"; New-Item -ItemType File -Force "$root\SETUP_FAIL" | Out-Null; exit 1 }

$env:JAVA_HOME = "$root\jdk"
$env:ANDROID_SDK_ROOT = "$root\sdk"
$env:ANDROID_HOME = "$root\sdk"
$env:Path = "$root\jdk\bin;$root\gradle\bin;$root\sdk\platform-tools;$env:Path"

try {
    Log "java: $(java -version 2>&1 | Select-Object -First 1)"
    Log "accepting SDK licenses"
    1..40 | ForEach-Object { 'y' } | & "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses | Out-Null
    Log "installing SDK packages (platform 34, build-tools 34, platform-tools)"
    & "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat" 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools' 2>&1 | Select-Object -Last 3 | ForEach-Object { Log $_ }
} catch { Log "SETUP FAILED (sdk): $_"; New-Item -ItemType File -Force "$root\SETUP_FAIL" | Out-Null; exit 1 }

New-Item -ItemType File -Force "$root\SETUP_OK" | Out-Null
Log "=== setup OK, starting build ==="

# ---- project ----
$proj = 'C:\development\openblue-us'
if (Test-Path 'C:\development\openblue-us.zip') {
    if (Test-Path $proj) { Remove-Item -Recurse -Force $proj }
    New-Item -ItemType Directory -Force $proj | Out-Null
    tar.exe -xf 'C:\development\openblue-us.zip' -C $proj
}
if (-not (Test-Path "$proj\app\build.gradle.kts")) { Log "BUILD FAILED: project not found at $proj"; New-Item -ItemType File -Force "$root\BUILD_FAIL" | Out-Null; exit 1 }

Set-Content -Path "$proj\local.properties" -Value 'sdk.dir=C:\\development\\android-build\\sdk' -Encoding ASCII
# The 2011 CPU / 8 GB RAM in this PC: cap Gradle's heap in the build copy only.
(Get-Content "$proj\gradle.properties") -replace '-Xmx3g', '-Xmx2g' | Set-Content "$proj\gradle.properties"

Set-Location $proj
& "$root\gradle\bin\gradle.bat" --no-daemon --console=plain assembleDebug *> "$root\build.log"
if (Test-Path "$proj\app\build\outputs\apk\debug\app-debug.apk") {
    Copy-Item "$proj\app\build\outputs\apk\debug\app-debug.apk" "$root\OpenBlue-US.apk" -Force
    Log "=== BUILD OK: $root\OpenBlue-US.apk ==="
    New-Item -ItemType File -Force "$root\BUILD_OK" | Out-Null
} else {
    Log "=== BUILD FAILED (see build.log) ==="
    New-Item -ItemType File -Force "$root\BUILD_FAIL" | Out-Null
}
