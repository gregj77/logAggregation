package com.armory.logsort

import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.processors.PublishProcessor
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class LogPrinter {
    fun printLogs(logFiles: List<File>) {
        sortLogs(logFiles).forEach{ logLine: String? ->
            println(logLine)
        }
    }

    fun sortLogs(logFiles: List<File>): Sequence<String> {
        try {
            return logFiles
                .map { splitLogFileIntoWindows(it) }
                .combineWindowsByTimestamp()
                .asSequence()

        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun splitLogFileIntoWindows(logFile: File): Iterator<LogRecordWindow> {

        // i'd assume that regardless of the number and individual size of the log files, it is very unlikely that
        // individual file would have megabytes of log data in a single minute marker - to keep the calculations easy -
        // i assume 1 minute file break is more than enough

        // the idea behind this method is to change the file reading from eagerly iterating line by line,
        // to an iterator, which will let the caller decide when to pull next chunk of data

        var oneMinuteWindowIndicator = LocalDateTime.MIN.truncatedTo(ChronoUnit.MINUTES)
        val windowDelimiter = PublishProcessor.create<Unit>()

        return Flowable.create<LogRecord>(
            { emitter ->
                try {
                    // according to documentation - forEachLine is using streaming, so it should load only small chunks of data
                    logFile.forEachLine {

                        // i don't want to deal with string manipulation, and based on the readme - i assumed there is ALWAYS
                        // column containing valid ISO date
                        val record = LogRecord.fromLogLine(it)

                        // split the file into 1 minute chunks
                        if (record.timestamp.isAfter(oneMinuteWindowIndicator)) {
                            windowDelimiter.onNext(Unit)

                            // update the time "pointer" to next minute
                            oneMinuteWindowIndicator = record
                                .timestamp
                                .truncatedTo(ChronoUnit.MINUTES)
                                .plusMinutes(1L)
                        }

                        // add the record to the buffer - this is the place where control of the file loading
                        // is delegated to the caller - in this case - to the next operator, which is buffering
                        // the log lines, windowed by 1 minute blocks, as soon as the complete block is completed,
                        // control gets passed to the iterator
                        emitter.onNext(record)
                    }
                    // entire file is processed - we are done with this flowable
                    emitter.onComplete()
                } catch (e: Exception) {
                    emitter.onError(e)
                }
            }, BackpressureStrategy.BUFFER)
            .buffer( windowDelimiter )
            .filter { it.isNotEmpty() }
            .map {
                // to make working on windows easier - wrap the collection of all 1 minute logs into a dedicated dto
                // and decorate it with the window's start time
                // NOTE: filter ensures there is at least 1 entry in the collection
                val windowStart = it[0]!!.timestamp.truncatedTo(ChronoUnit.MINUTES)
                LogRecordWindow(windowStart, it)
            }
            .blockingIterable()
            .iterator()
    }

    private fun List<Iterator<LogRecordWindow>>.combineWindowsByTimestamp(): Iterable<String> {
        // combine the log file with the currently loaded window
        val logFileWindows = this.map { WindowEntry(it) }

        // combine all the logs together
        return Flowable.create<String>({ emitter ->

            try {
                // while there is a single file which still has data
                while (true) {

                    // window join condition - as it wraps some state, there needs to be created a fresh one for each iteration
                    val condition = associatedWithSingleWindow()

                    val remaining = logFileWindows
                        // ensure 1 minute chunk of data is loaded
                        .onEach { it.loadIfNeeded() }
                        // consider only files which still have some data
                        .filter { it.hasData() }
                        // sort the windows by the date
                        .sortedBy { it.windowDate()!! }
                        // in this iteration take only windows with the same timestamp
                        .takeWhile { condition(it) }

                    // if there is still some data
                    if (remaining.isNotEmpty()) {
                        remaining
                            // collect the log lines from given time window from each file
                            .flatMap { it.extractLogEntries() }
                            // ensure all the lines are sorted based on the timestamp
                            .sorted()
                            .map { it.logEntry }
                            // print all the lines within given window
                            .forEach(emitter::onNext)

                    } else {
                        // no more data!
                        break
                    }
                }

                // and a signal for the iterator so it can stop
                emitter.onComplete()
            } catch (e: Exception) {
                emitter.onError(e)
            }

        }, BackpressureStrategy.BUFFER)
//            .doOnNext{ println("--${it}")}
            .blockingIterable()

    }

    private fun associatedWithSingleWindow(): (WindowEntry) -> Boolean {
        var first = true
        var start = LocalDateTime.MIN
        return {
            // utility predicate function - always take first window from the sorted list, and keep taking all subsequent windows
            // as long as they have the same window's timestamp
            if (first) {
                start = it.windowDate()!!
                first = false
                true
            } else {
                start == it.windowDate()!!
            }
        }
    }

    data class WindowEntry(val iterator: Iterator<LogRecordWindow>, var currentWindow: LogRecordWindow? = null) {
        fun loadIfNeeded() {
            if (currentWindow == null && iterator.hasNext()) {
                currentWindow = iterator.next()
            }
        }
        fun hasData() = currentWindow != null

        fun windowDate() = currentWindow?.start

        fun extractLogEntries(): List<LogRecord> {
            val logEntries = currentWindow!!.entries
            currentWindow = null
            return logEntries
        }
    }
}

