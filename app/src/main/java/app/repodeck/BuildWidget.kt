package app.repodeck

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/** Home-screen widget showing the real status of the latest workflow run of the pinned repository. */
class BuildWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    private fun refresh(context: Context) {
        val pending = goAsync()
        thread { try { render(context) } finally { pending.finish() } }
    }

    companion object {
        const val ACTION_REFRESH = "app.repodeck.WIDGET_REFRESH"

        fun requestUpdate(ctx: Context) {
            ctx.sendBroadcast(Intent(ctx, BuildWidget::class.java).setAction(ACTION_REFRESH))
        }

        fun render(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BuildWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_build)
            val repo = Graph.store.widgetRepo
            var status = ""
            var detail = ""
            var color: Int = Color.WHITE
            if (repo.isEmpty() || Graph.store.activeToken() == null) {
                status = ctx.getString(R.string.widget_no_repo)
            } else {
                try {
                    runBlocking {
                        val run = Graph.api.get("/repos/$repo/actions/runs?per_page=1").o().list("workflow_runs").firstOrNull()
                        if (run == null) {
                            status = ctx.getString(R.string.nothing_here)
                        } else {
                            val st = run.s("status")
                            val c = run.s("conclusion")
                            var extra = ""
                            when {
                                st != "completed" -> { status = ctx.getString(R.string.widget_running); color = 0xFFFBBF24.toInt() }
                                c == "success" -> { status = ctx.getString(R.string.widget_success); color = 0xFF34D399.toInt() }
                                c == "cancelled" || c == "skipped" -> { status = ctx.getString(R.string.widget_cancelled); color = 0xFFCBD5E1.toInt() }
                                else -> {
                                    status = ctx.getString(R.string.widget_failed); color = 0xFFF87171.toInt()
                                    val jobs = Graph.api.get("/repos/$repo/actions/runs/${run.n("id")}/jobs").o().list("jobs")
                                    val step = jobs.flatMap { it.list("steps") }.firstOrNull { it.s("conclusion") == "failure" }?.s("name")
                                    if (step != null) extra = "\n" + ctx.getString(R.string.widget_failed_at, step)
                                }
                            }
                            detail = "#${run.n("run_number")} ${run.s("name")} \u00b7 ${run.s("head_branch")} \u00b7 ${Fmt.time(run.s("updated_at"))}$extra"
                        }
                    }
                } catch (e: Exception) {
                    status = ctx.getString(R.string.widget_error)
                    detail = Fmt.err(e)
                    color = 0xFFF87171.toInt()
                }
            }
            v.setTextViewText(R.id.w_repo, if (repo.isEmpty()) ctx.getString(R.string.app_name) else repo)
            v.setTextViewText(R.id.w_status, status)
            v.setTextColor(R.id.w_status, color)
            v.setTextViewText(R.id.w_detail, detail)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags))
            v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(ctx, 1, Intent(ctx, BuildWidget::class.java).setAction(ACTION_REFRESH), flags))
            mgr.updateAppWidget(ids, v)
        }
    }
}
