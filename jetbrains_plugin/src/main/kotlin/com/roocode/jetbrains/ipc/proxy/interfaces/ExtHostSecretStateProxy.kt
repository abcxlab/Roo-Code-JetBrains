package com.roocode.jetbrains.ipc.proxy.interfaces

/**
 * Proxy interface for ExtHostSecretState.
 */
interface ExtHostSecretStateProxy {
    /**
     * Notify extension host that a secret has changed.
     * @param e Event data containing extensionId and key
     */
    fun onDidChangePassword(e: Map<String, String>)
}
