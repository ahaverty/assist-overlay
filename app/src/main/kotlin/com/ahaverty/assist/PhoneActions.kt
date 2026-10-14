package com.ahaverty.assist

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import java.util.Calendar

/**
 * Things Friday does on the phone itself. The phone persona ends a reply with a line like
 * `![Alarm 7:00](do:alarm?time=07:00&label=Gym)`; the app carries it out once the reply has finished
 * (never when an old chat is shown again), shows it as a small chip and never speaks it.
 *
 *   do:alarm?time=HH:MM[&label=..][&days=mon,tue,..]   set an alarm (Clock app, no screen)
 *   do:timer?seconds=N[&label=..]                       start a timer
 *   do:alarms / do:timers                               show the Clock app's alarms / timers
 *   do:open?app=Spotify                                 open an app by its name
 *   do:navigate?to=..[&mode=d|w|b|l]                    Google Maps turn-by-turn (drive/walk/bike/two-wheeler)
 */
object PhoneActions {
    const val SCHEME = "do:"

    class Result(val ok: Boolean, val text: String, val leavesPanel: Boolean = false)

    fun isAction(target: String) = target.startsWith(SCHEME)

    fun run(context: Context, target: String): Result = runCatching {
        val uri = Uri.parse(target.removePrefix(SCHEME).let { if ('?' in it) it else "$it?" }.let { "x://a/$it" })
        val what = uri.path!!.trim('/')
        fun p(k: String) = uri.getQueryParameter(k)?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        when (what) {
            "alarm" -> alarm(context, p("time") ?: return Result(false, "No time for the alarm"), p("label"), p("days"))
            "timer" -> timer(context, p("seconds")?.toIntOrNull() ?: return Result(false, "No length for the timer"), p("label"))
            "alarms" -> start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opened alarms", leaves = true)
            "timers" -> start(context, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Opened timers", leaves = true)
            "open" -> open(context, p("app") ?: return Result(false, "No app named"))
            "navigate" -> navigate(context, p("to") ?: return Result(false, "No destination"), p("mode"))
            else -> Result(false, "Unknown phone action: $what")
        }
    }.getOrElse { Result(false, "Couldn't do that on the phone (${it.message ?: it.javaClass.simpleName})") }

    private fun alarm(context: Context, time: String, label: String?, days: String?): Result {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(time) ?: return Result(false, "Bad alarm time: $time")
        val (h, min) = m.destructured.toList().map { it.toInt() }
        if (h > 23 || min > 59) return Result(false, "Bad alarm time: $time")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val dayList = days?.split(',')?.mapNotNull { DAYS[it.trim().lowercase().take(3)] }.orEmpty()
        if (dayList.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(dayList))
        val shown = "%d:%02d".format(h, min)
        return start(context, intent, "Alarm set for $shown" + (label?.let { " ($it)" } ?: ""))
    }

    private fun timer(context: Context, seconds: Int, label: String?): Result {
        if (seconds !in 1..86_400) return Result(false, "Bad timer length: $seconds s")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return start(context, intent, "Timer started: ${length(seconds)}" + (label?.let { " ($it)" } ?: ""))
    }

    /** Launcher apps matched on their label: exact, then starts-with, then contains (ignoring case and spaces). */
    private fun open(context: Context, name: String): Result {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val want = norm(name)
        val hit = apps.firstOrNull { norm(it.first) == want }
            ?: apps.filter { norm(it.first).startsWith(want) }.minByOrNull { it.first.length }
            ?: apps.filter { norm(it.first).contains(want) }.minByOrNull { it.first.length }
            ?: return Result(false, "No app called $name")
        val launch = pm.getLaunchIntentForPackage(hit.second) ?: return Result(false, "${hit.first} can't be opened")
        return start(context, launch, "Opened ${hit.first}", leaves = true)
    }

    private fun navigate(context: Context, to: String, mode: String?): Result {
        val m = mode?.lowercase()?.take(1)?.takeIf { it in setOf("d", "w", "b", "l") } ?: "d"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(to)}&mode=$m"))
            .setPackage("com.google.android.apps.maps")
        return start(context, intent, "Directions to $to", leaves = true)
    }

    private fun start(context: Context, intent: Intent, done: String, leaves: Boolean = false): Result = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Result(true, done, leaves)
    } catch (e: ActivityNotFoundException) {
        Result(false, "No app on the phone can do that")
    }

    private fun length(s: Int) = listOfNotNull(
        (s / 3600).takeIf { it > 0 }?.let { "$it h" },
        (s % 3600 / 60).takeIf { it > 0 }?.let { "$it min" },
        (s % 60).takeIf { it > 0 }?.let { "$it s" },
    ).joinToString(" ")

    private val DAYS = mapOf(
        "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
    )
}
