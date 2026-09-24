package com.battlesbudz.jarvis.v2.actions

import java.util.concurrent.CancellationException

fun interface MobileActionExecutor {
    fun execute(action: MobileAction): ExecutionResult
}

class ExecutionResult private constructor(
    val succeeded: Boolean,
    val message: String,
    val outcome: Outcome
) {
    enum class Outcome { SUCCEEDED, FAILED, REJECTED_VALIDATION, DENIED_PERMISSION, UNKNOWN_COMPLETION }

    /** Retains the existing JVM constructor used by Android instrumentation and callers. */
    constructor(succeeded: Boolean, message: String) : this(
        succeeded,
        message,
        if (succeeded) Outcome.SUCCEEDED else Outcome.FAILED
    )

    constructor(outcome: Outcome, message: String) : this(outcome == Outcome.SUCCEEDED, message, outcome)
}

class MobileActionPipeline(
    private val validator: MobileActionValidator = MobileActionValidator(),
    private val executor: MobileActionExecutor
) {
    fun execute(request: ActionRequest): ExecutionResult {
        return when (val validation = validator.validate(request)) {
            is ActionValidation.Valid -> try {
                executor.execute(validation.action)
            } catch (cancelled: CancellationException) {
                // Cancellation is an IllegalStateException too; preserve caller cancellation.
                throw cancelled
            } catch (_: SecurityException) {
                ExecutionResult(ExecutionResult.Outcome.DENIED_PERMISSION,
                    "Android denied permission to perform this action.")
            } catch (_: IllegalStateException) {
                // The side effect may have happened before the service failed. Do not retry.
                ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION,
                    "Android could not confirm that this action completed.")
            }
            is ActionValidation.Rejected -> ExecutionResult(ExecutionResult.Outcome.REJECTED_VALIDATION, validation.reason)
        }
    }
}
