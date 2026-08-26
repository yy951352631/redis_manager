param(
    [string]$HostName = "192.168.18.136",
    [string]$User = "root",
    [string]$TomcatHome = "/home/tomcat/apache-tomcat-9.0.78",
    [string]$UiDist = "/home/nginx/nginx_home/html/cachecloud-ui/dist",
    [string]$BackupBase = "/home/cachecloud-backups",
    [string]$JavaHome = "/home/tomcat/jdk1.8.0_202",
    [string]$MavenCommand = "mvn",
    [string]$PnpmCommand = "pnpm",
    [string]$NodeBin,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory = (Get-Location).Path
    )

    Write-Host ">> $FilePath $($Arguments -join ' ')"
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -NoNewWindow -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Command failed with exit code $($process.ExitCode): $FilePath $($Arguments -join ' ')"
    }
}

function New-RemoteDeployScript {
    param(
        [string]$Path,
        [string]$RemoteTomcatHome,
        [string]$RemoteUiDist,
        [string]$RemoteBackupBase,
        [string]$RemoteJavaHome
    )

    $content = @'
set -euo pipefail
STAGE="${1:?stage dir required}"
TS="${2:?timestamp required}"
TOMCAT_HOME="__TOMCAT_HOME__"
WEB_ROOT="$TOMCAT_HOME/webapps/ROOT"
UI_DIST="__UI_DIST__"
BACKUP_BASE="__BACKUP_BASE__"
BACKUP="$BACKUP_BASE/$TS"
JAVA_HOME="__JAVA_HOME__"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

restore_backend() {
  echo "Rolling back backend..."
  pid=$(pgrep -f 'org.apache.catalina.startup.Bootstrap' | head -n 1 || true)
  if [ -n "$pid" ]; then kill "$pid" || true; sleep 3; fi
  rm -rf "$WEB_ROOT"
  if [ -d "$BACKUP/ROOT.previous-dir" ]; then mv "$BACKUP/ROOT.previous-dir" "$WEB_ROOT"; fi
  "$TOMCAT_HOME/bin/startup.sh" || true
}

restore_frontend() {
  echo "Rolling back frontend..."
  rm -rf "$UI_DIST"
  if [ -d "$BACKUP/ui-dist.previous-dir" ]; then mv "$BACKUP/ui-dist.previous-dir" "$UI_DIST"; fi
}

[ -d "$TOMCAT_HOME" ] || fail "Tomcat home missing: $TOMCAT_HOME"
[ -d "$(dirname "$UI_DIST")" ] || fail "UI parent missing: $(dirname "$UI_DIST")"
[ -f "$STAGE/ROOT.war" ] || fail "Missing $STAGE/ROOT.war"
[ -f "$STAGE/cachecloud-ui-dist.tgz" ] || fail "Missing $STAGE/cachecloud-ui-dist.tgz"
[ "$WEB_ROOT" = "$TOMCAT_HOME/webapps/ROOT" ] || fail "Unexpected WEB_ROOT: $WEB_ROOT"
command -v unzip >/dev/null 2>&1 || fail "unzip command missing"

mkdir -p "$BACKUP/configs"
rm -rf "$STAGE/new-ROOT" "$STAGE/new-dist"
mkdir -p "$STAGE/new-ROOT" "$STAGE/new-dist"

echo "Backup directory: $BACKUP"
if [ ! -f "$BACKUP/ROOT.tgz" ]; then tar -C "$TOMCAT_HOME/webapps" -czf "$BACKUP/ROOT.tgz" ROOT; fi
if [ -d "$UI_DIST" ] && [ ! -f "$BACKUP/cachecloud-ui-dist.tgz" ]; then tar -C "$(dirname "$UI_DIST")" -czf "$BACKUP/cachecloud-ui-dist.tgz" "$(basename "$UI_DIST")"; fi
cp "$WEB_ROOT/WEB-INF/classes"/application*.yml "$BACKUP/configs"/ 2>/dev/null || true

unzip -q "$STAGE/ROOT.war" -d "$STAGE/new-ROOT"
if [ -f "$BACKUP/configs/application.yml" ]; then
  cp "$BACKUP/configs/application.yml" "$STAGE/new-ROOT/WEB-INF/classes"/
fi

active_profile=$(awk -F: '/^[[:space:]]*active:/ { gsub(/[[:space:]]/, "", $2); print $2; exit }' "$BACKUP/configs/application.yml" 2>/dev/null || true)
case "$active_profile" in
  '${SPRING_PROFILES_ACTIVE:'*)
    active_profile="${active_profile#\$\{SPRING_PROFILES_ACTIVE:}"
    active_profile="${active_profile%\}}"
    ;;
  '${'*)
    active_profile=""
    ;;
esac

if [ -n "$active_profile" ] && [ -f "$BACKUP/configs/application-$active_profile.yml" ]; then
  echo "Preserving runtime profile config: application-$active_profile.yml"
  cp "$BACKUP/configs/application-$active_profile.yml" "$STAGE/new-ROOT/WEB-INF/classes"/
elif ls "$BACKUP/configs"/application*.yml >/dev/null 2>&1; then
  echo "No concrete active profile detected; preserving all existing application*.yml"
  cp "$BACKUP/configs"/application*.yml "$STAGE/new-ROOT/WEB-INF/classes"/
fi
tar -xzf "$STAGE/cachecloud-ui-dist.tgz" -C "$STAGE/new-dist"

