package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ChatEntry
import org.json.JSONObject

class ActionIntentRouter {
    fun classifyActionIntent(
        prompt: String,
        history: List<ChatEntry>
    ): com.battlesbudz.jarvis.v2.ai.ToolCall? {
        val planned = ActionTurnPlan.parse(prompt, history)
        val request = (planned as? ActionTurnPlan.Ready)?.takeIf { it.steps.size == 1 }?.steps?.single()?.request
            ?: return null
        return com.battlesbudz.jarvis.v2.ai.ToolCall(request.name, JSONObject(request.arguments).toString())
    }


    fun toolMatchesUserIntent(
        prompt: String,
        history: List<ChatEntry>,
        call: com.battlesbudz.jarvis.v2.ai.ToolCall
    ): Boolean {
        val plan = ActionTurnPlan.parse(prompt, history) as? ActionTurnPlan.Ready ?: return false
        if (plan.steps.size != 1) return false
        val expected = plan.steps.single().request
        val proposed = NativeActionDecoder.decodeStrict(call) ?: return false
        if (expected.name != proposed.name) return false
        return when (expected.name) {
            "read_battery" -> true
            "set_volume" -> expected.arguments["level"]?.toIntOrNull()?.let { requested ->
                proposed.arguments["level"]?.toIntOrNull() == requested
            } == true
            "open_app" -> expected.arguments["app"]?.let { requested ->
                proposed.arguments["app"]?.trim()?.equals(requested.trim(), ignoreCase = true)
            } == true
            else -> false
        }
    }


}
