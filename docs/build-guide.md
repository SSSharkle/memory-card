# 构建指南

## 环境

- Windows PowerShell 或支持 Bash 的环境；项目提供 Gradle Wrapper 8.11.1。
- JDK 21。`core` 使用 Java 21 toolchain；Android app 的 Java/Kotlin 字节码目标为 17。
- Android SDK Platform 35 和 Build Tools 35.0.0；应用 `minSdk` 为 26、`targetSdk` 为 35。

SDK 路径由本机忽略文件 `local.properties` 中的 `sdk.dir` 提供。不要提交该文件，也不要把本机 SDK 路径写进 `gradle.properties`。设置 `JAVA_HOME` 指向 JDK 21；脚本 `scripts/build-release.ps1` 也可用 `-JavaHome` 和 `-SdkRoot` 显式传入路径。换机器时先安装相应 SDK 组件并更新本机路径。

## 构建和测试

构建 debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。安装到已连接设备或模拟器：

```powershell
.\gradlew.bat :app:installDebug
```

运行核心 JVM 测试：

```powershell
.\gradlew.bat :core:test
```

Room、仓储和界面设备测试是 Android instrumentation 测试，需启动兼容的模拟器或连接设备后运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

也可通过 `adb shell am instrument` 指定某个已安装的设备测试 APK。`app:testDebugUnitTest` 不运行 Room 仓储 instrumentation 测试。API 26 及 API 35 的已执行检查和待复验项目见[测试报告](test-report.md)。

## 发布构建

Windows 上运行：

```powershell
.\scripts\build-release.ps1
```

脚本调用 `:core:test :app:lintRelease :app:assembleRelease`，然后把签名 APK 复制到 `dist/考研闪卡-1.0.0.apk` 并生成 `dist/SHA256SUMS.txt`。它使用 JDK 21 和本机 Android SDK。macOS/Linux 可分别运行这些 Gradle 任务并自行完成 APK 复制与 SHA-256 校验。

签名密钥和密码保存在被忽略的 `.signing/` 目录（`release.jks`、`signing.properties`）。新克隆没有 `.signing/` 时，脚本会自动生成一把新的本地密钥；这把密钥只代表该本地构建身份，不能用于升级由另一把密钥签名的已发行 APK。若要发布可覆盖安装并延续现有应用签名身份的版本，必须从安全备份恢复原发行密钥和密码。现有工作区的签名材料应限制访问并安全备份；遗失密钥会影响后续以相同签名身份升级应用。不要提交、公开或写入日志，也不要删除现有密钥。脚本检测到只有密码文件而没有密钥文件时会停止，需先恢复匹配的原密钥。

## Windows 非 ASCII 路径排错

如果 Gradle 测试工作进程因工作区或 Gradle 用户缓存路径含非 ASCII 字符而报类找不到，可临时建立仅含 ASCII 字符的目录联接，并从联接路径运行。目标目录需替换为本机存在的路径：

```powershell
New-Item -ItemType Junction -Path 'D:\exam-flashcards' -Target (Get-Location).Path
New-Item -ItemType Junction -Path 'D:\gradle-cache' -Target "$env:USERPROFILE\.gradle"
$env:GRADLE_USER_HOME = 'D:\gradle-cache'
Set-Location 'D:\exam-flashcards'
.\gradlew.bat :core:test
```

这仅用于处理特定 Windows 路径问题，不是项目构建的常规前置条件。
