package com.xiddoc.claudeophobia.data

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * The pool of cheerful, slightly-competitive daily nudges. Each day we pick one
 * (deterministically for that calendar day, but it changes day to day) and fill
 * in two placeholders:
 *
 *  - `{pct}`  — how far through the week we are right now, as a percentage.
 *               A steady, even pace would have you at roughly this much usage.
 *  - `{day}`  — the name of the weekly reset day (e.g. "Thursday").
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
     * Picks the nudge for a given calendar day. [epochDay] seeds the choice so
     * it's stable across re-reads within the same day but rotates each day.
     */
    fun forDay(
        epochDay: Long,
        weekFraction: Double,
        resetDay: DayOfWeek,
        locale: Locale = Locale.getDefault(),
    ): Nudge {
        val pct = "${(weekFraction.coerceIn(0.0, 1.0) * 100).toInt()}%"
        val day = resetDay.getDisplayName(TextStyle.FULL, locale)
        val template = TEMPLATES[(Random(epochDay).nextInt(TEMPLATES.size))]
        val body = template.replace("{pct}", pct).replace("{day}", day)
        return Nudge(title = "Claude-o-phobia", body = body)
    }
}
