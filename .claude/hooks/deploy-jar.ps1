# Auto-deploy the freshly built HomoSapiens mod jar to the "quests" CurseForge instance.
#
# Wired as a Claude Code PostToolUse(Bash) hook. The hook JSON payload arrives on stdin;
# we only act when the Bash command that just ran was a gradle build (the thing that
# (re)produces the jar). Standing instruction from the user:
#   "from now on when you do updates can you push the newest jar into
#    C:\Users\lukey\curseforge\minecraft\Instances\quests this instance."
#
# Safe by design: silently no-ops on any unexpected input, missing jar, or missing dest.

$ErrorActionPreference = 'SilentlyContinue'

$raw = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }

try { $payload = $raw | ConvertFrom-Json } catch { exit 0 }

$command = [string]$payload.tool_input.command
if ([string]::IsNullOrWhiteSpace($command)) { exit 0 }

# Only react to gradle build invocations (gradlew build, gradlew.bat build, clean build, etc.).
# compileJava / runClient / unrelated commands are ignored because they do not refresh the jar.
if ($command -notmatch '(?i)gradle.*\bbuild\b') { exit 0 }

# Resolve the build output relative to the command's working directory; fall back to the worktree.
$cwd = [string]$payload.cwd
if ([string]::IsNullOrWhiteSpace($cwd)) {
    $cwd = 'C:/Users/lukey/Downloads/MultiCharacterMod/.claude/worktrees/funny-lewin'
}

$libs = Join-Path $cwd 'build/libs'
if (-not (Test-Path -LiteralPath $libs)) { exit 0 }

# Pick the newest HomoSapiens-*.jar (excluding any -sources jar) so version bumps keep working.
$jar = Get-ChildItem -LiteralPath $libs -Filter 'HomoSapiens-*.jar' -File |
       Where-Object { $_.Name -notmatch '(?i)-sources\.jar$' } |
       Sort-Object LastWriteTime -Descending |
       Select-Object -First 1
if ($null -eq $jar) { exit 0 }

$dest = 'C:/Users/lukey/curseforge/minecraft/Instances/quests/mods'
if (-not (Test-Path -LiteralPath $dest)) { exit 0 }

Copy-Item -LiteralPath $jar.FullName -Destination $dest -Force
if (-not $?) { exit 0 }

$msg  = "[auto-deploy] Pushed $($jar.Name) -> quests/mods"
$json = '{"systemMessage":"' + ($msg -replace '\\','\\\\' -replace '"','\"') + '","suppressOutput":true}'
[Console]::Out.Write($json)
exit 0
