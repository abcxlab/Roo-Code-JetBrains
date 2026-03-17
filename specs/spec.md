# Global Spec Index (SSA Root)

> **[SSA 架构]** 本文件是 Spec-Structured Architecture 的根索引。仅包含全局 Feature Map 和 Domain Index。具体规格请查阅 `specs/domain/` 或 `specs/fr<ID>_<slug>/`。

## 1. Feature Map (Global)

| ID | Feature Name | Status | Owner | Domain |
| :--- | :--- | :--- | :--- | :--- |
| fr001 | 对接 Worktree 打开与切换功能 | Archived | @Qiang | Bridge |

## 2. Domain Index (领域分片索引)

| Domain | Description | Path |
| :--- | :--- | :--- |
| **Bridge** | VSCode API 桥接与模拟层 | `specs/domain/bridge.md` |

## 3. Global Business Rules (全局业务规则)

> **[治理策略]** 本索引仅保留“宪法级”全局原则。具体业务逻辑与技术规约应下沉至 `specs/domain/` 对应的领域分片中。

- **规格先行 (Specs-First)**: 无 `fr.md` 不设计，无 `fd.md` 不编码。
- **主动探测 (Agentic Probing)**: 动手前必须先执行调研。
- **状态回填 (Status Backfill)**: 编码阶段必须实时维护 `frp.md`。

---

<details>
<summary>🤖 AI Assistant Guide</summary>

## 根索引维护指南

### 1. 核心原则：全局视图与层级解耦

- **全局视图**：必须 [维护 Feature 级别的条目] (apply_diff)，严禁记录 Task 级别的细节。
- **层级解耦**：具体业务规则必须 [下沉至 Domain 规格] (read_file)，此处仅保留“宪法级”原则。

### 2. 协作准则

- **正交性守卫**：在分配领域时，必须 [扫描现有 Domain Index] (list_files) 确保领域分片保持正交，避免重叠。
- **状态同步**：在功能生命周期发生重大变更时，应主动建议用户更新 Feature Map 状态。

### 3. 质量检查清单

- [ ] **索引有效性**：所有引用的物理路径是否真实有效？
- [ ] **信息冗余度**：是否成功避免了将详细设计内容记录在根索引中？

</details>
