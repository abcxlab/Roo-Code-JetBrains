package com.roocode.jetbrains.util

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Utility for detecting the runtime environment, specifically virtual machines.
 */
object EnvironmentUtil {
    private val logger = Logger.getInstance(EnvironmentUtil::class.java)

    /**
     * Checks if the plugin is running inside a known virtual machine environment.
     * The result is cached (lazy loaded) to avoid repeated expensive checks.
     */
    val isVirtualMachine: Boolean by lazy {
        val start = System.currentTimeMillis()

        // 1. Check System Properties (Fastest and safest)
        if (checkSystemProperties()) {
            logger.debug("Virtual machine detected via System Properties. Cost: ${System.currentTimeMillis() - start}ms")
            true
        }
        // 2. Check System Command (Windows only, for ESXi/Hyper-V compatibility)
        else if (checkWindowsWmic()) {
            logger.debug("Virtual machine detected via WMIC. Cost: ${System.currentTimeMillis() - start}ms")
            true
        } else {
            logger.debug("No virtual machine environment detected. Cost: ${System.currentTimeMillis() - start}ms")
            false
        }
    }

    private fun checkSystemProperties(): Boolean {
        return try {
            val vendor = System.getProperty("java.vendor", "").lowercase()
            val vmName = System.getProperty("java.vm.name", "").lowercase()
            val osName = System.getProperty("os.name", "").lowercase()

            vendor.contains("vmware") ||
            vmName.contains("vmware") ||
            osName.contains("virtualbox") ||
            osName.contains("hyper-v")
        } catch (e: Exception) {
            logger.warn("Failed to check system properties", e)
            false
        }
    }

    private fun checkWindowsWmic(): Boolean {
        val osName = System.getProperty("os.name", "").lowercase()
        if (!osName.contains("windows")) {
            return false
        }

        return try {
            // Execute wmic command to get manufacturer and model
            val process = ProcessBuilder("wmic", "computersystem", "get", "manufacturer,model")
                .redirectErrorStream(true)
                .start()

            // Wait for the process to complete with a timeout (500ms)
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroy()
                logger.warn("WMIC command timed out")
                return false
            }

            // Read output
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().lowercase()
            
            // Check for common VM signatures
            output.contains("vmware") ||
            output.contains("virtualbox") ||
            output.contains("hyper-v") ||
            output.contains("virtual machine") || // Generic Microsoft/Hyper-V
            output.contains("kvm") ||
            output.contains("bochs") ||
            output.contains("qemu")
        } catch (e: Exception) {
            logger.warn("Failed to execute WMIC command", e)
            false
        }
    }
}