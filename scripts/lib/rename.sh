#!/bin/bash

# This script provides functions for renaming build artifacts.

# Rename IDEA plugin artifact based on VSIX version
rename_idea_plugin_artifact() {
    log_step "Renaming IDEA plugin artifact..."

    # Find the latest VSIX file to extract the version
    local vsix_file
    vsix_file=$(get_latest_file "$PROJECT_ROOT/deps/roo-code/bin" "*.vsix")

    if [[ -z "$vsix_file" ]]; then
        log_warn "No VSIX file found, skipping IDEA plugin rename."
        return 0
    fi

    # Extract version from VSIX filename (e.g., roo-cline-3.25.11.vsix -> 3.25.11)
    local vsix_version
    vsix_version=$(basename "$vsix_file" .vsix | grep -o -E '[0-9]+\.[0-9]+\.[0-9]+')

    if [[ -z "$vsix_version" ]]; then
        log_warn "Could not extract version from VSIX file: $vsix_file. Skipping rename."
        return 0
    fi
    log_info "Detected roo-code version: $vsix_version"

    # Find the original IDEA plugin zip file
    local idea_dist_dir="$PROJECT_ROOT/$IDEA_DIR/build/distributions"
    local original_plugin_file
    original_plugin_file=$(find "$idea_dist_dir" -name "*.zip" -type f | sort -r | head -n 1)

    if [[ -z "$original_plugin_file" ]]; then
        log_warn "No original IDEA plugin ZIP file found. Skipping rename."
        return 0
    fi
    log_info "Found original IDEA plugin: $original_plugin_file"

    # Define the new filename
    local new_plugin_name="jetbrains-roo-cline-${vsix_version}.zip"
    local new_plugin_path="$idea_dist_dir/$new_plugin_name"

    # Rename the file
    log_info "Renaming '$original_plugin_file' to '$new_plugin_path'"
    execute_cmd "mv '$original_plugin_file' '$new_plugin_path'" "IDEA plugin rename"

    # Update the global variable to reflect the new name for the summary
    IDEA_PLUGIN_FILE="$new_plugin_path"

    log_success "IDEA plugin renamed to: $new_plugin_name"
}
