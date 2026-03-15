package com.roocode.jetbrains.problems

import com.intellij.util.messages.Topic
import com.roocode.jetbrains.util.URI

/**
 * Defines the listener interface for receiving problem update events.
 * Components interested in problem changes should implement this interface.
 */
interface ProblemListener {
    /**
     * Invoked when the list of problems has been updated.
     * @param problems A map where the key is the file URI and the value is the list of problems for that file.
     */
    fun problemsUpdated(problems: Map<URI, List<Problem>>)
}

/**
 * Defines the MessageBus Topic for problem updates.
 * This topic is the single point of connection between publishers and subscribers.
 */
object Topics {
    val PROBLEMS_TOPIC: Topic<ProblemListener> = Topic.create("RooCode Problems Updated", ProblemListener::class.java)
}