package com.bekacodes.pocketagent.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface AuditResult {
    data class Valid(val sanitizedArgs: JsonObject) : AuditResult
    data class Rejected(val reason: String) : AuditResult
}

/**
 * Deterministic guardrail layer that audits LLM tool calls for hallucinations,
 * out-of-bounds values, and schema violations before execution.
 */
object ToolCallAuditor {

    private val allowedCategories = setOf(
        "Coffee", "Dining Out", "Groceries", "Transport", "Entertainment", "Utilities", "General"
    )

    fun audit(
        toolName: String,
        rawArgs: JsonObject,
        registry: AgentToolRegistry
    ): AuditResult {
        val tool = registry.getTool(toolName)
            ?: return AuditResult.Rejected("Hallucinated unknown tool '$toolName'.")

        return when (tool.name) {
            "insert_expense" -> auditInsertExpense(rawArgs)
            "get_expenses", "delete_last_expense" -> AuditResult.Valid(rawArgs)
            else -> AuditResult.Valid(rawArgs)
        }
    }

    private fun auditInsertExpense(args: JsonObject): AuditResult {
        val amount = args["amount"]?.jsonPrimitive?.doubleOrNull
            ?: return AuditResult.Rejected("Missing or non-numeric 'amount' field.")

        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
            return AuditResult.Rejected("Invalid expense amount ($amount). Must be > $0.00.")
        }

        if (amount > 50_000.0) {
            return AuditResult.Rejected("Amount ($$amount) exceeds single-transaction safety cap ($50,000).")
        }

        val category = args["category"]?.jsonPrimitive?.contentOrNull?.trim()
        if (category.isNullOrEmpty()) {
            return AuditResult.Rejected("Missing expense 'category'.")
        }

        if (category.length > 40) {
            return AuditResult.Rejected("Category name exceeds 40-character limit.")
        }

        return AuditResult.Valid(args)
    }
}