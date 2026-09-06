package com.battlesbudz.jarvis.v2.actions

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
            is ActionValidation.Valid -> executor.execute(validation.action)
            is ActionValidation.Rejected -> ExecutionResult(false, validation.reason)
        }
    }
}