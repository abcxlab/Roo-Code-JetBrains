# 修复方案：Roo Code Cloud 协议处理器 (Deep Link) 失效问题

## 1. 问题描述
当前 Roo Code IntelliJ 插件在集成 Roo Code Cloud 功能时，无法正确处理浏览器跳回的 Deep Link（例如 `idea://WeCode-AI.RunVSAgent.roo-cline/auth/clerk/callback`）。
虽然 `idea.log` 显示 IDE 核心层已接收到 `external URI request`，但插件定义的 `RooCodeProtocolHandler` 未被触发，导致登录流程中断。

## 2. 根源分析
在 `RooCodeProtocolHandler.kt` 的实现中，`executeAndGetResult` 方法被错误地声明为了 `suspend` 函数：

```kotlin
// 错误的代码片段
override suspend fun executeAndGetResult(
    target: String?,
    parameters: Map<String, String>,
    fragment: String?
): JBProtocolCommandResult? { ... }
```

**失效原因：**
1. **签名不匹配**：IntelliJ 平台的基类 `JBProtocolCommand` 是 Java 编写的，其 `executeAndGetResult` 是一个同步方法。
2. **Kotlin 编译特性**：Kotlin 中的 `suspend` 函数在编译后会增加一个 `Continuation` 参数。这导致该方法在字节码层面**没有真正覆盖（Override）**基类的方法。
3. **静默失效**：IDE 在运行时调用的是基类的默认实现（空操作），因此插件逻辑从未执行。

## 3. 修复方案

### 3.1 修正方法签名
移除 `suspend` 关键字，确保与基类签名完全一致，实现真正的同步覆盖。

### 3.2 异步分发桥接
由于分发 URI 需要通过 RPC 调用（异步操作），我们将利用 `PluginContext` 提供的协程作用域（`CoroutineScope`）来启动一个异步任务，从而在不阻塞 IDE 协议处理线程的情况下完成转发。

### 3.3 核心代码修正预览
```kotlin
class RooCodeProtocolHandler : JBProtocolCommand("WeCode-AI.RunVSAgent.roo-cline") {
    
    // 移除 suspend，改为同步方法
    override fun executeAndGetResult(
        target: String?,
        parameters: Map<String, String>,
        fragment: String?
    ): JBProtocolCommandResult? {
        logger.info("Received protocol command: target=$target")

        val extensionId = "WeCode-AI.RunVSAgent.roo-cline"
        val parsedUri = reconstructUri(target, parameters, fragment)

        // 获取所有打开的项目，并利用其 PluginContext 异步分发
        val projects = ProjectManager.getInstance().openProjects
        for (project in projects) {
            val pluginContext = project.getService(PluginContext::class.java)
            // 使用插件自带的协程作用域进行异步 RPC 调用
            pluginContext?.scope?.launch {
                dispatchUri(project, extensionId, parsedUri)
            }
        }

        return JBProtocolCommandResult(null) // 返回成功状态
    }
}
```

## 4. 验证计划
1. **编译验证**：确保移除 `suspend` 后代码编译通过，且 IDE 插件加载正常。
2. **日志验证**：
    - 点击登录后，观察 `idea.log` 是否出现 `Received protocol command`。
    - 观察是否出现 `Successfully dispatched URI to project '...'`。
3. **业务验证**：确认浏览器跳回后，Roo Code 插件 Webview 状态更新为“已登录”。
