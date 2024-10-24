/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater.updater

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.github.pixelupdater.pixelupdater.Notifications
import com.github.pixelupdater.pixelupdater.Permissions
import com.github.pixelupdater.pixelupdater.Preferences
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class UpdaterJob: JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = Preferences(this)

        if (!Permissions.haveRequired(applicationContext) || !Notifications.areEnabled(applicationContext)) {
            Log.i(TAG, "Notifications are disabled, job skipped")
            return false
        }

        val actionIndex = params.extras.getInt(EXTRA_ACTION, -1)
        val isPeriodic = actionIndex == -1

        if (isPeriodic) {
            if (!prefs.automaticCheck) {
                Log.i(TAG, "Automatic update checks are disabled")
                return false
            } else if (skipNextRun) {
                Log.i(TAG, "Skipped this run of the periodic job")
                skipNextRun = false
                return false
            }
        }

        val action = if (!isPeriodic) {
            UpdaterThread.Action.entries[actionIndex]
        } else {
            UpdaterThread.Action.CHECK
        }

        startForegroundService(UpdaterService.createStartIntent(
            applicationContext, params.network, action, isPeriodic))
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return false
    }

    companion object {
        private val TAG = UpdaterJob::class.java.simpleName

        private const val ID_IMMEDIATE = 1
        private const val ID_PERIODIC = 2

        private const val EXTRA_ACTION = "action"

        private const val PERIODIC_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val DAILY_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val WEEKLY_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MONTHLY_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

        // Scheduling a periodic job usually makes the first iteration run immediately. We'll
        // sometimes skip this to avoid unexpected operations while the user is configuring
        // settings in the UI.
        private var skipNextRun = false

        private var timeout: Deferred<Unit>? = null

        private fun createJobBuilder(
            context: Context,
            jobId: Int,
            action: UpdaterThread.Action?,
        ): JobInfo.Builder {
            val prefs = Preferences(context)

            val builder = JobInfo.Builder(jobId, ComponentName(context, UpdaterJob::class.java))

            val networkType = if (action == UpdaterThread.Action.INSTALL && prefs.requireUnmetered) {
                JobInfo.NETWORK_TYPE_UNMETERED
            } else {
                JobInfo.NETWORK_TYPE_ANY
            }
            builder
                .setRequiredNetworkType(networkType)

            if (action == UpdaterThread.Action.INSTALL || action == UpdaterThread.Action.SWITCH_SLOT) {
                builder
                    .setRequiresBatteryNotLow(prefs.requireBatteryNotLow)
            }

            val extras = PersistableBundle().apply {
                if (action != null) {
                    putInt(EXTRA_ACTION, action.ordinal)
                }
            }

            return builder.setExtras(extras)
        }

        @DelicateCoroutinesApi
        private fun scheduleIfUnchanged(context: Context, jobInfo: JobInfo) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)

            val oldJobInfo = jobScheduler.getPendingJob(jobInfo.id)

            // JobInfo.equals() is unreliable (and the comments in its implementation say so), so
            // just compare the fields that we set. We don't compare the extras because there's no
            // sane way to do so. That doesn't matter for our use case because this check is mostly
            // useful for the periodic job, which doesn't use extras.
            if (oldJobInfo != null &&
                oldJobInfo.requiredNetwork == jobInfo.requiredNetwork &&
                oldJobInfo.isRequireBatteryNotLow == jobInfo.isRequireBatteryNotLow &&
                oldJobInfo.isPersisted == jobInfo.isPersisted &&
                oldJobInfo.intervalMillis == jobInfo.intervalMillis &&
                oldJobInfo.isPeriodic == jobInfo.isPeriodic &&
                oldJobInfo.intervalMillis == jobInfo.intervalMillis) {
                Log.i(TAG, "Job already exists and is unchanged: $jobInfo")
                return
            }

            if (jobInfo.id == ID_IMMEDIATE) {
                if (timeout != null) {
                    timeout!!.cancel()
                }
            }

            Log.d(TAG, "Scheduling job: $jobInfo")

            when (val result = jobScheduler.schedule(jobInfo)) {
                JobScheduler.RESULT_SUCCESS -> {
                    Log.d(TAG, "Scheduled job: $jobInfo")
                    if (jobInfo.id == ID_IMMEDIATE) {
                        timeout = GlobalScope.async {
                            withContext(Dispatchers.IO) {
                                delay(2000)
                                if (jobScheduler.getPendingJob(jobInfo.id) != null) {
                                    jobScheduler.cancel(jobInfo.id)
                                    val actionIndex = jobInfo.extras.getInt(EXTRA_ACTION)
                                    val action = UpdaterThread.Action.entries[actionIndex]
                                    context.startForegroundService(UpdaterService.createFailIntent(context, action))
                                }
                            }
                        }
                    }
                }
                JobScheduler.RESULT_FAILURE ->
                    Log.w(TAG, "Failed to schedule job: $jobInfo")
                else -> throw IllegalStateException("Unexpected scheduler error: $result")
            }
        }

        fun scheduleImmediate(context: Context, action: UpdaterThread.Action) {
            val jobInfo = createJobBuilder(context, ID_IMMEDIATE, action).build()

            scheduleIfUnchanged(context, jobInfo)
        }

        fun schedulePeriodic(context: Context, skipFirstRun: Boolean) {
            val prefs = Preferences(context)
            val interval = if (prefs.updateNotified) {
                when (prefs.notificationFrequency) {
                    "daily" -> DAILY_INTERVAL_MS
                    "weekly" -> WEEKLY_INTERVAL_MS
                    "monthly" -> MONTHLY_INTERVAL_MS
                    else -> PERIODIC_INTERVAL_MS
                }
            } else {
                PERIODIC_INTERVAL_MS
            }

            val jobInfo = createJobBuilder(context, ID_PERIODIC, null)
                .setPersisted(true)
                .setPeriodic(interval)
                .build()

            skipNextRun = skipFirstRun

            scheduleIfUnchanged(context, jobInfo)
        }
    }
}