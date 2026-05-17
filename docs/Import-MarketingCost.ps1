<#
.SYNOPSIS
    把 marketing_cost.sql (1.2GB) 导入到 Windows 上的 MySQL。

.DESCRIPTION
    行为：
      - SQL 文件里有的表：先 DROP 再用最新版重建（覆盖）
      - SQL 文件里没有的表：完全不动
      - 自动检测目标库现有表，列出"将被覆盖 / 将新建 / 保留不动"的清单
      - 导入前自动备份目标库中将要被覆盖的表

.EXAMPLE
    # 默认参数（127.0.0.1 / root / 数据库 marketing_cost）
    .\Import-MarketingCost.ps1

.EXAMPLE
    # 指定连接信息
    .\Import-MarketingCost.ps1 -DbHost "192.168.1.50" -DbUser "root" -DbName "marketing_cost" `
        -SqlFile "C:\data\marketing_cost.sql"

.EXAMPLE
    # 跳过备份（目标库是空的时候用）
    .\Import-MarketingCost.ps1 -NoBackup

#>

[CmdletBinding()]
param(
    [string]$SqlFile = "C:\data\marketing_cost.sql",
    [string]$DbHost  = "127.0.0.1",
    [int]   $DbPort  = 3306,
    [string]$DbUser  = "root",
    [string]$DbPass  = $null,
    [string]$DbName  = "marketing_cost",
    [string]$BackupDir = "$env:USERPROFILE\mysql_backup",
    [switch]$NoBackup
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Msg, [string]$Color = "Cyan")
    $ts = (Get-Date).ToString("HH:mm:ss")
    Write-Host "[$ts] " -ForegroundColor $Color -NoNewline
    Write-Host $Msg
}
function Write-Err { param([string]$Msg) Write-Host "[ERR] $Msg" -ForegroundColor Red; exit 1 }

# 1. 找 mysql.exe / mysqldump.exe
function Find-MySqlBin {
    param([string]$exeName)
    $cmd = Get-Command $exeName -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # 尝试常见路径
    $candidates = @(
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\$exeName.exe",
        "C:\Program Files\MySQL\MySQL Server 5.7\bin\$exeName.exe",
        "C:\Program Files (x86)\MySQL\MySQL Server 8.0\bin\$exeName.exe",
        "C:\xampp\mysql\bin\$exeName.exe",
        "C:\phpstudy_pro\Extensions\MySQL5.7.26\bin\$exeName.exe"
    ) + (Get-ChildItem "C:\Program Files\MySQL" -Filter "$exeName.exe" -Recurse -ErrorAction SilentlyContinue | ForEach-Object FullName)
    foreach ($p in $candidates) { if (Test-Path $p) { return $p } }
    return $null
}

$MysqlExe     = Find-MySqlBin "mysql"
$MysqldumpExe = Find-MySqlBin "mysqldump"

if (-not $MysqlExe)     { Write-Err "找不到 mysql.exe，请先装 MySQL 客户端，或把 bin 目录加到 PATH" }
if (-not $MysqldumpExe) { Write-Err "找不到 mysqldump.exe" }

Write-Log "mysql:     $MysqlExe"
Write-Log "mysqldump: $MysqldumpExe"

# 2. SQL 文件检查
if (-not (Test-Path $SqlFile)) { Write-Err "SQL 文件不存在：$SqlFile" }
$sqlSizeMb = [math]::Round((Get-Item $SqlFile).Length / 1MB, 1)
Write-Log "SQL 文件：$SqlFile ($sqlSizeMb MB)"

# 3. 询问密码
if (-not $DbPass) {
    $sec = Read-Host "请输入 MySQL 密码（$DbUser@$DbHost）" -AsSecureString
    $DbPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}

# 用环境变量传密码，避免命令行明文
$env:MYSQL_PWD = $DbPass

# 4. 测试连接
Write-Log "测试 MySQL 连接 ..."
$null = & $MysqlExe -h$DbHost -P$DbPort -u$DbUser -e "SELECT 1;" 2>&1
if ($LASTEXITCODE -ne 0) { Write-Err "MySQL 连接失败，确认 host/port/用户名/密码" }
Write-Log "✅ MySQL 连接 OK" "Green"

# 5. 创建数据库（如不存在）
Write-Log "确认数据库 ``$DbName`` 存在 ..."
& $MysqlExe -h$DbHost -P$DbPort -u$DbUser -e `
    "CREATE DATABASE IF NOT EXISTS ``$DbName`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Err "创建数据库失败" }
Write-Log "✅ 数据库就绪" "Green"

# 6. 解析 SQL 文件中的表名（流式扫描，不全量读入内存）
Write-Log "扫描 SQL 文件，提取表名 ..."
$sqlTables = New-Object System.Collections.Generic.HashSet[string]
$reader = [System.IO.StreamReader]::new($SqlFile, [System.Text.Encoding]::UTF8)
try {
    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ($line -match '^DROP TABLE IF EXISTS `([^`]+)`') {
            [void]$sqlTables.Add($Matches[1])
        }
    }
} finally {
    $reader.Close()
}
$sqlTablesArr = $sqlTables | Sort-Object
Write-Log "SQL 文件包含 $($sqlTablesArr.Count) 张表"

