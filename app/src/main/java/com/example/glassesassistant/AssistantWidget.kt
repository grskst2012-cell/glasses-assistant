package com.example.glassesassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Виджет рабочего стола: одно касание запускает ассистента или начинает слушать. */
class AssistantWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(context))
    }

    companion object {
        private fun views(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setTextViewText(R.id.widget_text, ctx.getString(if (AssistantService.running) R.string.widget_talk else R.string.widget_start))
            rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 10,
                Intent(ctx, TriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            return rv
        }

        fun update(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, AssistantWidget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, views(ctx))
        }
    }
}
