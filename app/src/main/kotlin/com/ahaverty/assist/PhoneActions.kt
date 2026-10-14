package com.ahaverty.assist

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.AlarmClock
import org.json.JSONObject
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
 *   do:navigate?entity=person.x|zone.home[&mode=..]    the same, to where a Home Assistant person or zone is right now
 *   do:navigate?contact=Mam|Ann+Haverty[&mode=..]       the same, to a contact's address on the phone (first name that has one)
 */
object PhoneActions {
    const val SCHEME = "do:"

    class Result(val ok: Boolean, val text: String, val leavesPanel: Boolean = false)

    fun isAction(target: String) = target.startsWith(SCHEME)

    suspend fun run(context: Context, target: String): Result = runCatching {
        val uri = Uri.parse(target.removePrefix(SCHEME).let { if ('?' in it) it else "$it?" }.let { "x://a/$it" })
        val what = uri.path!!.trim('/')
        fun p(k: String) = uri.getQueryParameter(k)?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        when (what) {
            "alarm" -> alarm(context, p("time") ?: return Result(false, "No time for the alarm"), p("label"), p("days"))
            "timer" -> timer(context, p("seconds")?.toIntOrNull() ?: return Result(false, "No length for the timer"), p("label"))
            "alarms" -> start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opened alarms", leaves = true)
            "timers" -> start(context, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Opened timers", leaves = true)
            "open" -> open(context, p("app") ?: return Result(false, "No app named"))
            "navigate" -> p("entity")?.let { navigateToEntity(context, it, p("mode")) }
                ?: p("contact")?.let { navigateToContact(context, it, p("mode")) }
                ?: navigate(context, p("to") ?: return Result(false, "No destination"), p("mode"))
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

    /** A Home Assistant person, device tracker or zone, by its latitude/longitude now (so a moving person works). */
    private suspend fun navigateToEntity(context: Context, entity: String, mode: String?): Result {
        if (!Regex("""^(person|zone|device_tracker)\.[a-z0-9_]+$""").matches(entity)) return Result(false, "Can't navigate to $entity")
        val state = JSONObject(String(App.ha.get("/api/states/$entity")))
        val attrs = state.getJSONObject("attributes")
        val name = attrs.optString("friendly_name").ifEmpty { entity }
        if (!attrs.has("latitude") || !attrs.has("longitude")) return Result(false, "Home Assistant has no location for $name")
        val at = "${attrs.getDouble("latitude")},${attrs.getDouble("longitude")}"
        return navigate(context, at, mode).let { r -> if (r.ok) Result(true, "Directions to $name", true) else r }
    }

    /** Names separated by |, tried in order; within a name, an exact display name beats a partial one. */
    private fun navigateToContact(context: Context, names: String, mode: String?): Result {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Result(false, "Allow Contacts in the Assist app to get directions to people")
        }
        val tried = names.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        for (name in tried) {
            val found = contactAddress(context, name) ?: continue
            return navigate(context, found.second, mode).let { r -> if (r.ok) Result(true, "Directions to ${found.first}", true) else r }
        }
        return Result(false, "No address in contacts for ${tried.joinToString(" or ")}")
    }

    private fun contactAddress(context: Context, name: String): Pair<String, String>? {
        val postal = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI
        val cols = arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
        val rows = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(postal, cols, "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use { c ->
            while (c.moveToNext()) {
                val who = c.getString(0) ?: continue
                val address = c.getString(1)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                rows += who to address
            }
        }
        return rows.firstOrNull { it.first.equals(name, ignoreCase = true) } ?: rows.minByOrNull { it.first.length }
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
