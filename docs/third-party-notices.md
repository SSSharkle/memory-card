# 第三方声明

本页记录配置中的直接依赖以及锁文件审阅时确认的部分运行时传递依赖，不是完整的解析后依赖清单或传递依赖审计。版本号来自 `gradle/libs.versions.toml`、模块 Gradle 文件和 `app/gradle.lockfile`；Compose UI/Material 3 的具体库版本由 Compose BOM `2025.04.01` 管理。许可正文位于 [`docs/licenses/`](licenses/)；应用设置页读取 `app/src/main/assets/third-party-notices.txt`，其中包含 Apache、BSD 2-Clause、BSD 3-Clause 和 MIT 许可正文。发布构建前仍应检查实际解析的全部依赖、许可证和 NOTICE，并确保发行包能取得适用的声明文本。

## 应用运行依赖

| 依赖 | 配置中声明的版本 | 许可 |
| --- | --- | --- |
| AndroidX Activity Compose | 1.10.1 | Apache License 2.0 |
| AndroidX Lifecycle Runtime Compose / ViewModel Compose | 2.9.0 | Apache License 2.0 |
| AndroidX Compose UI、Material 3、Material Icons Extended | Compose BOM 2025.04.01 | Apache License 2.0 |
| AndroidX Room Runtime / KTX / Compiler | 2.7.1 | Apache License 2.0 |
| AndroidX DataStore Preferences | 1.1.5 | Apache License 2.0 |
| DataStore external-protobuf（锁文件中的运行时传递依赖） | 1.1.5 | BSD 3-Clause License |
| kotlinx.serialization JSON | 1.8.1 | Apache License 2.0 |
| CommonMark Java | 0.24.0 | BSD 2-Clause License |
| SnakeYAML Engine | 2.9 | Apache License 2.0 |
| FSRS-6 Kotlin 移植 | 参考 py-fsrs 6.3.2 | MIT License；文本见 [`FSRS-6-MIT.txt`](licenses/FSRS-6-MIT.txt)，与仓库根许可副本一致 |

CommonMark Java 0.24.0 的 BSD 2-Clause 类型由其 [Maven POM](https://repo.maven.apache.org/maven2/org/commonmark/commonmark/0.24.0/commonmark-0.24.0.pom) 声明，并已对照该版本上游 [`LICENSE.txt`](https://raw.githubusercontent.com/commonmark/commonmark-java/commonmark-parent-0.24.0/LICENSE.txt) 核实；许可文本标注 Copyright (c) 2015, Robin Stocker。SnakeYAML Engine 2.9 的 [Maven POM](https://repo.maven.apache.org/maven2/org/snakeyaml/snakeyaml-engine/2.9/snakeyaml-engine-2.9.pom) 和 kotlinx.serialization JSON 1.8.1 的 [Maven POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/1.8.1/kotlinx-serialization-json-1.8.1.pom) 声明 Apache 2.0。Kotlin 2.1.20 的上游源与许可位于[版本标签](https://github.com/JetBrains/kotlin/tree/v2.1.20)。锁文件还包含 Okio 3.4.0 与 kotlinx-coroutines 1.7.3（Apache 2.0），以及 DataStore external-protobuf 1.1.5（BSD 3-Clause）。其 [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-external-protobuf/1.1.5/datastore-preferences-external-protobuf-1.1.5.pom) 标出 The Android Open Source Project、inception year 2024 和 BSD-3-Clause，但未提供独立 copyright 行；随附文本以该组织和年份作为归属声明。AndroidX 组件的版本和许可类型按其 Google Maven 元数据及 AndroidX 上游许可记录；该 DataStore POM 仅为此组件的来源，不代表完整依赖树。

FSRS-6 调度实现参考 [py-fsrs 6.3.2](https://github.com/open-spaced-repetition/py-fsrs/tree/v6.3.2)；随仓库许可副本与该版本的上游 `LICENSE` 文本逐行一致，标注 Copyright (c) 2022 Open Spaced Repetition。Kotlin 实现源代码注明其改编来源；Android 运行不依赖 Python。

## 构建和测试依赖

Gradle Wrapper 8.11.1、Android Gradle Plugin 8.9.2、Kotlin 2.1.20、KSP 2.1.20-1.0.32 用于构建，不是应用运行依赖。测试配置声明 JUnit 4.13.2、AndroidX Test JUnit 1.2.1、Runner 1.6.2、Core 1.6.1 和 Compose UI test；这些是测试工具，不属于应用功能依赖。JUnit 4.13.2 的 [Maven POM](https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom) 声明 Eclipse Public License 1.0。Room 仓储设备测试通过 Android instrumentation 运行。

[`Apache-2.0.txt`](licenses/Apache-2.0.txt) 是 Apache Software Foundation 发布的标准 Apache License 2.0 正文，来源为 [apache.org/licenses/LICENSE-2.0.txt](https://www.apache.org/licenses/LICENSE-2.0.txt)；它本身不取代各组件自己的版权行或 NOTICE。CommonMark 和 FSRS 正文保留上游许可中的版权文字。Kotlin、AndroidX、SnakeYAML Engine 等组件的具体版权和 NOTICE 以各自对应的上游版本为准；本页不能替代完整许可审计。
