// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.ipc

import com.intellij.openapi.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors

/**
 * Load estimator
 * Corresponds to LoadEstimator in VSCode
 */
class LoadEstimator : ILoadEstimator, Disposable {
    private val lastRuns = LongArray(HISTORY_LENGTH)
    private val scheduler: ScheduledExecutorService
    private var isDisposed = false
    
    init {
        val now = System.currentTimeMillis()
        
        for (i in 0 until HISTORY_LENGTH) {
            lastRuns[i] = now - 1000L * i
        }
        
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LoadEstimator").apply { isDaemon = true }
        }
        
        scheduler.scheduleAtFixedRate({
            if (!isDisposed) {
                for (i in HISTORY_LENGTH - 1 downTo 1) {
                    lastRuns[i] = lastRuns[i - 1]
                }
                lastRuns[0] = System.currentTimeMillis()
            }
        }, 0, 1000, TimeUnit.MILLISECONDS)
    }
    
    /**
     * Calculate current load estimate
     * Returns an estimate number from 0 (low load) to 1 (high load)
     * @return Load estimate value
     */
    private fun load(): Double {
        val now = System.currentTimeMillis()
        val historyLimit = (1 + HISTORY_LENGTH) * 1000L
        var score = 0
        
        for (i in 0 until HISTORY_LENGTH) {
            if (now - lastRuns[i] <= historyLimit) {
                score++
            }
        }
        
        return 1.0 - score.toDouble() / HISTORY_LENGTH
    }
    
    override fun hasHighLoad(): Boolean {
        return if (isDisposed) false else load() >= 0.5
    }
    
    override fun dispose() {
        isDisposed = true
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
    
    companion object {
        private const val HISTORY_LENGTH = 10
    }
} 