/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context
import android.util.Log

import scala.annotation.tailrec

object ZLog {
  type LogTag = String

  type LogLevel = LogLevel.Value
  object LogLevel extends Enumeration {
    val Verbose, Debug, Warn, Info, Error = Value
  }

  var testLogLevel: LogLevel = LogLevel.Error
  def setTestLogging() = this.testLogLevel = LogLevel.Verbose
  def testLogging: Boolean = this.testLogLevel == LogLevel.Verbose

  @volatile var minimumLogLevel: Int = Log.VERBOSE

  def logTagFor[A <: Singleton](a: A): String = macro ZLogMacros.logTagForSingleton[A]
  def logTagFor[A]: String = macro ZLogMacros.logTagFor[A]

  object ImplicitTag {
    implicit def implicitLogTag: LogTag = macro ZLogMacros.enclosingLogTag
  }

  def error(message: String, cause: Throwable)(implicit tag: LogTag): Unit = macro ZLogMacros.errorWithCause
  def error(message: String)(implicit tag: LogTag): Unit = macro ZLogMacros.error
  def warn(message: String, cause: Throwable)(implicit tag: LogTag): Unit = macro ZLogMacros.warnWithCause
  def warn(message: String)(implicit tag: LogTag): Unit = macro ZLogMacros.warn
  def info(message: String)(implicit tag: LogTag): Unit = macro ZLogMacros.info
  def debug(message: String)(implicit tag: LogTag): Unit = macro ZLogMacros.debug
  def verbose(message: String)(implicit tag: LogTag): Unit = macro ZLogMacros.verbose
  def logTime[A](message: String)(body: A)(implicit tag: LogTag): A = macro ZLogMacros.logTime[A]
}

private object ZLogMacros {
  import ZLog.LogTag

  def errorWithCause(c: Context)(message: c.Expr[String], cause: c.Expr[Throwable])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""
        if (com.waz.utils.isTest) {
          if (com.waz.ZLog.testLogLevel <= com.waz.ZLog.LogLevel.Error) ${reify(println(s"E: ${tag.splice}: ${message.splice}"))}
        }
        else
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.ERROR) android.util.Log.e($tag, $message, $cause)
      """
  }

  def error(c: Context)(message: c.Expr[String])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""
        if (com.waz.utils.isTest) {
          if (com.waz.ZLog.testLogLevel <= com.waz.ZLog.LogLevel.Error) ${reify(println(s"E: ${tag.splice}: ${message.splice}"))}
        }
        else
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.ERROR) android.util.Log.e($tag, $message)
      """
  }

  def warnWithCause(c: Context)(message: c.Expr[String], cause: c.Expr[Throwable])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""
        if (com.waz.utils.isTest) {
          if (com.waz.ZLog.testLogLevel <= com.waz.ZLog.LogLevel.Warn) ${reify(println(s"W: ${tag.splice}: ${message.splice}"))}
        }
        else
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.WARN) android.util.Log.w($tag, $message, $cause)
      """
  }

  def warn(c: Context)(message: c.Expr[String])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""
        if (com.waz.utils.isTest) {
          if(com.waz.ZLog.testLogLevel <= com.waz.ZLog.LogLevel.Warn) ${reify(println(s"W: ${tag.splice}: ${message.splice}"))}
        }
        else
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.WARN) android.util.Log.w($tag, $message)
      """
  }

  def info(c: Context)(message: c.Expr[String])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""
        if (com.waz.utils.isTest) {
          if (com.waz.ZLog.testLogLevel <= com.waz.ZLog.LogLevel.Info) ${reify(println(s"I: ${tag.splice}: ${message.splice}"))}
        }
        else
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.INFO) android.util.Log.i($tag, $message)
      """
  }

  def debug(c: Context)(message: c.Expr[String])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""
        if (com.waz.utils.isTest) {
          if (com.waz.ZLog.testLogLevel <= com.waz.ZLog.LogLevel.Info) ${reify(println(s"D: ${tag.splice}: ${message.splice}"))}
        }
        else
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.DEBUG) android.util.Log.d($tag, $message)
      """
  }

  def verbose(c: Context)(message: c.Expr[String])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""
        if (com.waz.utils.isTest) {
          if (com.waz.ZLog.testLogLevel <= com.waz.ZLog.LogLevel.Verbose) ${reify(println(s"V: ${tag.splice}: ${message.splice}"))}
        }
        else
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.VERBOSE) android.util.Log.v($tag, $message)
      """
  }

  def logTime[A](c: Context)(message: c.Expr[String])(body: c.Expr[A])(tag: c.Expr[LogTag]) = {
    import c.universe._
    q"""val time = System.nanoTime
        try {
          $body
        } finally {
          if (com.waz.ZLog.minimumLogLevel <= android.util.Log.VERBOSE)
            android.util.Log.v($tag, $message + ": " + ((System.nanoTime - time) / 1000 / 1000f) + " ms")
        }
    """
  }

  def logTagForSingleton[A <: Singleton](c: Context)(a: c.Expr[A])(implicit tag: c.WeakTypeTag[A]) = logTagFor[A](c)

  def logTagFor[A](c: Context)(implicit tag: c.WeakTypeTag[A]) = {
    import c.universe._
    val name = tag.tpe.typeSymbol.fullName.split('.').lastOption.getOrElse("UNKNOWN")
    q"$name"
  }

  def enclosingLogTag(c: Context) = {
    import c.universe._

    def nameOf(s: c.Symbol): String = if (s.name.toString == "package") nameOf(s.owner) else s.name.toString

    @tailrec def owningClasses(s: c.Symbol, accu: List[c.Symbol] = Nil): List[c.Symbol] =
      if (s == NoSymbol || s.isPackage) accu
      else if (s.isClass && ! nameOf(s).startsWith("$")) owningClasses(s.owner, s :: accu)
      else owningClasses(s.owner, accu)

    val parents = owningClasses(c.internal.enclosingOwner)
    val name = if (parents.isEmpty) "UNKNOWN" else parents.map(nameOf).mkString(".")

    q"$name"
  }
}