pid=$(pgrep -f 'org.apache.catalina.startup.Bootstrap' | head -n 1 || true)
if [ -n "$pid" ]; then
  echo "Stopping Tomcat pid=$pid"
  "$TOMCAT_HOME/bin/shutdown.sh" || true
  for i in $(seq 1 30); do
    pgrep -f 'org.apache.catalina.startup.Bootstrap' >/dev/null || break
    sleep 2
  done
  if pgrep -f 'org.apache.catalina.startup.Bootstrap' >/dev/null; then
    echo "Tomcat still running, sending TERM"
    kill "$pid" || true
    for i in $(seq 1 15); do
      pgrep -f 'org.apache.catalina.startup.Bootstrap' >/dev/null || break
      sleep 2
    done
  fi
  pgrep -f 'org.apache.catalina.startup.Bootstrap' >/dev/null && fail "Tomcat did not stop"
fi

echo "Swapping backend ROOT"
mv "$WEB_ROOT" "$BACKUP/ROOT.previous-dir"
mv "$STAGE/new-ROOT" "$WEB_ROOT"

echo "Swapping frontend dist"
if [ -d "$UI_DIST" ]; then mv "$UI_DIST" "$BACKUP/ui-dist.previous-dir"; fi
mv "$STAGE/new-dist" "$UI_DIST"

echo "Starting Tomcat"
"$TOMCAT_HOME/bin/startup.sh"

backend_ok=0
for i in $(seq 1 90); do
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8080/ || true)
  echo "backend probe $i -> $code"
  if [ "$code" = "200" ] || [ "$code" = "301" ] || [ "$code" = "302" ]; then backend_ok=1; break; fi
  sleep 2
done

if [ "$backend_ok" != "1" ]; then restore_backend; restore_frontend; fail "Backend health check failed; rollback attempted"; fi

front_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1/ || true)
api_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1/api/v1/wiki/access/client || true)
manage_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:8080/manage/total/statlist || true)
echo "frontend / -> $front_code"
echo "api wiki probe -> $api_code"
echo "manage statlist -> $manage_code"

[ "$front_code" = "200" ] || { restore_backend; restore_frontend; fail "Frontend health check failed; rollback attempted"; }
echo "DEPLOY_OK backup=$BACKUP"
'@

    $content = $content.
        Replace("__TOMCAT_HOME__", $RemoteTomcatHome).
        Replace("__UI_DIST__", $RemoteUiDist).
        Replace("__BACKUP_BASE__", $RemoteBackupBase).
        Replace("__JAVA_HOME__", $RemoteJavaHome)

    Set-Content -LiteralPath $Path -Value $content -Encoding ASCII
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$webWar = Join-Path $repoRoot "cachecloud-web\target\cachecloud-web.war"
$uiRoot = Join-Path $repoRoot "cachecloud-ui"
$uiDistLocal = Join-Path $uiRoot "dist"

if ($NodeBin) {
    $env:Path = "$NodeBin;$env:Path"
}

if (-not $SkipBuild) {
    Invoke-Checked -FilePath $MavenCommand -Arguments @("-B", "-ntp", "package", "-DskipTests") -WorkingDirectory $repoRoot
    Invoke-Checked -FilePath $PnpmCommand -Arguments @("install", "--frozen-lockfile") -WorkingDirectory $uiRoot
    Invoke-Checked -FilePath (Join-Path $uiRoot "node_modules\.bin\vitest.CMD") -Arguments @("run", "--reporter=dot") -WorkingDirectory $uiRoot
    Invoke-Checked -FilePath (Join-Path $uiRoot "node_modules\.bin\vue-tsc.CMD") -Arguments @("--noEmit") -WorkingDirectory $uiRoot
    Invoke-Checked -FilePath (Join-Path $uiRoot "node_modules\.bin\vite.CMD") -Arguments @("build") -WorkingDirectory $uiRoot
}

if (-not (Test-Path -LiteralPath $webWar)) {
    throw "WAR not found: $webWar"
}
if (-not (Test-Path -LiteralPath (Join-Path $uiDistLocal "index.html"))) {
    throw "Frontend dist not found: $uiDistLocal"
}

$timestamp = Get-Date -Format "yyyyMMddHHmmss"
$packageDir = Join-Path $env:TEMP "cachecloud-deploy-$timestamp"
$remoteStage = "/tmp/cachecloud-deploy-$timestamp"
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null

Copy-Item -LiteralPath $webWar -Destination (Join-Path $packageDir "ROOT.war") -Force
Invoke-Checked -FilePath "tar" -Arguments @("-czf", (Join-Path $packageDir "cachecloud-ui-dist.tgz"), "-C", $uiDistLocal, ".") -WorkingDirectory $repoRoot
New-RemoteDeployScript -Path (Join-Path $packageDir "deploy-cachecloud.sh") -RemoteTomcatHome $TomcatHome -RemoteUiDist $UiDist -RemoteBackupBase $BackupBase -RemoteJavaHome $JavaHome

$target = "$User@$HostName"
Invoke-Checked -FilePath "ssh.exe" -Arguments @($target, "mkdir -p $remoteStage")
Invoke-Checked -FilePath "scp.exe" -Arguments @((Join-Path $packageDir "ROOT.war"), (Join-Path $packageDir "cachecloud-ui-dist.tgz"), (Join-Path $packageDir "deploy-cachecloud.sh"), "${target}:$remoteStage/")
Invoke-Checked -FilePath "ssh.exe" -Arguments @($target, "chmod +x $remoteStage/deploy-cachecloud.sh && bash $remoteStage/deploy-cachecloud.sh $remoteStage $timestamp")
Invoke-Checked -FilePath "ssh.exe" -Arguments @($target, "rm -rf $remoteStage")

Remove-Item -LiteralPath $packageDir -Recurse -Force
Write-Host "CacheCloud deployment completed. Backup: $BackupBase/$timestamp"
