// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.theme

/**
 * Maps IntelliJ UIManager keys to VS Code CSS variable names.
 * This allows the WebView to dynamically adapt to the current IDE theme.
 * 
 * Minimal configuration based on core visual elements.
 */
object ThemeMapper {
    val colorMap = mapOf(
        // --- Core Backgrounds (Hierarchy Sync) ---
        // 1. Main Background: Blend with IDE ToolWindow/Panel
        "sideBar-background" to "Panel.background",
        "panel-background" to "Panel.background",
        
        // 2. Inner Content Background: Keep as IDE Editor background
        // This allows internal widgets (like code blocks or cards) to have a distinct, usually darker color
        // compared to the sideBar background, creating natural contrast.
        "editor-background" to "Editor.background",
        
        // 3. Widget/Card Background: Map to Editor background
        // This ensures list items/cards stand out against the sideBar background.
        "editorWidget-background" to "Editor.background",
        
        // --- Text ---
        "foreground" to "Label.foreground",
        "sideBar-foreground" to "Label.foreground",
        "editor-foreground" to "Editor.foreground" 
    )
}