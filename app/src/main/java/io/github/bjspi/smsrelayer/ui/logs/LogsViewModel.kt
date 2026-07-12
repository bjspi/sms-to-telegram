package io.github.bjspi.smsrelayer.ui.logs

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.di.AppContainer
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.LogEntry
import io.github.bjspi.smsrelayer.domain.model.LogLevel
import io.github.bjspi.smsrelayer.ui.common.containerViewModelFactory
import io.github.bjspi.smsrelayer.ui.common.formatAbsoluteTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LogFilter(@StringRes val labelRes: Int) {
    All(R.string.logs_filter_all),
    Error(R.string.logs_filter_error),
    Warning(R.string.logs_filter_warning),
    Sms(R.string.logs_filter_sms),
    Telegram(R.string.logs_filter_telegram),
    Queue(R.string.logs_filter_queue),
    Service(R.string.logs_filter_service),
    ;

    fun matches(entry: LogEntry): Boolean = when (this) {
        All -> true
        Error -> entry.level == LogLevel.Error
        Warning -> entry.level == LogLevel.Warning
        Sms -> entry.category == LogCategory.Sms
        Telegram -> entry.category == LogCategory.Telegram
        Queue -> entry.category == LogCategory.Queue
        Service -> entry.category == LogCategory.Service ||
            entry.category == LogCategory.Boot ||
            entry.category == LogCategory.Watchdog
    }
}

class LogsViewModel(private val container: AppContainer) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(LogFilter.All)

    val query: StateFlow<String> = queryFlow.asStateFlow()
    val filter: StateFlow<LogFilter> = filterFlow.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val entries: StateFlow<List<LogEntry>> =
        combine(
            queryFlow.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
            filterFlow,
        ) { query, filter -> query.trim() to filter }
            .flatMapLatest { (query, filter) ->
                val source = if (query.isEmpty()) {
                    container.eventLog.observeLatest(PAGE_SIZE)
                } else {
                    flow { emit(container.eventLog.search(query, PAGE_SIZE)) }
                }
                source.map { list -> list.filter(filter::matches) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun onFilterChange(value: LogFilter) {
        filterFlow.value = value
    }

    fun clearLogs() {
        viewModelScope.launch { container.eventLog.clear() }
    }

    /** Plain-text dump of the currently visible entries for the share sheet. */
    fun buildExportText(): String = buildString {
        appendLine("SMS Relayer logs")
        appendLine("Exported at: ${formatAbsoluteTime(container.clock.now())}")
        appendLine()
        entries.value.forEach { entry ->
            append('[')
            append(formatAbsoluteTime(entry.timestamp))
            append("] ")
            append(entry.level.name.uppercase())
            append('/')
            append(entry.category.name)
            append(": ")
            appendLine(entry.message)
            entry.details?.takeIf { it.isNotBlank() }?.let { appendLine("    $it") }
        }
    }

    companion object {
        private const val PAGE_SIZE = 500
        private const val SEARCH_DEBOUNCE_MS = 300L

        val Factory: ViewModelProvider.Factory = containerViewModelFactory(::LogsViewModel)
    }
}
