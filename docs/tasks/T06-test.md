# T06 独立测试执行

owner: routine_luna (gpt-6-luna/high)
files: docs/test-report.md（生产代码只读）
parallel_group: serial-validation
depends_on: T04生产代码冻结
description: 按Sol提供的验收命令运行测试、构建、lint和数据性能测试，记录时间、退出码、关键输出、失败复现及未执行设备检查。
acceptance: 实际运行证据完整，失败明确分类并回报主协调；APK存在且签名验证记录齐全。
escalation: 算法、数据库、权限、跨模块和迁移问题直接交Sol/high；常规文档错误局部修复最多两轮。
