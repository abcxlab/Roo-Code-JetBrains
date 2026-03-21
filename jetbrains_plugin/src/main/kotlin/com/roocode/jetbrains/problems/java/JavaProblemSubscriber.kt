package com.roocode.jetbrains.problems.java

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.roocode.jetbrains.problems.CompilerProblemHandler
import com.roocode.jetbrains.problems.ProblemManager

class JavaProblemSubscriber : ProjectActivity {
    override suspend fun execute(project: Project) {
        val problemManager = project.getService(ProblemManager::class.java)
        val handler = CompilerProblemHandler(project, problemManager) { problems ->
            // Use reflection or an internal method to pass problems back to ProblemManager
            // to maintain strict decoupling if necessary, or just call public method
            problemManager.receiveExternalProblems(problems)
        }
        handler.subscribe()
    }
}
