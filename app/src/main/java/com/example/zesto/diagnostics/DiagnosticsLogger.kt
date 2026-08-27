package com.example.zesto.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thread-safe circular diagnostics event logger.
 */
class DiagnosticsLogger(private val maxEntries: Int = 200) {

    private val logQueue = ConcurrentLinkedDeque<DiagnosticsEvent>()
    private val _logs = MutableStateFlow<List<DiagnosticsEvent>>(emptyList())
    val logs: StateFlow<List<DiagnosticsEvent>> = _logs.asStateFlow()

    fun log(subsystem: Subsystem, level: DiagnosticsLevel, message: String, errorDetails: String? = null) {
        val event = DiagnosticsEvent(
            timestampMs = System.currentTimeMillis(),
            subsystem = subsystem,
            level = level,
            message = message,
            errorDetails = errorDetails
        )
        logQueue.add(event)
        while (logQueue.size > maxEntries) {
            logQueue.poll()
        }
        _logs.update { logQueue.toList() }
    }

    fun info(subsystem: Subsystem, message: String) = log(subsystem, DiagnosticsLevel.INFO, message)
    fun debug(subsystem: Subsystem, message: String) = log(subsystem, DiagnosticsLevel.DEBUG, message)
    fun warn(subsystem: Subsystem, message: String, error: String? = null) = log(subsystem, DiagnosticsLevel.WARNING, message, error)
    fun error(subsystem: Subsystem, message: String, error: String? = null) = log(subsystem, DiagnosticsLevel.ERROR, message, error)

    fun clear() {
        logQueue.clear()
        _logs.value = emptyList()
    }
}
