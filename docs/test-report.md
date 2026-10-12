# 测试报告

最终分页版在 API 26 和 API 35 模拟器各通过 12 项 Android instrumentation。签名 release APK 在两台模拟器完成恢复/回滚验收；release lint、签名 APK 安装与启动检查也通过。未进行实体手机测试。

## 最终构建与设备 instrumentation

2026-10-12 在 Android 8.0/API 26 模拟器 `emulator-5556` 安装最终分页版 debug APK 和 instrumentation APK，运行：

```powershell
adb -s emulator-5556 shell am instrument -w cn.flashcard.app.test/androidx.test.runner.AndroidJUnitRunner
```

结果为 `OK (12 tests)`，runner 用时 18.117 秒，adb 退出码 0。Android 15/API 35 模拟器 `emulator-5554` 的同一套件也为 `OK (12 tests)`，runner 用时 25.439 秒，退出码 0。两套件均验证真实磁盘 Room 数据库中的 10,000 条记录导入、去重、分页边界、SQL 搜索/筛选和快照检查。各设备计时如下，数值来自模拟器上的这一轮运行，不构成性能保证：

| 设备 | 10k 导入 | 去重 | 全量数据库查询 | 搜索与队列 | 快照与校验 |
| --- | ---: | ---: | ---: | ---: | ---: |
| API 26 | 12,553 ms | 897 ms | 244 ms | 88 ms | 1,338 ms |
| API 35 | 15,154 ms | 1,914 ms | 308 ms | 79 ms | 2,458 ms |

“全量数据库查询”计时为测试中的笔记/卡片读取阶段；“搜索与队列”阶段包含真实的 LIMIT/OFFSET 分页检查。完整命令、安装结果、测试摘要及退出码见 [API 26 日志](validation/api26-final-instrumentation.log) 和 [API 35 日志](validation/api35-final-instrumentation.log)；性能输出原文分别见 [API 26](validation/api26-10k-performance.log) 与 [API 35](validation/api35-10k-performance.log)。

最终 Gradle 验证运行 `:core:test :app:lintRelease :app:assembleRelease`，构建成功，耗时 2 分 1 秒，退出码 0。`lintRelease` 报告 `No issues found`；`core:test` 在该轮为 `UP-TO-DATE`，因此此轮未重新执行 JVM 测试。冻结版已有 XML 结果显示 CoreTest 5 项、OracleTest 1 项通过，失败 0 项；Oracle 用例覆盖 132 组 py-fsrs 6.3.2 参考状态。原始结果见 [CoreTest XML](validation/core-TEST-cn.flashcard.core.CoreTest.xml)、[OracleTest XML](validation/core-TEST-cn.flashcard.core.OracleTest.xml)，Gradle 输出见[验证日志](validation/final-gradle-validation.log)。

最终发布 APK 的 SHA-256 为 `9ecb8bf90e4e6285d332416ae9b833fb4f57c0e423e826ec7ddd9c2cef7ccad7`。本轮 Gradle 验证前后 `dist/考研闪卡-1.0.0.apk` 的哈希一致。签名 v2 校验及权限检查记录见[签名验证日志](validation/signed-release-verification.log)。debug 测试结束后，在 API 35 卸载 debug 包并重新安装签名 release APK；应用进程成功启动，启动后没有匹配到 `AndroidRuntime` 崩溃记录，见[release 启动日志](validation/api35-final-release-launch.log)。

## 最终签名 APK 人工走查

协调者在 API 35 模拟器导入包含 10,000 条 UTF-8 Markdown 笔记的 QA 文件，预览和导入结果均为 10,000 条笔记/卡片。导入后总计 10,003 条笔记、10,004 张卡；列表首次显示 50 条，搜索 `999` 得到 19 条结果，清除搜索后滚动到底部点“加载更多（50 条）”，加载计数变为 100。检查了暗色主题下的系统状态栏和导航栏颜色。相关截图为 [万条笔记首屏](screenshots/release-api35-10000-firstpage.png) 和[暗色主题设置](screenshots/release-api35-dark-settings.png)，归档的界面 XML 见 [`validation/`](validation/)。

API 35 导出的跨设备 ZIP 为 `exam-flashcards` 格式版本 1，内有 10,003 条笔记、10,004 张卡、1 条复习记录和暗色主题设置。协调者在 API 26 签名 release APK 中恢复此 ZIP，核对上述数量、50 条首屏、暗色主题设置以及 1 次评分/19 张新卡剩余；随后执行安全回滚，确认恢复到原有演示数据（3 条笔记、4 张卡、0 条复习）和浅色默认设置。API 26 最终页和恢复/回滚界面见[截图](screenshots/release-api26-cross-restore.png)；对应 UI XML 已归档在 [`validation/`](validation/)。

最终 10k 搜索与加载更多、暗色主题、ZIP 导出和恢复后的 API 35 检查，以及 API 26 恢复/回滚画面分别见 [API 35 10k 完成](validation/ui35-release-10k-done.xml)、[API 35 搜索清除](validation/ui35-release-search-cleared.xml)、[API 35 已加载 100 条](validation/ui35-release-loaded100.xml)、[API 35 暗色主题](validation/ui35-release-dark-stable.xml)、[API 35 导出完成](validation/ui35-release-export-done.xml)、[API 26 跨设备恢复](validation/ui26-release-cross-restore-success.xml)和[API 26 回滚完成](validation/ui26-release-rollback-finished.xml)。

更早的中间 APK API 35 走查使用 `samples/invalid/missing-back.md` 检查错误提示；具体是缺少 `### 背面`，错误指向第 7 行，并非缺少结束标记。早期成功/失败运行记录保留在[历史日志](validation/api26-instrumentation-history.log)，不与最终构建结果混用。

## 验收边界

API 26 和 API 35 的测试均使用模拟器，未做实体手机验收。API 35 手动走查由协调者执行；本报告记录其观察和已归档证据。
