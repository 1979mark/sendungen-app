package de.mmc.sendungen

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * Widget für den Startbildschirm: zeigt die nächsten Sendungen mit Zustellung.
 * Die Daten kommen aus der letzten Aktualisierung in der App.
 */
class SendungenWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, ansicht(context))
    }

    companion object {
        private val zeilen = intArrayOf(R.id.zeile1, R.id.zeile2, R.id.zeile3, R.id.zeile4)
        private val stati = intArrayOf(R.id.status1, R.id.status2, R.id.status3, R.id.status4)
        private val bloecke = intArrayOf(R.id.block1, R.id.block2, R.id.block3, R.id.block4)

        fun aktualisiereAlle(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, SendungenWidget::class.java))
                if (ids.isEmpty()) return
                val views = ansicht(context)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (_: Exception) {
            }
        }

        private fun ansicht(context: Context): RemoteViews {
            val p = context.palette()
            val v = RemoteViews(context.packageName, R.layout.widget_sendungen)
            val unterwegs = try {
                SendungDb(context).aufgeteilt().first
            } catch (_: Exception) {
                emptyList()
            }

            v.setTextViewText(
                R.id.widget_titel,
                when (unterwegs.size) {
                    0 -> "Sendungen"
                    1 -> "1 Sendung unterwegs"
                    else -> "${unterwegs.size} Sendungen unterwegs"
                }
            )
            v.setTextColor(R.id.widget_titel, p.text)

            for (i in bloecke.indices) {
                val s = unterwegs.getOrNull(i)
                if (s == null) {
                    v.setViewVisibility(bloecke[i], View.GONE)
                } else {
                    v.setViewVisibility(bloecke[i], View.VISIBLE)
                    v.setTextViewText(zeilen[i], s.anzeigeName())
                    v.setTextColor(zeilen[i], p.text)
                    val status = s.kurzStatus()
                    v.setTextViewText(stati[i], status)
                    v.setTextColor(stati[i], p.statusFarbe(if (status.startsWith("Heute")) Status.HEUTE else s.status))
                }
            }
            v.setViewVisibility(R.id.widget_leer, if (unterwegs.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextColor(R.id.widget_leer, p.text2)

            val oeffnen = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widget_wurzel, oeffnen)
            return v
        }
    }
}
