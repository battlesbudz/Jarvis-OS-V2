package com.battlesbudz.jarvis.v2.actions

import java.util.concurrent.CancellationException

fun interface MobileActionExecutor {
    fun execute(action: MobileAction): ExecutionResult
}

data class ExecutionResult(
    val succeeded: Boolean,
    val message: String
)

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
                ExecutionResult(false, "Android denied permission to perform this action.")
            } catch (_: IllegalStateException) {
                // The side effect may have happened before the service failed. Do not retry.
                ExecutionResult(false, "Android could not confirm that this action completed.")
            }
            is ActionValidation.Rejected -> ExecutionResult(false, validation.reason)
        }
    }
}
