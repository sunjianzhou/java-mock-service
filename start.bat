@echo off
chcp 65001 >nul
setlocal

:: ============================================================
:: Mock Service 启动脚本
:: 统一Mock服务 — 身份认证三方接口模拟工具
:: ============================================================

set "JAR_DIR=%~dp0mock-boot\target"
set "JAR_FILE=%JAR_DIR%\mock-boot-1.0.0.jar"

if not exist "%JAR_FILE%" (
    echo [ERROR] JAR 文件不存在: %JAR_FILE%
    echo 请先执行: mvn clean package -DskipTests
    exit /b 1
)

:: 默认端口 8080，可通过参数覆盖: start.bat 9090
set "PORT=8080"
if not "%~1"=="" set "PORT=%~1"

:: JAVA_HOME 检测
if "%JAVA_HOME%"=="" (
    echo [WARN] JAVA_HOME 未设置，将使用系统 PATH 中的 java
    set "JAVA_CMD=java"
) else (
    set "JAVA_CMD=%JAVA_HOME%\bin\java"
)

echo ============================================================
echo  Mock Service 启动中...
echo  JAR: %JAR_FILE%
echo  端口: %PORT%
echo  管理端点: http://localhost:%PORT%/mock/_admin/routes
echo ============================================================

"%JAVA_CMD%" -jar "%JAR_FILE%" --server.port=%PORT%

endlocal
