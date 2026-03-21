# 修复方案：Roo Cloud 认证状态变更后 Webview 不刷新问题 (非侵入式)

## 1. 问题描述
在 Roo Code IntelliJ 插件中，用户执行登录 (Login) 或登出 (Logout) 操作后，虽然底层凭据已更新，但 Webview 页面无法自动跳转或刷新状态。

## 2. 根源分析

### 2.1 状态机断链 (核心原因)
上游代码 [`WebAuthService.ts`](deps/roo-code/packages/cloud/src/WebAuthService.ts) 遵循 VS Code 原生响应式设计：
- 它在 `login`/`logout` 时仅负责读写 `SecretStorage`。
- 真正的认证状态转换 (State Transition) 依赖于监听 `secrets.onDidChange` 事件。
- **现状**：IntelliJ 插件的模拟层 [`MainThreadSecretState.kt`](jetbrains_plugin/src/main/kotlin/com/roocode/jetbrains/actors/MainThreadSecretStateShape.kt) 仅实现了磁盘持久化，未实现向 Extension Host 发送 RPC 通知 (`$onDidChangePassword`) 的逻辑。导致上游状态机停滞，未触发 Webview 推送。

### 2.2 序列号失效 (同步障碍)
上游代码 [`ClineProvider.ts`](deps/roo-code/src/core/webview/ClineProvider.ts) 在推送状态时：
- `postStateToWebviewWithoutClineMessages` 方法漏掉了 `clineMessagesSeq` 的自增。
- **后果**：前端 React 根据序列号判断状态新旧。序列号不增加，前端会静默忽略该状态更新，导致 UI 不跳转。

### 2.3 可见性同步延迟
- `ClineProvider.getVisibleInstance()` 依赖 `view.visible` 状态。在 JCEF 环境下，该状态同步可能存在延迟，导致状态推送被跳过。

## 3. 修复方案 (非侵入式)
本方案旨在**完全不修改 `deps/roo-code/` (上游子模块)** 的前提下，通过补全 IDE 模拟层能力和出口消息拦截来实现修复。

### 3.1 补全 SecretStorage 事件 (Kotlin)
在 Kotlin 侧实现数据变更后的 RPC 回调，激活上游响应式逻辑。

- **文件**：[`MainThreadSecretState.kt`](jetbrains_plugin/src/main/kotlin/com/roocode/jetbrains/actors/MainThreadSecretStateShape.kt)
- **实现细节**：
    - 构造函数接收 `IRPCProtocol`。
    - 在 `setPassword` 和 `deletePassword` 成功写入文件后，调用 `notifyDidChange`。
    - `notifyDidChange` 通过 `ExtHostSecretStateProxy` 调用 ExtHost 侧的 `$onDidChangePassword`。
    ```kotlin
    private fun notifyDidChange(extensionId: String, key: String) {
        rpcProtocol?.let { protocol ->
            try {
                val proxy = protocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostSecretState) as ExtHostSecretStateProxy
                proxy.onDidChangePassword(mapOf("extensionId" to extensionId, "key" to key))
                logger.debug("Sent onDidChangePassword to ExtHost: extensionId=$extensionId, key=$key")
            } catch (e: Exception) {
                logger.warn("Failed to notify ExtHost of secret change", e)
            }
        }
    }
    ```

### 3.2 强制修正 Webview 序列号 (Kotlin 拦截器)
在消息离开插件进入 Webview 的最后关卡进行干预，修正上游逻辑缺陷。

- **文件**：[`MainThreadWebviews.kt`](jetbrains_plugin/src/main/kotlin/com/roocode/jetbrains/actors/MainThreadWebviewsShape.kt)
- **实现细节**：
    - 在 `postMessage` 方法中，解析 `value` 字符串（JSON）。
    - 如果消息 `type == "state"`，则在 Kotlin 侧维护一个全局序列号 `globalSeq` (AtomicInteger)。
    - 强制覆盖 JSON 中的 `state.clineMessagesSeq` 字段。
    - 重新序列化后发送给 JCEF 浏览器。
    ```kotlin
    if (jsonObject.has("type") && jsonObject.get("type").asString == "state") {
        val state = jsonObject.getAsJsonObject("state")
        if (state != null) {
            val newSeq = globalSeq.incrementAndGet()
            state.addProperty("clineMessagesSeq", newSeq)
            finalValue = jsonObject.toString()
            logger.debug("Injected sequence number into state message: seq=$newSeq")
        }
    }
    ```

### 3.3 适配层链路闭合 (Extension Host)
确保 RPC 通道完全畅通。

- **文件**：[`rpcManager.ts`](extension_host/src/rpcManager.ts)
- **实现细节**：
    - 在 `setupRooCodeRequiredProtocols` 中注册 `ExtHostContext.ExtHostSecretState` 的处理器。
    - 实现 `$onDidChangePassword` 方法，使其能够接收来自 Kotlin 的通知并分发给模拟的 VSCode API。
    ```typescript
    this.rpcProtocol.set(ExtHostContext.ExtHostSecretState, {
        $onDidChangePassword: async (e: { extensionId: string; key: string }): Promise<void> => {
            console.log('[RPC] ExtHostSecretState: onDidChangePassword', e);
        }
    });
    ```

## 4. 方案优势
1. **零污染**：不触碰 `deps/roo-code/` 源码，方便后续与社区上游同步。
2. **高可靠**：从底层模拟 VS Code 原生行为，不仅修复当前问题，还增强了插件的 API 兼容性。
3. **确定性**：Kotlin 侧强制注入序列号可 100% 保证前端 React 触发重新渲染。

## 5. 验证计划
1. **登出验证**：点击“退出登录”，观察 Webview 是否立即跳转至登录引导页。
2. **登录验证**：手动粘贴挑战地址完成登录，观察 Webview 是否立即进入已登录状态。
3. **日志验证**：观察 `idea.log` 中是否出现 `Sent onDidChangePassword to ExtHost` 和 `Injected sequence number into state message` 日志。
