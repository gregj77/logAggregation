package com.armory.logsort

import java.time.LocalDateTime

data class LogRecordWindow(val start: LocalDateTime, val entries: List<LogRecord>)