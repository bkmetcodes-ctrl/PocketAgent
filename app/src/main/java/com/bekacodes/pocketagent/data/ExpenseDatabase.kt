package com.bekacodes.pocketagent.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val category: String,
    val merchant: String,
    val dateLabel: String,
    val addedByAgent: Boolean = false
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY id DESC")
    fun observeAllExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE LOWER(category) = LOWER(:category) ORDER BY id DESC")
    suspend fun getExpensesByCategory(category: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses ORDER BY id DESC")
    suspend fun getAllExpensesSnapshot(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Query("DELETE FROM expenses WHERE id = (SELECT MAX(id) FROM expenses)")
    suspend fun deleteMostRecentExpense(): Int

    @Query("DELETE FROM expenses")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int
}

@Database(entities = [ExpenseEntity::class], version = 1, exportSchema = false)
abstract class PocketDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: PocketDatabase? = null

        fun getInstance(context: Context): PocketDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PocketDatabase::class.java,
                    "pocket_agent.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}