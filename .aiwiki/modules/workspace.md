---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：workspace

# 模块：workspace

工作空间管理模块，负责沙箱文件系统、命令执行、PRoot 容器与知识库操作。

模块以 `WorkspaceManager` 为核心，管理生命周期与文件 CRUD，`WorkspaceFileSystem` 提供安全路径解析、通配匹配和内容搜索。`ProotShellRunner` 通过 JNI PTY 伪终端在 PRoot 环境中执行 Linux 命令，`RootfsInstaller` 与 `RootfsPatcher` 完成根文件系统下载、解压与修补。`KnowledgeSpaceManager` 基于 `WorkspaceManager` 实现知识导入、中文搜索和引用溯源。模块依赖 `kotlinx-serialization`、`xz` 及原生 C++ 库（termux PTY）。

修改指引：普通文件操作调整从 `WorkspaceFileSystem` 入手，环境执行变更从 `ProotShellRunner` 或 `WorkspaceShellRunner` 入手，新增知识能力扩展 `KnowledgeSpaceManager`。

## 文件摘要

### `workspace/build.gradle.kts`

Android 库模块构建脚本，配置 Android 库、Kotlin 序列化与 C++ 原生构建。
- `plugins`：android.library, kotlin.serialization
- `android`：namespace=me.rerere.workspace, compileSdk=37, minSdk=26, Java 11, CMake
- `dependencies`：appcompat, core-ktx, material, serialization.json, xz

### `workspace/consumer-rules.pro`

```
- 空 ProGuard 消费者规则文件，无自定义保护条目。
```

### `workspace/proguard-rules.pro`

Android ProGuard 规则配置文件，当前仅含注释及示例未启用规则。  
- 无活跃规则：所有配置均为注释或示例说明。

### `workspace/src/androidTest/java/me/rerere/workspace/ExampleInstrumentedTest.kt`

Android 仪表化测试示例，验证应用上下文包名正确。
- `ExampleInstrumentedTest`：仪表化测试类
- `useAppContext`：检查 context 包名是否为 `me.rerere.workspace.test`

### `workspace/src/main/AndroidManifest.xml`

Android 应用清单文件，当前为空，未定义任何组件或权限。  
- 无关键条目。

### `workspace/src/main/cpp/CMakeLists.txt`

Android NDK 原生构建配置，定义 workspace 与 termux 共享库。
- `workspace` 库：SHARED，源文件 workspace.cpp，链接 android 和 log
- `termux` 库：SHARED，源文件 termux_pty.cpp，链接 log

### `workspace/src/main/cpp/termux_pty.cpp`

```markdown
通过JNI创建和管理PTY伪终端子进程。
- `copy_java_string`：复制Java字符串到C字符串
- `copy_java_string_array`：复制Java字符串数组
- `open_pty_master`：打开PTY主设备
- `Java_com_termux_terminal_JNI_createSubprocess`：创建子进程并返回PTY主FD
- `Java_com_termux_terminal_JNI_setPtyWindowSize`：设置PTY窗口大小
- `Java_com_termux_terminal_JNI_waitFor`：等待子进程退出并返回状态
- `Java_com_termux_terminal_JNI_close`：关闭FD
```

### `workspace/src/main/cpp/workspace.cpp`

C++原生库模板文件，仅含加载注释，无实际代码。
- 无关键符号

### `workspace/src/main/java/me/rerere/workspace/KnowledgeSpaceManager.kt`

基于 `WorkspaceManager` 的知识空间，提供初始化、导入、搜索与读取。  
- `KnowledgeSpaceManager`：主管理类  
- `KnowledgeSpaceStatus`：空间状态  
- `KnowledgeImportResult`：导入结果  
- `KnowledgeSearchMatch`：搜索结果项  
- `KnowledgeSearchResult`：搜索结果集  
- `KnowledgeReadResult`：读取结果  
- 伴生常量：目录路径、搜索/读取限制

### `workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt`

```markdown
PRoot 环境执行器，用于在 Android 上运行 Linux 命令。
- `WorkspaceBindMount`：指定绑定挂载源路径与目标
- `ProotShellRunner`：实现 WorkspaceShellRunner，通过 proot 执行命令
- `execute`：核心执行入口，检查 rootfs 并启动 proot 进程
- `PROOT_EXEC`/`PROOT_LOADER`：proot 可执行文件及加载器路径
- `WORKSPACE_DIR`：容器内工作区挂载点
```

