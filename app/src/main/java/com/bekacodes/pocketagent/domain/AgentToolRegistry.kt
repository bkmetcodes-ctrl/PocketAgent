package com.bekacodes.pocketagent.domain

import androidx.compose.runtime.Immutable
import com.bekacodes.pocketagent.data.ExpenseDao
import com.bekacodes.pocketagent.data.ExpenseEntity
import com.bekacodes.pocketagent.data.repository.ExpenseRepository
import com.bekacodes.pocketagent.data.repository.ExpenseRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Preview state passed to Jetpack Compose approval card prior to executing mutating writes.
 */
@Immutable
data class PendingWritePreview(
    val actionBadge: String,               // e.g., "INSERT_EXPENSE"
    val fields: List<Pair<String, String>> // Key-value rows for the Compose Approval Card
)

/**
 * Encapsulates the output of an [AgentTool] execution.
 */
@Immutable
data class ToolExecutionResult(
    val signatureDisplay: String,          // e.g., "get_expenses(category='Coffee')"
    val jsonOutput: JsonObject,            // Structured JSON fed back to the LLM
    val humanReadableSummary: String       // Summary used by Demo Simulator / Logs
)

/**
 * Core Model Context Protocol (MCP) tool contract for on-device Android agents.
 */
interface AgentTool {
    val name: String
    val description: String
    val parametersSchemaJson: String
    val requiresUserApproval: Boolean

    /**
     * Constructs a human-readable preview card model if the tool requires approval.
     */
    fun buildApprovalPreview(arguments: JsonObject): PendingWritePreview? = null

    /**
     * Executes the underlying business logic on the local repository or domain layer.
     */
    suspend fun execute(arguments: JsonObject): ToolExecutionResult
}

/**
 * Tool 1: Read-Only Expense Query (Executes automatically mid-stream without user approval).
 */
class GetExpensesTool(private val expenseRepository: ExpenseRepository) : AgentTool {
    override val name: String = "get_expenses"
    override val description: String =
        "Queries local SQLite expenses, optionally filtered by category."
    override val requiresUserApproval: Boolean = false

