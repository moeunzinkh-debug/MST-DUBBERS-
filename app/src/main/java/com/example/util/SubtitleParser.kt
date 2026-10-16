package com.example.util

import java.util.Locale

data class Cue(
    val start: Double,
    val end: Double,
    val text: String
)

object SubtitleParser {

    fun parseTime(t: String): Double {
        val clean = t.trim().replace(',', '.')
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toDoubleOrNull() ?: 0.0
                    val m = parts[1].toDoubleOrNull() ?: 0.0
                    val s = parts[2].toDoubleOrNull() ?: 0.0
                    h * 3600.0 + m * 60.0 + s
                }
                2 -> {
                    val m = parts[0].toDoubleOrNull() ?: 0.0
                    val s = parts[1].toDoubleOrNull() ?: 0.0
                    m * 60.0 + s
                }
                else -> {
                    parts[0].toDoubleOrNull() ?: 0.0
                }
            }
        } catch (e: Exception) {
            0.0
        }
    }

    fun parseSubtitles(raw: String): List<Cue> {
        val text = raw.replace("\r", "").removePrefix("\uFEFF")
        val blocks = text.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<Cue>()

        for (block in blocks) {
            val lines = block.lines().filter { it.trim().isNotEmpty() }
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeIdx == -1) continue

            val timeLine = lines[timeIdx]
            val split = timeLine.split("-->")
            if (split.size < 2) continue

            val start = parseTime(split[0])
            val endRaw = split[1].trim().split(Regex("\\s+")).firstOrNull() ?: ""
            val end = parseTime(endRaw)

            val body = lines.drop(timeIdx + 1)
                .joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()

            if (body.isNotEmpty() && !start.isNaN() && !end.isNaN() && end >= start) {
                cues.add(Cue(start = start, end = maxOf(end, start + 0.3), text = body))
            }
        }
        return cues
    }

    private fun pad(n: Long, len: Int = 2): String {
        return n.toString().padStart(len, '0')
    }

    fun formatStamp(sec: Double, sep: Char): String {
        val totalSec = if (sec < 0) 0.0 else sec
        val h = (totalSec / 3600).toLong()
        val m = ((totalSec % 3600) / 60).toLong()
        val s = (totalSec % 60).toLong()
        val ms = ((totalSec - totalSec.toLong()) * 1000).toLong().coerceIn(0, 999)
        return "${pad(h)}:${pad(m)}:${pad(s)}$sep${pad(ms, 3)}"
    }

    fun formatTimeDisplay(sec: Double): String {
        val totalSec = if (sec < 0) 0.0 else sec
        val m = (totalSec / 60).toLong()
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%04.1f", m, s)
    }

    fun toSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            sb.append("${i + 1}\n")
            sb.append("${formatStamp(c.start, ',')} --> ${formatStamp(c.end, ',')}\n")
            sb.append("${c.text}\n\n")
        }
        return sb.toString()
    }

    fun toVtt(cues: List<Cue>): String {
        val sb = StringBuilder("WEBVTT\n\n")
        cues.forEach { c ->
            sb.append("${formatStamp(c.start, '.')} --> ${formatStamp(c.end, '.')}\n")
            sb.append("${c.text}\n\n")
        }
        return sb.toString()
    }
}
