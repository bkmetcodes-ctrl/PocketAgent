package com.bekacodes.pocketagent.domain


import com.bekacodes.pocketagent.data.ExpenseDao
import com.bekacodes.pocketagent.data.ExpenseEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PendingWritePreview(
    val actionBadge: String,               // e.g., "INSERT_EXPENSE"
    val fields: List<Pair<String, String>> // Key-value rows for the Compose Approval Card
)

data class ToolExecutionResult(
    val signatureDisplay: String,          // e.g., "get_expenses(category='Coffee')"
    val jsonOutput: JsonObject,            // Structured JSON fed back to the LLM
    val humanReadableSummary: String       // Summary used by Demo Simulator / Logs
)

/**
 * Core MCP (Model Context Protocol) contract for on-device Android tools.
 */
interface AgentTool {
    val name: String
    val description: String
    val parametersSchemaJson: String
    val requiresUserApproval: Boolean

    fun buildApprovalPreview(arguments: JsonObject): PendingWritePreview? = null
    suspend fun execute(arguments: JsonObject): ToolExecutionResult
}

/**
 * Tool 1: Read-Only Expense Query (Executes automatically mid-stream)
 */
class GetExpensesTool(private val expenseDao: ExpenseDao) : AgentTool {
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
        val category = arguments["category"]?.jsonPrimitive?.contentOrNull
        val rows = if (!category.isNullOrBlank()) {
            expenseDao.getExpensesByCategory(category)
        } else {
            expenseDao.getAllExpensesSnapshot()
        }

        val total = rows.sumOf { it.amount }
        val count = rows.size
        val signature =
            if (category != null) "get_expenses(category='$category')" else "get_expenses()"

        val resultJson = buildJsonObject {
            put("category", category ?: "All")
            put("visitCount", count)
            put("totalAmountUsd", total)
        }

        return ToolExecutionResult(
            signatureDisplay = signature,
            jsonOutput = resultJson,
            humanReadableSummary = "Found $count visits totaling $${String.format("%.2f", total)}"
        )
    }
}

/**
 * Tool 2: Mutating Database Insert (Suspends stream and triggers Accept & Save Card)
 */
class InsertExpenseTool(private val expenseDao: ExpenseDao) : AgentTool {
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
                "Amount" to "$${String.format("%.2f", amount)}",
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

        val rowId = expenseDao.insertExpense(
            ExpenseEntity(
                amount = amount,
                category = category,
                merchant = merchant,
                dateLabel = date,
                addedByAgent = true
            )
        )

        return ToolExecutionResult(
            signatureDisplay = "insert_expense(amount=$amount, category='$category')",
            jsonOutput = buildJsonObject {
                put("status", "SUCCESS")
                put("insertedRowId", rowId)
                put("amount", amount)
                put("category", category)
            },
            humanReadableSummary = "Inserted row #$rowId ($${
                String.format(
                    "%.2f",
                    amount
                )
            } in $category)"
        )
    }
}

/**
 * Tool 3: Undo / Delete Last Entry (Suspends stream and triggers Accept & Save Card)
 */
class DeleteLastExpenseTool(private val expenseDao: ExpenseDao) : AgentTool {
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
        val deletedCount = expenseDao.deleteMostRecentExpense()
        return ToolExecutionResult(
            signatureDisplay = "delete_last_expense()",
            jsonOutput = buildJsonObject {
                put("status", if (deletedCount > 0) "DELETED" else "EMPTY")
            },
            humanReadableSummary = "Deleted $deletedCount recent row(s)"
        )
    }
}

class AgentToolRegistry(expenseDao: ExpenseDao) {
    private val tools: Map<String, AgentTool> = listOf(
        GetExpensesTool(expenseDao),
        InsertExpenseTool(expenseDao),
        DeleteLastExpenseTool(expenseDao)
    ).associateBy { it.name }

    fun getTool(name: String): AgentTool? = tools[name]
    fun allTools(): List<AgentTool> = tools.values.toList()
}