### `workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt`

下载根文件系统归档（tar.gz/xz）并解压到工作空间，含进度回调。
- `RootfsInstaller`：主安装器，管理下载、解压、补丁
- `install`：执行完整安装流程
- `download`：HTTP 下载，分段进度上报
- `extractTar`：解析 tar，处理长名/Pax/符号硬链接
- `ArchiveFormat`：枚举 TAR_GZ/TAR_XZ，自动从 URL 判断格式

### `workspace/src/main/java/me/rerere/workspace/RootfsPatcher.kt`

修补 Linux 根文件系统（DNS、hosts、hostname、locale、group、临时目录）。
- `RootfsPatcher`：根文件系统修补器，`patch` 方法应用所有修补。
- `RootfsPatchOptions`：修补选项（nameservers、hostname、locale、groupIds）。

### `workspace/src/main/java/me/rerere/workspace/Workspace.kt`

工作空间数据模型定义，包含状态、文件、命令结果等数据结构。
- `Workspace`：工作空间主数据类
- `WorkspaceShellStatus`：Shell状态枚举
- `WorkspaceStorageArea`：存储区域枚举
- `RootfsInstallStage`：根文件系统安装阶段
- `RootfsInstallProgress`：安装进度
- `WorkspaceConfig`：配置限制
- `WorkspaceFileEntry`：文件条目
- `WorkspaceSearchMatch`：搜索匹配
- `WorkspaceCommandResult`：命令执行结果

### `workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt`

沙箱文件系统封装，提供文件增删改查、模式匹配与内容搜索。
- `WorkspaceFileSystem`：文件系统操作类
- `list`：列表目录条目
- `readText`/`writeText`：文本读写
- `importBytes`：流式导入文件
- `delete`：删除文件/目录
- `move`：移动文件/目录
- `glob`：通配符匹配文件
- `grep`：内容搜索（支持正则）
- `resolve`：安全路径解析

### `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt`

管理工作区生命周期：创建/删除、文件CRUD、命令执行与临时目录清理。
- `WorkspaceManager`：工作区管理核心类
- `ensureWorkspace`：初始化工作区目录结构
- `listFiles`/`readText`/`writeText`：文件列表与读写
- `executeCommand`：在文件区执行shell命令
- `cleanupAllTempDirs`：清理所有临时目录
- `importFile`/`exportFile`：文件导入导出

### `workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt`

执行 shell 命令，管理超时、输出截断与进程清理。
- `WorkspaceShellRunner`：shell 执行接口
- `HostShellRunner`：本地进程实现
- `WorkspaceShellContext`：执行上下文参数
- `MAX_OUTPUT_CHARS`：输出截断上限
- `Process.readResult`：收集 stdout/stderr 并处理超时

### `workspace/src/test/java/me/rerere/workspace/ExampleUnitTest.kt`

单元测试，验证工作区文件系统、命令执行、rootfs 安装与补丁逻辑。
- `ExampleUnitTest`：测试类，包含文件操作、命令执行、rootfs 等用例。
- `tarGz`/`tarHeader`：生成 tar.gz 测试归档。
- `TarTestEntry`：测试用 tar 条目数据类。

### `workspace/src/test/java/me/rerere/workspace/KnowledgeSpaceManagerTest.kt`

测试知识空间管理器的初始化、导入、搜索及路径安全  
- `KnowledgeSpaceManagerTest`：测试类  
- `initializeIsIdempotentAndPreservesProjectFile`：验证初始化幂等且保留项目文件  
- `importedChineseContentCanBeSearchedAndReadWithSourceCitation`：测试中文内容导入后可搜索与阅读  
- `duplicateImportsNeverOverwriteExistingSource`：重复导入不覆盖原文件  
- `readRejectsPathsOutsideKnowledgeBoundary`：读取拒绝越界路径

### `workspace/src/test/java/me/rerere/workspace/RootfsInstallerTest.kt`

测试`RootfsInstaller`的tar.gz提取及过滤逻辑。
- `RootfsInstallerTest`：测试类
- `extract skips OTHER entry data exactly once`：验证跳过OTHER(S)条目
- `extract handles directories and zero size entries`：验证目录与零大小条目
- `TAR_BLOCK`：tar块大小常量(512)
