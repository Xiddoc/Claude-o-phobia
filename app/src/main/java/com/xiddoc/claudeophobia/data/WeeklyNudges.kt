package com.xiddoc.claudeophobia.data

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * The pool of cheerful, slightly-competitive daily nudges. Each day we pick one
 * (deterministically for that calendar day, but it changes day to day) and fill
 * in the placeholders:
 *
 *  - `{pct}`   — how far through the week we are right now, as a percentage.
 *                A steady, even pace would have you at roughly this much usage.
 *  - `{day}`   — the name of the weekly reset day (e.g. "Thursday").
 *  - `{usage}` — your *real* weekly utilization, only known when the LSPosed
 *                live-usage feature is on. Templates using it are only ever
 *                chosen when we actually have that figure.
 *  - `{left}`  — how much of the weekly limit is still untouched (100 − usage).
 *  - `{pace}`  — a short ahead/under-pace verdict from [Pacing].
 *
 * Pure Kotlin, no Android types, so it can be unit-tested directly.
 */
object WeeklyNudges {

    data class Nudge(val title: String, val body: String)

    /** A deliberately big list so the nudges stay fresh for a long time. */
    val TEMPLATES: List<String> = listOf(
        "Did you reach {pct} usage yet this week?",
        "We're {pct} of the way to {day} — keep the momentum going! 🧡",
        "We're {pct} there to {day}, this is the final sprint! 🏁",
        "{pct} of the week is gone. A steady pace would put you right around {pct} usage.",
        "Pace check: aiming for ~{pct} by now keeps you smooth until {day}.",
        "Halfway-ish? You're {pct} into the week. How's the usage looking?",
        "{pct} through to {day}. Spend it wisely, future-you says thanks.",
        "Fresh day, same goal: stay near {pct} usage to glide into {day}.",
        "Tick tock — {pct} of the week elapsed. Are you ahead or behind?",
        "You're {pct} of the way to your reset. Every prompt counts!",
        "{day} is calling. We're {pct} there — don't burn out early! 🔥",
        "Steady wins it: {pct} of the week done, {pct} usage is the sweet spot.",
        "Reset day ({day}) creeps closer — {pct} of the week behind us.",
        "Have you hit {pct} yet? That's the even-pace target for today.",
        "{pct} down, the rest to go. Save a little for the {day} stretch.",
        "Quick gut-check: are you under {pct} usage? Plenty of room if so. 🚀",
        "We're {pct} into the week. The countdown to {day} is on.",
        "Almost {day}! {pct} elapsed — finish strong, pace yourself.",
        "A new day, a new nudge: keep your usage close to {pct} for now.",
        "{pct} of the way through. Are your prompts pulling their weight?",
        "Pacing tip: {pct} of the week is gone, so ~{pct} usage means you're bang on.",
        "Don't sprint too soon — only {pct} of the week is behind you.",
        "{pct} toward {day}. The reset will feel so good. Keep going! ✨",
        "How's the budget? {pct} of the week elapsed, {pct} usage is on-pace.",
        "Eyes on {day}: we're {pct} there. Smooth and steady does it.",
        "{pct} complete. Reach {pct} usage and you're perfectly paced.",
        "The week's {pct} done. Got enough left in the tank for {day}?",
        "Onwards! {pct} of the way to your weekly reset on {day}.",
    )

    /**
     * Templates that quote the user's *real* usage. These are only ever in the
     * running when the LSPosed live-usage feature has handed us an actual figure,
     * so it's safe for them to lean on `{usage}`, `{left}` and `{pace}`.
     */
    val USAGE_TEMPLATES: List<String> = listOf(
        "You're at {usage} of your weekly limit — {pace}.",
        "{usage} used so far, with {left} still in the tank. {pace}!",
        "Real talk: {usage} of the week's limit is gone, {pct} of the week elapsed. {pace}.",
        "Progress check — {usage} used, {left} left before {day}. {pace}.",
        "{usage} down. You're {pace} versus the {pct} mark. Keep it smooth!",
        "Heads up: only {left} of your weekly limit remains before {day}.",
        "{usage} of the limit used at {pct} through the week — {pace}.",
        "Pace report: {usage} used vs {pct} elapsed. {pace}. ✨",
        "You've spent {usage} of the week's allowance; {left} is yours till {day}.",
        "{pace}. That's {usage} used against {pct} of the week gone by.",
    )

    /**
     * Picks the nudge for a given calendar day. [epochDay] seeds the choice so
     * it's stable across re-reads within the same day but rotates each day.
     *
     * When [usagePercent] is non-null (the LSPosed live-usage feature is on and we
     * have a real figure) the usage-aware templates join the pool, so *some* days'
     * nudges quote how far along — or how much is left — the user actually is. When
     * it's null those templates are excluded entirely and we fall back to the
     * pace-only wording, which is all we can honestly say.
     */
    fun forDay(
        epochDay: Long,
        weekFraction: Double,
        resetDay: DayOfWeek,
        usagePercent: Int? = null,
        locale: Locale = Locale.getDefault(),
    ): Nudge {
        val elapsed = (weekFraction.coerceIn(0.0, 1.0) * 100).toInt()
        val pct = "$elapsed%"
        val day = resetDay.getDisplayName(TextStyle.FULL, locale)

        val pool = if (usagePercent != null) TEMPLATES + USAGE_TEMPLATES else TEMPLATES
        val template = pool[Random(epochDay).nextInt(pool.size)]

        var body = template.replace("{pct}", pct).replace("{day}", day)
        if (usagePercent != null) {
            val usage = usagePercent.coerceIn(0, 100)
            val delta = Pacing.delta(usage, elapsed)
            body = body
                .replace("{usage}", "$usage%")
                .replace("{left}", "${100 - usage}%")
                .replace("{pace}", Pacing.shortLabel(delta))
        }
        return Nudge(title = "Claude-o-phobia", body = body)
    }

    /**
     * Whether a nudge should fire on [epochDay] to hit a target of [perWeek]
     * notifications across each 7-day block.
     *
     * Uses a Bresenham-style spread: exactly [perWeek] of every seven consecutive
     * days return true, distributed as evenly as integer days allow (e.g. 3/week
     * lands roughly every other day rather than three days in a row). The daily
     * alarm still wakes every day — this just gates whether it actually posts —
     * so the answer is purely a function of the calendar day and the target.
     */
    fun shouldNudgeOn(epochDay: Long, perWeek: Int): Boolean {
        val n = perWeek.coerceIn(0, 7)
        if (n == 0) return false
        if (n == 7) return true
        return Math.floorDiv(epochDay * n, 7L) != Math.floorDiv((epochDay - 1) * n, 7L)
    }
}
