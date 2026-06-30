package com.xiddoc.claudeophobia.data

import java.time.Instant

/**
 * The persistent, append-only record of weekly Claude usage, sampled every few
 * hours and kept *forever* so the progress graph can look arbitrarily far back.
 *
 * Everything in this file is pure Kotlin (no Android types) so it can be
 * unit-tested directly, mirroring the [WeeklyNudges]/[Pacing]/[ResetConfig]
 * idiom. The Android side ([UsageHistoryRepository]) only persists the encoded
 * blob and hands these models to the renderers.
 *
 * Data is bucketed by *week* (the [ResetConfig] boundary that opened it). Each
 * bucket freezes the reset signature in force when its first sample landed, so a
 * later change to the reset day/time/zone never retroactively re-buckets — or
 * corrupts — historical weeks.
 */

/** One recorded data point: a wall-clock instant and the weekly utilization (0..100). */
data class Sample(val tsMs: Long, val pct: Int)

/**
 * One week's samples, keyed by [weekStartMs] (the reset boundary that opened the
 * week). [weekEndMs] is the next reset boundary — note it can be 23h or 25h off a
 * round 7 days across a DST transition, which is exactly why it is stored rather
 * than derived by dividing epoch millis.
 */
data class WeekBucket(
    val weekStartMs: Long,
    val weekEndMs: Long,
    /** Frozen `"${dayOfWeek.value}@${hour}:${minute}@${zoneId}"` of the reset in force. */
    val resetSignature: String,
    /** Sorted ascending by [Sample.tsMs], one entry per distinct timestamp. */
    val samples: List<Sample>,
)

/** The whole history: buckets sorted ascending by [WeekBucket.weekStartMs]. */
data class History(val buckets: List<WeekBucket>) {
    val isEmpty: Boolean get() = buckets.isEmpty()
}

/**
 * Stable, opaque identity of a reset configuration. Two configs that resolve week
 * boundaries to the same instants share a signature; any difference (day, time,
 * zone) yields a different one. Deliberately contains no `'|'` so it survives the
 * codec's top-level line split untouched (zone ids never contain `'|'`).
 */
fun ResetConfig.signature(): String =
    "${dayOfWeek.value}@${time.hour}:${time.minute}@${zone.id}"

/** The reset-boundary instant (epoch millis) that opens the week containing [tsMs]. */
fun weekKeyFor(tsMs: Long, config: ResetConfig): Long =
    config.previousResetAtOrBefore(Instant.ofEpochMilli(tsMs)).toInstant().toEpochMilli()

/**
 * Inserts [sample] into [history] under [config], returning a new [History]. Pure
 * and total — never throws. Handles three cases:
 *
 *  1. A bucket for the sample's week already exists → append (dedupe by timestamp,
 *     last write wins, kept sorted).
 *  2. The sample's week boundary falls *inside* an existing bucket that was frozen
 *     under a different reset signature (the user changed the reset config
 *     mid-week) → close that old bucket at the new boundary, migrating any of its
 *     samples that now belong to the new week, and open a fresh bucket.
 *  3. Otherwise → open a fresh bucket.
 *
 * [sample]'s percent is re-clamped to 0..100 here as a final defense; callers
 * should already have clamped a finite Double before converting.
 */
fun insertSample(history: History, sample: Sample, config: ResetConfig): History {
    val instant = Instant.ofEpochMilli(sample.tsMs)
    val weekStart = config.previousResetAtOrBefore(instant).toInstant().toEpochMilli()
    val weekEnd = config.nextResetAfter(instant).toInstant().toEpochMilli()
    val sig = config.signature()
    val clamped = Sample(sample.tsMs, sample.pct.coerceIn(0, 100))

    val buckets = history.buckets.toMutableList()

    // Case 1: exact week match — append regardless of signature (same boundary).
    val exact = buckets.indexOfFirst { it.weekStartMs == weekStart }
    if (exact >= 0) {
        val b = buckets[exact]
        buckets[exact] = b.copy(samples = (b.samples + clamped).dedupeByTs())
        return History(buckets.sortedBy { it.weekStartMs })
    }

    // Case 2: the new boundary lands inside a differently-signed in-progress bucket.
    val overlap = buckets.indexOfFirst {
        it.resetSignature != sig && weekStart >= it.weekStartMs && weekStart < it.weekEndMs
    }
    if (overlap >= 0) {
        val old = buckets[overlap]
        val (stay, move) = old.samples.partition { it.tsMs < weekStart }
        if (stay.isEmpty()) {
            buckets.removeAt(overlap)
        } else {
            buckets[overlap] = old.copy(weekEndMs = weekStart, samples = stay)
        }
        buckets.add(WeekBucket(weekStart, weekEnd, sig, (move + clamped).dedupeByTs()))
        return History(buckets.sortedBy { it.weekStartMs })
    }

    // Case 3: brand-new week.
    buckets.add(WeekBucket(weekStart, weekEnd, sig, listOf(clamped)))
    return History(buckets.sortedBy { it.weekStartMs })
}

