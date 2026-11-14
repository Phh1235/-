# 采购比价与供应商尽调 Agent —— 一键启动（Windows PowerShell）
# 用法：
#   .\start.ps1            构建并启动 Java（有 Python 就一起拉起），打开演示页
#   .\start.ps1 -SkipBuild 跳过构建，直接用已有 jar
#   .\start.ps1 -NoBrowser 不自动打开浏览器
param(
    [switch]$SkipBuild,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$javaDir = Join-Path $root 'java-backend'
$pyDir = Join-Path $root 'python-agent'
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-Port([int]$port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $task = $client.ConnectAsync('127.0.0.1', $port)
        if ($task.Wait(800) -and $client.Connected) { $client.Close(); return $true }
        $client.Close()
    } catch { }
    return $false
}

function Wait-Port([int]$port, [int]$seconds = 90) {
    for ($i = 0; $i -lt $seconds * 2; $i++) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

Write-Host '=== 采购比价与供应商尽调 Agent ===' -ForegroundColor Cyan

# 1. 环境检查
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw '未找到 java，请先安装 JDK 21 并加入 PATH'
}
if (-not (Test-Port 3306)) {
    throw 'MySQL 未在 127.0.0.1:3306 监听，请先启动 MySQL（默认 root/123456，可用环境变量 DB_USER/DB_PASSWORD 覆盖）'
}
Write-Host '[ok] JDK 与 MySQL 就绪' -ForegroundColor Green

# 2. Python Agent：优先用 .venv，没有就用系统 Python（依赖需已安装）
$pyExe = Join-Path $pyDir '.venv\Scripts\python.exe'
if (-not (Test-Path $pyExe)) {
    $pyCmd = Get-Command python -ErrorAction SilentlyContinue
    if ($pyCmd) { $pyExe = $pyCmd.Source }
}
if ($pyExe -and (Test-Path $pyExe)) {
    & $pyExe -c "import fastapi, uvicorn, httpx, pydantic" 2>$null
    $pyDepsOk = ($LASTEXITCODE -eq 0)
    if (-not $pyDepsOk) {
        Write-Warning "Python 依赖不完整，跳过 Agent。请执行：cd python-agent; python -m venv .venv; .\.venv\Scripts\pip install -r requirements.txt"
    } elseif (Test-Port 8000) {
        Write-Host '[ok] Python Agent 已在运行' -ForegroundColor Green
    } else {
        Write-Host '[..] 启动 Python Agent :8000'
        $env:PYTHONPATH = $pyDir
        Start-Process -FilePath $pyExe `
            -ArgumentList '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8000' `
            -WorkingDirectory $pyDir `
            -RedirectStandardOutput (Join-Path $logDir 'python.out.log') `
            -RedirectStandardError (Join-Path $logDir 'python.err.log') | Out-Null
        if (Wait-Port 8000 40) { Write-Host '[ok] Python Agent 已启动' -ForegroundColor Green }
        else { Write-Warning 'Python Agent 40 秒内未就绪，日志见 logs\python.err.log' }
    }
} else {
    Write-Warning '未找到 Python，跳过 Agent 侧（Java 仍可启动，但比价任务会失败）'
}

# 3. 构建 Java
$jar = Join-Path $javaDir 'target\procure-agent-java-1.0.0.jar'
if (-not $SkipBuild -or -not (Test-Path $jar)) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw '未找到 mvn，请安装 Maven 3.9+，或用 -SkipBuild 直接使用已构建的 jar'
    }
    Write-Host '[..] 构建 Java 后端（首次会下载依赖，约 1-3 分钟）'
    Push-Location $javaDir
    try {
        & mvn -B -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败' }
    } finally {
        Pop-Location
    }
    Write-Host '[ok] 构建完成' -ForegroundColor Green
}

# 4. 启动 Java
if (Test-Port 8080) {
    Write-Host '[ok] 8080 已被占用，视为 Java 后端已在运行' -ForegroundColor Green
} else {
    Write-Host '[..] 启动 Java 后端 :8080'
    Start-Process -FilePath 'java' `
        -ArgumentList '-jar', $jar `
        -WorkingDirectory $javaDir `
        -RedirectStandardOutput (Join-Path $logDir 'java.out.log') `
        -RedirectStandardError (Join-Path $logDir 'java.err.log') | Out-Null
    if (-not (Wait-Port 8080 120)) {
        throw 'Java 后端 120 秒内未就绪，日志见 logs\java.err.log'
    }
    Write-Host '[ok] Java 后端已启动' -ForegroundColor Green
}

$url = 'http://localhost:8080/demo.html'
Write-Host ''
Write-Host "演示页：$url" -ForegroundColor Cyan
Write-Host '账号：demo / demo123    API 文档：http://localhost:8080/swagger-ui.html'
Write-Host "日志目录：$logDir"
if (-not $NoBrowser) { Start-Process $url }
