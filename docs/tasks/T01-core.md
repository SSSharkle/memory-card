# T01–T04 核心开发

owner: core_sol (gpt-6.1-sol/high)
files: 所有生产代码、构建配置、core/app测试、工具脚本；不得修改Luna或主协调拥有的文档。
parallel_group: core
depends_on: 主协调SDK准备（构建依赖，编码可并行）

分步：T01工程与FSRS；T02数据与复习；T03全部内容与备份功能；T04构建、修复和核心审查。各步完成后报告实际路径、命令及输出。

acceptance: Gradle核心测试、app测试/数据测试、lint、assembleDebug/assembleRelease及签名验证；覆盖FSRS参考对照、导入、评分原子性和重复提交、重启/兄弟卡/限额、备份坏文件与往返、10k数据。

escalation: 核心问题由Sol/high直接处理；缺少工具或联网依赖尽早告知主协调。
notes: 不夸大已验证范围；设备/模拟器未执行必须明确。MIT原作者许可保留，私钥和本地工具gitignore。