/**
 * The weeks to display, derived from the stored [History] under the *current*
 * [config]. Buckets whose `[start, end)` ranges overlap (a side effect of a
 * mid-history config change) are merged into one, preferring the boundaries of
 * the bucket whose signature matches the current config, so the pager and the
 * derivative never show two conflicting "this week" pages.
 */
fun bucketsFor(history: History, config: ResetConfig): List<WeekBucket> {
    val sorted = history.buckets.sortedBy { it.weekStartMs }
    if (sorted.isEmpty()) return emptyList()
    val curSig = config.signature()
    val merged = ArrayList<WeekBucket>(sorted.size)
    for (b in sorted) {
        val last = merged.lastOrNull()
        if (last != null && b.weekStartMs < last.weekEndMs) {
            val preferB = b.resetSignature == curSig && last.resetSignature != curSig
            merged[merged.size - 1] = last.copy(
                weekStartMs = minOf(last.weekStartMs, b.weekStartMs),
                weekEndMs = maxOf(last.weekEndMs, b.weekEndMs),
                resetSignature = if (preferB) b.resetSignature else last.resetSignature,
                samples = (last.samples + b.samples).dedupeByTs(),
            )
        } else {
            merged.add(b)
        }
    }
    return merged
}

/** Keeps the last [Sample] for each distinct timestamp, sorted ascending. */
private fun List<Sample>.dedupeByTs(): List<Sample> {
    val byTs = LinkedHashMap<Long, Sample>()
    for (s in this) byTs[s.tsMs] = s
    return byTs.values.sortedBy { it.tsMs }
}

/**
 * Compact, dependency-free serialization for the history blob. One bucket per
 * line:
 *
 * ```
 * <weekStartMs>|<weekEndMs>|<resetSignature>|<ts>,<pct>;<ts>,<pct>;...
 * ```
 *
 * [decode] is *total*: a corrupt line or token is skipped, never thrown, so a
 * single bad byte can never wipe years of history. Round-trips are unit-tested.
 * At ~12-16 bytes per sample even a multi-year history stays modest (a few MB at
 * the finest cadence), and a [UsageHistoryRepository.MAX_WEEKS] cap backstops it.
 */
object HistoryCodec {

    fun encode(history: History): String =
        history.buckets.joinToString("\n") { b ->
            val samples = b.samples.joinToString(";") { "${it.tsMs},${it.pct}" }
            "${b.weekStartMs}|${b.weekEndMs}|${b.resetSignature}|$samples"
        }

    fun decode(text: String): History {
        if (text.isBlank()) return History(emptyList())
        val parsed = text.lineSequence().mapNotNull { decodeLine(it) }.toList()
        // Defensive: merge any buckets that happen to share a weekStart (corruption
        // or a historical config quirk) so consumers see one bucket per boundary.
        if (parsed.isEmpty()) return History(emptyList())
        val byStart = LinkedHashMap<Long, WeekBucket>()
        for (b in parsed.sortedBy { it.weekStartMs }) {
            val existing = byStart[b.weekStartMs]
            byStart[b.weekStartMs] = existing?.copy(
                weekEndMs = maxOf(existing.weekEndMs, b.weekEndMs),
                samples = (existing.samples + b.samples).dedupeByTs(),
            ) ?: b
        }
        return History(byStart.values.sortedBy { it.weekStartMs })
    }

    private fun decodeLine(line: String): WeekBucket? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split("|", limit = 4)
        if (parts.size < 4) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = parts[1].toLongOrNull() ?: return null
        val sig = parts[2]
        if (sig.isEmpty()) return null
        val samples = parts[3].split(";").mapNotNull(::decodeSample).dedupeByTs()
        if (samples.isEmpty()) return null
        return WeekBucket(start, end, sig, samples)
    }

    private fun decodeSample(token: String): Sample? {
        val t = token.trim()
        val comma = t.indexOf(',')
        if (comma <= 0) return null
        val ts = t.substring(0, comma).toLongOrNull() ?: return null
        val pct = t.substring(comma + 1).toIntOrNull() ?: return null
        return Sample(ts, pct.coerceIn(0, 100))
    }
}
