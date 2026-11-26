# CarPlay 构建与安装
#
#   make dist            编译 Debug APK 并复制到 dist/
#   make install         编译后通过 adb 安装到已连接设备
#   make install DEVICE=<serial>   指定设备
#   make clean           清理构建产物和 dist/
#
# Windows 下需要 GNU make（winget install ezwinports.make）。

GRADLEW      := .\gradlew.bat
APK_DIR      := app/build/outputs/apk/debug
DIST         := dist
ADB          ?= D:\code\guki\tools\android-sdk\platform-tools\adb.exe
DEVICE       ?=
JAVA_HOME    ?= D:\code\guki\tools\jdk25\jdk-25.0.5.1+2
GRADLE_USER_HOME ?= D:\code\guki\tools\gradle-home
PS           := powershell -NoProfile -ExecutionPolicy Bypass -Command

.DEFAULT_GOAL := dist
.PHONY: all build dist release install install-release clean version

all: dist

## 编译 Debug APK
build:
	$(PS) "$$env:JAVA_HOME='$(JAVA_HOME)'; $$env:GRADLE_USER_HOME='$(GRADLE_USER_HOME)'; & '$(GRADLEW)' :app:assembleDebug --offline --console=plain --no-daemon"

## 编译并把 APK 复制到 dist
dist: build
	$(PS) "New-Item -ItemType Directory -Force '$(DIST)' | Out-Null; Copy-Item '$(APK_DIR)/*.apk' '$(DIST)/' -Force; Get-ChildItem '$(DIST)/*.apk' | Select-Object Name,Length,LastWriteTime"

## 构建已签名的 Release APK 并复制到 dist
release:
	$(PS) "$$env:JAVA_HOME='$(JAVA_HOME)'; $$env:GRADLE_USER_HOME='$(GRADLE_USER_HOME)'; & '$(GRADLEW)' :app:assembleRelease --offline --console=plain --no-daemon"
	$(PS) "New-Item -ItemType Directory -Force '$(DIST)' | Out-Null; Copy-Item 'app/build/outputs/apk/release/*.apk' '$(DIST)/CarPlay-release.apk' -Force; Get-Item '$(DIST)/CarPlay-release.apk' | Select-Object Name,Length,LastWriteTime"

## 安装到设备（默认第一个设备）
install: dist
	$(PS) "$$adb='$(ADB)'; $$dev='$(DEVICE)'; $$apk=(Get-ChildItem '$(DIST)/*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName; if (-not $$apk) { throw 'dist 中没有 APK' }; if ($$dev) { & $$adb -s $$dev install -r $$apk } else { & $$adb install -r $$apk }"

## 安装 Release 包到设备
install-release:
	$(PS) "$$adb='$(ADB)'; $$dev='$(DEVICE)'; $$apk='$(DIST)/CarPlay-release.apk'; if (-not (Test-Path $$apk)) { throw 'dist 中没有 Release APK，先执行 make release' }; if ($$dev) { & $$adb -s $$dev install -r $$apk } else { & $$adb install -r $$apk }"

## 输出当前版本号
version:
	$(PS) "(Select-String -Path 'app/build.gradle.kts' -Pattern 'appVersionName = \"(.*)\"').Matches.Groups[1].Value"

## 清理构建产物与 dist
clean:
	$(PS) "$$env:JAVA_HOME='$(JAVA_HOME)'; $$env:GRADLE_USER_HOME='$(GRADLE_USER_HOME)'; & '$(GRADLEW)' clean; if (Test-Path '$(DIST)') { Remove-Item '$(DIST)' -Recurse -Force }"
