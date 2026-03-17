# Project Technical Specification (技术法典)

> **定位声明**：本项目最高技术准则，作为 `/fr.check`审计的终极依据。

## 1. 🛠️ Tech Stack & Toolchain 技术栈与工具链

- **Runtime**: JVM (Kotlin 1.9+), Node.js (Extension Host)
- **Frameworks**: IntelliJ Platform SDK, React (Webview UI)
- **Toolchain**: Gradle (Kotlin DSL), npm/pnpm, tsup
- **Key Dependencies**: JCEF (Chromium), RPC over Sockets/UDS

## 2. 🏗️ Architecture & Patterns 架构与模式

- **Pattern**: Master-Slave (IntelliJ Plugin as Master, Node.js Extension Host as Slave)
- **Key Rules**: 
  - 所有 VSCode API 必须在 Kotlin 中实现为 `MainThread...Shape` 并通过 RPC 桥接。
  - UI 容器使用 Swing，内容使用 JCEF 渲染 Webview。
  - 通讯使用 `JBCefJSQuery` (Web-to-Kotlin) 和 `executeJavaScript` (Kotlin-to-Web)。

## 3. 📐 Engineering Redlines 工程红线

- **Must (保守修改)**: 默认增量式、最小化修改。严禁擅自大规模重构。
- **Must (测试驱动)**: 新功能必须同步产出单元测试。未测试代码视为“未完成”。
- **Forbidden (复杂度)**: 单文件严禁超过 **500 行**；复杂逻辑必须模块化拆解。
- **Forbidden**: 严禁硬编码敏感信息；严禁引入未经声明的核心依赖。
- **Confirm**: 涉及架构调整或新增核心依赖时，必须向用户确认。

## 4. 🛡️ Quality Gates & Standards 质量门禁 (DoD)

- **Testing**: 单元测试覆盖率必须 **> 80%**。
- **Security**: 禁止硬编码；RPC 数据传输避免使用语言特定类型（如 Kotlin Pair/Triple），改用 List/Map。
- **Audit**: 所有变更必须通过 `fr.check` 的全链路一致性审计。

## 5. 📦 Namespace & Isolation 命名空间与隔离

- **Kotlin RPC**: 方法名使用 `camelCase`，严禁添加 `$` 前缀。
- **Build Modes**: 通过 `-PdebugMode` 控制 (`idea`, `release`, `none`)。

## 6. 🧭 Project Context 项目上下文

- **Current Phase**: Initial Bootstrapping
- **SSoT Hierarchy**: `spec.md` 为业务契约，本文档为技术宪法。

---

<details>
<summary>🤖 AI Assistant Guide</summary>

## 协作准则

1. **初始化引导**：在项目启动时，填充上述 {{SLOTS}}。
2. **实时对齐**：在执行 `/fr.design` 或 `/fr.coding` 前，必须读取本文档，确保方案不偏离架构设计。
3. **冲突决策**：若探测到实际代码与本文档规范冲突，优先以本文档为准，并提醒用户修复代码或更新规范。

</details>

### [APPEND:2026-03-17] [[fr001]] Delta
- **[Command Bridge]**: 所有 `vscode.*` 命名空间的核心命令应通过 `MainThreadCommands` 进行标准化映射。 (Ref: fr001)
