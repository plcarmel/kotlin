/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.statistics

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.statistics.fileloggers.BuildSessionLogger.Companion.STATISTICS_FILE_NAME_PATTERN
import org.jetbrains.kotlin.statistics.fileloggers.BuildSessionLogger.Companion.STATISTICS_FOLDER_NAME
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KotlinGradleFUSLogger : StartupActivity, DumbAware, Runnable {

    override fun runActivity(project: Project) {
        AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(this, EXECUTION_DELAY_MIN, EXECUTION_DELAY_MIN, TimeUnit.MINUTES)
    }

    override fun run() {
        reportStatistics()
    }

    companion object {

        /**
         * Maximum amount of directories which were reported as gradle user dirs
         * These directories should be monitored for reported gradle statistics.
         */
        const val MAXIMUM_USER_DIRS = 10

        /**
         * Delay between sequential checks of gradle statistics
         */
        const val EXECUTION_DELAY_MIN = 60L

        /**
         * Property name used for persisting gradle user dirs
         */
        const val GRADLE_USER_DIRS_PROPERTY_NAME = "kotlin-gradle-user-dirs"

        private val isRunning = AtomicBoolean(false)

        private fun processFile(file: File) {
            val channel = FileChannel.open(Paths.get(file.toURI()), StandardOpenOption.READ)
            val lock = try {
                channel.lock()
            } catch (e: IOException) {
                channel.close()
                throw e
            }
            val inputStream = Channels.newOutputStream(channel)
            try {
                // read started marker
                // read all metrics
                // pass all read metrics to processor
                // processor should group into events and send

                //TODO read file content and report it's content
            } finally {
                channel.use {
                    inputStream?.use {
                        lock.release()
                    }
                }
            }

            //delete file

            // unblock file
        }

        fun reportStatistics() {
            if (isRunning.weakCompareAndSet(false, true)) {
                try {
                    for (gradleUserHome in getGradleUserDirs()) {
                        File(gradleUserHome, STATISTICS_FOLDER_NAME).listFiles()?.filter {
                            it?.name?.matches(STATISTICS_FILE_NAME_PATTERN.toRegex()) ?: false
                        }?.forEach { statisticFile ->
                            try {
                                processFile(statisticFile)
                            } catch (e: Exception) {
                                //TODO log
                            } finally {
                                //TODO log if failed to delete
                                statisticFile.delete()
                            }

                        }
                    }
                } finally {
                    isRunning.set(false)
                }
            }
        }

        private fun getGradleUserDirs(): Array<String> {
            return PropertiesComponent.getInstance().getValues(GRADLE_USER_DIRS_PROPERTY_NAME) ?: emptyArray()
        }

        fun populateGradleUserDir(path: String) {
            val currentState = getGradleUserDirs()
            if (currentState.contains(path)){
                return
            }

            val result = ArrayList<String>()
            result.add(path)
            result.addAll(currentState)

            PropertiesComponent.getInstance().setValues(
                GRADLE_USER_DIRS_PROPERTY_NAME,
                result.filter { path -> File(path).exists() }.filterIndexed { i, _ -> i < MAXIMUM_USER_DIRS }.toTypedArray()
            )
        }
    }
}