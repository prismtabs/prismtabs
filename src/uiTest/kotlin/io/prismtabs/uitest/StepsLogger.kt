package io.prismtabs.uitest

import com.intellij.remoterobot.stepsProcessing.StepLogger
import com.intellij.remoterobot.stepsProcessing.StepWorker

object StepsLogger {
    private var initialized = false

    fun init() {
        if (!initialized) {
            StepWorker.registerProcessor(StepLogger())
            initialized = true
        }
    }
}