# 7. 查询目标库现有表
Write-Log "查询目标库现有表 ..."
$existingTables = & $MysqlExe -h$DbHost -P$DbPort -u$DbUser -N -B -e `
    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DbName';" 2>$null
if ($null -eq $existingTables) { $existingTables = @() }
$existingArr = @($existingTables | Where-Object { $_ -ne "" } | Sort-Object)
Write-Log "目标库现有 $($existingArr.Count) 张表"

# 8. 计算差异
$existingSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$existingArr)
$sqlSet      = [System.Collections.Generic.HashSet[string]]::new([string[]]$sqlTablesArr)

$toReplace = $sqlTablesArr | Where-Object { $existingSet.Contains($_) }
$toCreate  = $sqlTablesArr | Where-Object { -not $existingSet.Contains($_) }
$keepAsIs  = $existingArr  | Where-Object { -not $sqlSet.Contains($_) }

Write-Host ""
Write-Host "================ 导入计划 ================" -ForegroundColor Yellow
Write-Host "  目标库：$DbName @ ${DbHost}:${DbPort}"
Write-Host "  SQL 文件：$SqlFile ($sqlSizeMb MB)"
Write-Host "  ----------------------------------------"
Write-Host ("  ⚠️  将被【覆盖】的旧表：{0} 张" -f $toReplace.Count) -ForegroundColor Yellow
Write-Host ("  ✨ 将被【新建】的表  ：{0} 张" -f $toCreate.Count) -ForegroundColor Green
Write-Host ("  ✅ 保持【不动】的表  ：{0} 张" -f $keepAsIs.Count) -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Yellow

if ($toReplace.Count -gt 0) {
    Write-Host ""
    Write-Host "—— 将被覆盖的表（旧数据被替换为 SQL 文件里的最新版）——" -ForegroundColor Yellow
    $toReplace | ForEach-Object { Write-Host "    - $_" }
}
Write-Host ""

$ans = Read-Host "继续导入？(yes / no)"
if ($ans -ne "yes") { Write-Log "已取消"; exit 0 }

# 9. 备份将要被覆盖的表
$backupFile = $null
if (-not $NoBackup -and $toReplace.Count -gt 0) {
    if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $backupFile = Join-Path $BackupDir "${DbName}_pre_import_${stamp}.sql"

    Write-Log "备份将被覆盖的 $($toReplace.Count) 张表 → $backupFile"
    $argsList = @(
        "-h$DbHost", "-P$DbPort", "-u$DbUser",
        "--single-transaction", "--quick", "--skip-lock-tables",
        "--default-character-set=utf8mb4",
        "--column-statistics=0",
        $DbName
    ) + $toReplace
    & $MysqldumpExe @argsList > $backupFile 2> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Err "备份失败（如不需要备份，加 -NoBackup 重试）"
    }
    $backupSizeMb = [math]::Round((Get-Item $backupFile).Length / 1MB, 1)
    Write-Log "✅ 备份完成（$backupSizeMb MB）" "Green"
} else {
    Write-Log "ℹ️  跳过备份（NoBackup 或没有要覆盖的表）"
}

# 10. 执行导入
Write-Log "开始导入 SQL 文件，请耐心等待 ..." "Yellow"
$startTs = Get-Date

# 用 cmd 的重定向最稳；-cmd 启动会保留原生 stdin 行为
$argsImport = @(
    "-h$DbHost", "-P$DbPort", "-u$DbUser",
    "--default-character-set=utf8mb4",
    "--max-allowed-packet=512M",
    $DbName
)

# 启动 mysql.exe，重定向 stdin = SQL 文件
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $MysqlExe
$psi.Arguments = ($argsImport -join ' ')
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardError = $true
$psi.EnvironmentVariables["MYSQL_PWD"] = $DbPass

$proc = [System.Diagnostics.Process]::Start($psi)

# 流式把 SQL 文件喂给 mysql.exe，并显示进度
$total = (Get-Item $SqlFile).Length
$buf = New-Object byte[] (1MB)
$fs = [System.IO.File]::OpenRead($SqlFile)
$ins = $proc.StandardInput.BaseStream
$readBytes = 0
$lastReport = 0

try {
    while (($n = $fs.Read($buf, 0, $buf.Length)) -gt 0) {
        $ins.Write($buf, 0, $n)
        $readBytes += $n
        # 每读 50MB 报一次进度
        if ($readBytes - $lastReport -ge 50MB) {
            $pct = [math]::Round($readBytes / $total * 100, 1)
            $mb  = [math]::Round($readBytes / 1MB, 0)
            Write-Log ("已发送 {0} MB / {1:N0} MB（{2}%）" -f $mb, ($total/1MB), $pct)
            $lastReport = $readBytes
        }
    }
} finally {
    $fs.Close()
    $ins.Close()
}

$proc.WaitForExit()
$stderr = $proc.StandardError.ReadToEnd()
$elapsed = [math]::Round(((Get-Date) - $startTs).TotalSeconds, 0)

if ($proc.ExitCode -ne 0) {
    Write-Host ""
    Write-Host "MySQL 报错：" -ForegroundColor Red
    Write-Host $stderr -ForegroundColor Red
    Write-Err "导入失败（耗时 $elapsed 秒）"
}
Write-Log "✅ 导入完成（耗时 $elapsed 秒）" "Green"

# 11. 校验：列出 SQL 文件中表的当前行数
Write-Log "导入后校验 ..." "Cyan"
$inList = ($sqlTablesArr | ForEach-Object { "'$_'" }) -join ','
$verifySql = @"
SELECT TABLE_NAME, TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA='$DbName' AND TABLE_NAME IN ($inList)
ORDER BY TABLE_NAME;
"@

Write-Host ""
Write-Host "—— SQL 文件中表的行数（导入后实际值）——" -ForegroundColor Cyan
& $MysqlExe -h$DbHost -P$DbPort -u$DbUser -e $verifySql

Write-Host ""
Write-Log "🎉 全部完成" "Green"
if ($backupFile) { Write-Log "如需回滚，备份文件：$backupFile" }

# 清理环境变量里的密码
Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
