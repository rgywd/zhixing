package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.repository.HealthMetricRepository
import me.rerere.rikkahub.data.repository.MonthlyLedgerRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

class StatsVM internal constructor(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
    private val monthlyLedgerRepository: MonthlyLedgerRepository,
    healthMetricRepository: HealthMetricRepository,
    watchProbe: LenovoWatchProbe,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    private val _selectedLedgerMonth = MutableStateFlow(YearMonth.now())
    val selectedLedgerMonth = _selectedLedgerMonth.asStateFlow()

    val monthlyLedgerStats = selectedLedgerMonth
        .flatMapLatest { month ->
            monthlyLedgerRepository.observeMonth(month.toString())
                .map { summaries ->
                    buildMonthlyLedgerStatsUiState(
                        month = month,
                        summaries = summaries,
                    )
                }
                .onStart {
                    emit(MonthlyLedgerStatsUiState(month = month, isLoading = true))
                }
                .catch {
                    emit(
                        MonthlyLedgerStatsUiState(
                            month = month,
                            loadFailed = true,
                        )
                    )
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MonthlyLedgerStatsUiState(
                month = _selectedLedgerMonth.value,
                isLoading = true,
            ),
        )

    internal val healthStats = combine(
        healthMetricRepository.observeRecords(),
        watchProbe.state,
    ) { records, watchState ->
        buildHealthStatsUiState(records = records, watchState = watchState)
    }
        .onStart { emit(HealthStatsUiState(isLoading = true)) }
        .catch { emit(HealthStatsUiState(loadFailed = true)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HealthStatsUiState(isLoading = true),
        )

    init {
        viewModelScope.launch { loadStats() }
    }

    fun selectPreviousLedgerMonth() {
        _selectedLedgerMonth.update { it.minusMonths(1) }
    }

    fun selectNextLedgerMonth() {
        _selectedLedgerMonth.update { selected ->
            nextLedgerMonth(selected = selected, currentMonth = YearMonth.now())
        }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
        )
    }
}

internal fun nextLedgerMonth(
    selected: YearMonth,
    currentMonth: YearMonth,
): YearMonth = if (selected < currentMonth) selected.plusMonths(1) else selected
