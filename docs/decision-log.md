# 决策记录

- 2026-10-12：用户授权实施完整计划并指定Sol/high核心开发、Luna/high常规任务与测试；使用子代理，不创建额外用户聊天。
- 2026-10-12：从空目录开始。采用AGP 8.9.2 / Gradle 8.11.1 / Kotlin 2.1.20 / Compose BOM 2025.04.01，compile/target SDK35、minSDK26。Java21已存在，SDK由主协调安装至项目.tooling/android-sdk。
- 2026-10-12：FSRS-Kotlin未作为独立依赖采用；使用MIT py-fsrs v6.3.2作为Kotlin移植和测试对照，运行时不依赖Python。
- 2026-10-12：Windows模拟器在中文SDK/AVD路径下停滞，改用英文NTFS junction运行：D:/kaoyan-android-sdk映射项目.tooling/android-sdk，D:/kaoyan-android-avd映射.tooling/avd-ascii。项目目录保持原位。API35与API26模拟器均已启动，分别为emulator-5554与emulator-5556；WHPX加速可用。此环境修复尚不代表App设备验收通过。
- 2026-10-12：fsrs==6.3.2 Python包安装至项目.tooling/python-packages，仅供对照验证；开发时使用Codex本地依赖Python，不改变系统Python配置。

- 2026-10-12：API26中间版本12项设备测试通过；API35人工中文导入/重复跳过/错误定位/多填空、评分重启、输入答案、统计、ZIP恢复及安全备份回滚通过。暗色状态栏、路径规范化和显式备份版本字段修复交Sol；最终版复验中。
- 2026-10-12：验收要求中的万条分页检查必须使用数据库实际分页；撤回仅LazyColumn惰性渲染的临时交付口径，Sol正在补SQL LIMIT/OFFSET和每50条加载更多、真实磁盘Room万条测试。最终发布以补齐分页后的APK为准。