    override val parametersSchemaJson: String = """
        {
          "type": "object",
          "properties": {
            "category": { "type": "string", "description": "Expense category e.g. Coffee, Dining Out, Groceries" }
          }
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val category = arguments["category"]?.jsonPrimitive?.contentOrNull?.trim()
        val result = if (!category.isNullOrBlank()) {
            expenseRepository.getExpensesByCategory(category)
        } else {
            expenseRepository.getAllExpensesSnapshot()
        }

        val rows = result.getOrDefault(emptyList())
        val total = rows.sumOf { it.amount }
        val count = rows.size
        val signature =
            if (!category.isNullOrBlank()) "get_expenses(category='$category')" else "get_expenses()"

        val resultJson = buildJsonObject {
            put("category", category ?: "All")
            put("visitCount", count)
            put("totalAmountUsd", total)
            put("status", if (result.isSuccess) "SUCCESS" else "ERROR")
        }

        val summaryText = if (result.isSuccess) {
            "Found $count record(s) totaling $${String.format(Locale.US, "%.2f", total)}"
        } else {
            "Failed to query database: ${result.exceptionOrNull()?.localizedMessage}"
        }

        return ToolExecutionResult(
            signatureDisplay = signature,
            jsonOutput = resultJson,
            humanReadableSummary = summaryText
        )
    }
}

/**
 * Tool 2: Mutating Database Insert (Suspends stream and requires explicit user approval).
 */
class InsertExpenseTool(private val expenseRepository: ExpenseRepository) : AgentTool {
    override val name: String = "insert_expense"
    override val description: String =
        "Inserts a new expense row into the user's local Room ledger."
    override val requiresUserApproval: Boolean = true

    override val parametersSchemaJson: String = """
        {
          "type": "object",
          "properties": {
            "amount": { "type": "number", "description": "Dollar amount of the expense" },
            "category": { "type": "string", "description": "Category name e.g. Dining Out, Coffee" },
            "merchant": { "type": "string", "description": "Merchant or short title" },
            "date": { "type": "string", "description": "Relative or formatted date e.g. Yesterday, Today" }
          },
          "required": ["amount", "category", "date"]
        }
    """.trimIndent()

    override fun buildApprovalPreview(arguments: JsonObject): PendingWritePreview {
        val amount = arguments["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val category = arguments["category"]?.jsonPrimitive?.contentOrNull ?: "General"
        val date = arguments["date"]?.jsonPrimitive?.contentOrNull ?: "Today"

        return PendingWritePreview(
            actionBadge = "INSERT_EXPENSE",
            fields = listOf(
                "Amount" to "$${String.format(Locale.US, "%.2f", amount)}",
                "Category" to category,
                "Date" to date
            )
        )
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val amount = arguments["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val category = arguments["category"]?.jsonPrimitive?.contentOrNull ?: "Dining Out"
        val merchant = arguments["merchant"]?.jsonPrimitive?.contentOrNull ?: category
        val date = arguments["date"]?.jsonPrimitive?.contentOrNull ?: "Yesterday"

        val result = expenseRepository.insertExpense(
            ExpenseEntity(
                amount = amount,
                category = category,
                merchant = merchant,
                dateLabel = date,
                addedByAgent = true
            )
        )

        val rowId = result.getOrDefault(-1L)
        val isSuccess = result.isSuccess && rowId > 0

        return ToolExecutionResult(
            signatureDisplay = "insert_expense(amount=$amount, category='$category')",
            jsonOutput = buildJsonObject {
                put("status", if (isSuccess) "SUCCESS" else "ERROR")
                put("insertedRowId", rowId)
                put("amount", amount)
                put("category", category)
            },
            humanReadableSummary = if (isSuccess) {
                "Inserted row #$rowId ($${String.format(Locale.US, "%.2f", amount)} in $category)"
            } else {
                "Failed to insert expense: ${result.exceptionOrNull()?.localizedMessage}"
            }
        )
    }
}

/**
 * Tool 3: Undo / Delete Most Recent Expense Entry.
 */
class DeleteLastExpenseTool(private val expenseRepository: ExpenseRepository) : AgentTool {
    override val name: String = "delete_last_expense"
    override val description: String =
        "Deletes the most recently logged expense row from the database."
    override val requiresUserApproval: Boolean = true

    override val parametersSchemaJson: String = """{ "type": "object", "properties": {} }"""

    override fun buildApprovalPreview(arguments: JsonObject): PendingWritePreview {
        return PendingWritePreview(
            actionBadge = "DELETE_LAST_ROW",
            fields = listOf(
                "Target" to "Most recent ledger entry",
                "Permanent" to "Yes (SQLite DELETE)"
            )
        )
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val result = expenseRepository.deleteMostRecentExpense()
        val deletedCount = result.getOrDefault(0)
        return ToolExecutionResult(
            signatureDisplay = "delete_last_expense()",
            jsonOutput = buildJsonObject {
                put("status", if (deletedCount > 0) "DELETED" else "EMPTY")
            },
            humanReadableSummary = "Deleted $deletedCount recent row(s)"
        )
    }
}

/**
 * Tool 4: Clear Entire SQLite Database.
 */
class ClearAllExpensesTool(private val expenseRepository: ExpenseRepository) : AgentTool {
    override val name: String = "clear_all_expenses"
    override val description: String = "Deletes all expense records and clears the entire local database."
    override val requiresUserApproval: Boolean = true

    override val parametersSchemaJson: String = """{ "type": "object", "properties": {} }"""

    override fun buildApprovalPreview(arguments: JsonObject): PendingWritePreview {
        return PendingWritePreview(
            actionBadge = "CLEAR_ALL_EXPENSES",
            fields = listOf(
                "Target" to "Entire SQLite Ledger",
                "Permanent" to "Yes (TRUNCATE TABLE)"
            )
        )
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val result = expenseRepository.clearAll()
        val deletedRows = result.getOrDefault(0)
        return ToolExecutionResult(
            signatureDisplay = "clear_all_expenses()",
            jsonOutput = buildJsonObject {
                put("status", "CLEARED")
                put("deletedRowCount", deletedRows)
            },
            humanReadableSummary = "Cleared all $deletedRows expense record(s) from the database"
        )
    }
}

/**
 * Registry containing all available [AgentTool] instances available to PocketAgent.
 */
class AgentToolRegistry(expenseRepository: ExpenseRepository) {
    constructor(expenseDao: ExpenseDao) : this(ExpenseRepositoryImpl(expenseDao, Dispatchers.IO))

    private val tools: Map<String, AgentTool> = listOf(
        GetExpensesTool(expenseRepository),
        InsertExpenseTool(expenseRepository),
        DeleteLastExpenseTool(expenseRepository),
        ClearAllExpensesTool(expenseRepository)
    ).associateBy { it.name }

    fun getTool(name: String): AgentTool? = tools[name]
    fun allTools(): List<AgentTool> = tools.values.toList()
}
