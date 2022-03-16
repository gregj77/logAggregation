package com.armory.logsort

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class LogRecord(val timestamp: LocalDateTime, val logEntry: String) : Comparable<LogRecord> {

    companion object {
        @JvmStatic
        fun fromLogLine(logLine: String) : LogRecord {

            val delimiter = logLine.indexOf(',')
            val timestamp =  OffsetDateTime.parse(logLine.substring(0, delimiter).trim(), DateTimeFormatter.ISO_DATE_TIME)
            return LogRecord(timestamp.toLocalDateTime(), logLine)
        }
    }

    override fun toString(): String = logEntry

    override fun compareTo(other: LogRecord): Int = this.timestamp.compareTo(other.timestamp)
}