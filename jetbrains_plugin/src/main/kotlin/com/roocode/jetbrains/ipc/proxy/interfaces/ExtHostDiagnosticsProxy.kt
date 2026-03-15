package com.roocode.jetbrains.ipc.proxy.interfaces

import com.roocode.jetbrains.problems.Problem
import com.roocode.jetbrains.util.URI

interface ExtHostDiagnosticsProxy {
    // The list will contain tuples of [URI, List<Problem>]
    fun acceptMarkersChange(markers: List<List<Any>>)

}