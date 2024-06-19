/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util

import org.apache.spark.executor.{ExecutorExitCode, KilledByTaskReaperException}
import org.apache.spark.internal.{Logging, MDC}
import org.apache.spark.internal.LogKeys.THREAD

/**
 * The default uncaught exception handler for Spark daemons. It terminates the whole process for
 * any Errors, and also terminates the process for Exceptions when the exitOnException flag is true.
 *
 * @param exitOnUncaughtException Whether to exit the process on UncaughtException.
 */
private[spark] class SparkUncaughtExceptionHandler(val exitOnUncaughtException: Boolean = true)
  extends Thread.UncaughtExceptionHandler with Logging {

  locally {
    // eagerly load SparkExitCode class, so the System.exit and runtime.halt have a chance to be
    // executed when the disk containing Spark jars is corrupted. See SPARK-44542 for more details.
    val _ = SparkExitCode.OOM
  }

  override def uncaughtException(thread: Thread, exception: Throwable): Unit = {
    try {
      val mdc = MDC(THREAD, thread)
      // Make it explicit that uncaught exceptions are thrown when container is shutting down.
      // It will help users when they analyze the executor logs
      if (ShutdownHookManager.inShutdown()) {
        logError(log"[Container in shutdown] Uncaught exception in thread $mdc", exception)
      } else {
        logError(log"Uncaught exception in thread $mdc", exception)
      }

      // We may have been called from a shutdown hook. If so, we must not call System.exit().
      // (If we do, we will deadlock.)
      if (!ShutdownHookManager.inShutdown()) {
        exception match {
          case _: OutOfMemoryError =>
            System.exit(SparkExitCode.OOM)
          case e: SparkFatalException if e.throwable.isInstanceOf[OutOfMemoryError] =>
            // SPARK-24294: This is defensive code, in case that SparkFatalException is
            // misused and uncaught.
            System.exit(SparkExitCode.OOM)
          case _: KilledByTaskReaperException if exitOnUncaughtException =>
            System.exit(ExecutorExitCode.KILLED_BY_TASK_REAPER)
          case _ if exitOnUncaughtException =>
            System.exit(SparkExitCode.UNCAUGHT_EXCEPTION)
          case _ =>
            // SPARK-30310: Don't System.exit() when exitOnUncaughtException is false
        }
      }
    } catch {
      case oom: OutOfMemoryError =>
        try {
          logError(
            log"Uncaught OutOfMemoryError in thread ${MDC(THREAD, thread)}, process halted.",
            oom)
        } catch {
          // absorb any exception/error since we're halting the process
          case _: Throwable =>
        }
        Runtime.getRuntime.halt(SparkExitCode.OOM)
      case t: Throwable =>
        try {
          logError(
            log"Another uncaught exception in thread ${MDC(THREAD, thread)}, process halted.",
            t)
        } catch {
          case _: Throwable =>
        }
        Runtime.getRuntime.halt(SparkExitCode.UNCAUGHT_EXCEPTION_TWICE)
    }
  }

  def uncaughtException(exception: Throwable): Unit = {
    uncaughtException(Thread.currentThread(), exception)
  }
}
