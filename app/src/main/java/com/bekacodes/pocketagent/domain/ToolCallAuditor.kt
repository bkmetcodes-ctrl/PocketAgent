package com.bekacodes.pocketagent.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Result of auditing an LLM tool call prior to execution.
 */
@Immutable
sealed interface AuditResult {
    @Immutable
    data class Valid(val sanitizedArgs: JsonObject) : AuditResult

    @Immutable
    data class Rejected(val reason: String) : AuditResult
}

/**
 * Deterministic guardrail layer that audits LLM tool calls for hallucinations,
 * out-of-bounds parameters, and schema violations before database mutations occur.
 */
object ToolCallAuditor {

    /** Max single transaction dollar limit allowed by financial safety policy. */
    private const val MAX_SINGLE_TRANSACTION_LIMIT = 50_000.0

    /**
     * Audits a tool call request against the tool registry and safety constraints.
     */
    fun audit(
        toolName: String,
        rawArgs: JsonObject,
        registry: AgentToolRegistry
    ): AuditResult {
        val tool = registry.getTool(toolName)
            ?: return AuditResult.Rejected("Hallucinated unknown tool '$toolName'.")

        return when (tool.name) {
            "insert_expense" -> auditInsertExpense(rawArgs)
            "get_expenses" -> auditGetExpenses(rawArgs)
            "delete_last_expense", "clear_all_expenses" -> AuditResult.Valid(rawArgs)
            else -> AuditResult.Valid(rawArgs)
        }
    }

    private fun auditInsertExpense(args: JsonObject): AuditResult {
        val amount = args["amount"]?.jsonPrimitive?.doubleOrNull
            ?: return AuditResult.Rejected("Missing or non-numeric 'amount' field.")

        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
            return AuditResult.Rejected("Invalid expense amount ($amount). Amount must be > $0.00.")
        }

        if (amount > MAX_SINGLE_TRANSACTION_LIMIT) {
            return AuditResult.Rejected(
                "Amount ($$amount) exceeds single-transaction safety cap ($50,000)."
            )
        }

        val rawCategory = args["category"]?.jsonPrimitive?.contentOrNull?.trim()
        if (rawCategory.isNullOrEmpty()) {
            return AuditResult.Rejected("Missing required 'category' field.")
        }

        // Strip non-printable or newline control characters
        val sanitizedCategory = rawCategory.replace(Regex("""[\r\n\t]"""), " ").trim()
        if (sanitizedCategory.length > 40) {
            return AuditResult.Rejected("Category name exceeds 40-character limit.")
        }

        val sanitizedArgs = buildJsonObject {
            args.forEach { (key, value) ->
                if (key == "category") {
                    put("category", sanitizedCategory)
                } else {
                    put(key, value)
                }
            }
        }

        return AuditResult.Valid(sanitizedArgs)
    }

    private fun auditGetExpenses(args: JsonObject): AuditResult {
        val rawCategory = args["category"]?.jsonPrimitive?.contentOrNull?.trim()
        if (rawCategory != null && rawCategory.length > 50) {
            return AuditResult.Rejected("Query category filter exceeds maximum allowed length.")
        }
        return AuditResult.Valid(args)
    }
}
