package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerChannelEntity
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerSummaryEntity
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerSummaryWithChannelsEntity

@Dao
interface MonthlyLedgerDAO {
    @Transaction
    @Query(
        """
        SELECT * FROM monthly_ledger_summaries
        WHERE month = :month
        ORDER BY currency ASC
        """
    )
    fun observeMonth(month: String): Flow<List<MonthlyLedgerSummaryWithChannelsEntity>>

    @Transaction
    @Query(
        """
        SELECT * FROM monthly_ledger_summaries
        WHERE month = :month
        ORDER BY currency ASC
        """
    )
    suspend fun getMonth(month: String): List<MonthlyLedgerSummaryWithChannelsEntity>

    @Upsert
    suspend fun upsertSummary(summary: MonthlyLedgerSummaryEntity)

    @Query("DELETE FROM monthly_ledger_channels WHERE summary_id = :summaryId")
    suspend fun deleteChannels(summaryId: String)

    @Upsert
    suspend fun upsertChannels(channels: List<MonthlyLedgerChannelEntity>)

    @Transaction
    suspend fun replaceSummary(
        summary: MonthlyLedgerSummaryEntity,
        channels: List<MonthlyLedgerChannelEntity>,
    ) {
        upsertSummary(summary)
        deleteChannels(summary.id)
        if (channels.isNotEmpty()) {
            upsertChannels(channels)
        }
    }

    @Query("DELETE FROM monthly_ledger_summaries WHERE month = :month")
    suspend fun deleteMonth(month: String)

    @Query("DELETE FROM monthly_ledger_summaries WHERE month = :month AND currency = :currency")
    suspend fun deleteMonthCurrency(month: String, currency: String)
}
