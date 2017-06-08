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
package com.waz.db

import java.io.{File, FileInputStream, FileOutputStream}

import android.database.sqlite.SQLiteDatabase
import com.waz.HockeyApp
import com.waz.ZLog._
import com.waz.api.ZmsVersion
import com.waz.utils.wrappers.DB

import scala.util.control.NonFatal

trait Migration { self =>
  val fromVersion: Int
  val toVersion: Int
  
  def apply(db: DB): Unit
}

object Migration {
  val AnyVersion = -1

  def apply(from: Int, to: Int)(migrations: (DB => Unit)*): Migration = new Migration {
    override val toVersion = to
    override val fromVersion = from

    override def apply(db: DB): Unit = migrations.foreach(_(db))

    override def toString: LogTag = s"Migration from: $from to $to"
  }

  def to(to: Int)(migrations: (DB => Unit)*): Migration = apply(AnyVersion, to)(migrations:_*)
}

/**
 * Uses given list of migrations to migrate database from one version to another.
 * Finds shortest migration path and applies it.
 */
class Migrations(migrations: Migration*) {

  private implicit val logTag: LogTag = logTagFor[Migrations]
  val toVersionMap = migrations.groupBy(_.toVersion)

  def plan(from: Int, to: Int): List[Migration] = {

    def shortest(from: Int, to: Int): List[Migration] = {
      val possible = toVersionMap.getOrElse(to, Nil)
      val plans = possible.map { m =>
        if (m.fromVersion == from || m.fromVersion == Migration.AnyVersion) List(m)
        else if (m.fromVersion < from) Nil
        else shortest(from, m.fromVersion) match {
          case Nil => List()
          case best => best ::: List(m)
        }
      } .filter(_.nonEmpty)

      if (plans.isEmpty) Nil
      else plans.minBy(_.length)
    }

    if (from == to) Nil
    else shortest(from, to)
  }

  /**
    * Migrates database using provided migrations.
    * Signals the app on failure, so that we can disable use of this version - this should prevent us from accidentally
    * dropping internal-user history in case of a migration error. We still drop the tables anyway with the current db
    * after creating the backup, this should allow the user to continue using the app with lost history.
    *
    * Note, SQLiteOpenHelper#onUpgrade is already called from within an exclusive transaction when the system realises
    * that a database you are trying to access needs to be upgraded. So we can just execute all sql commands directly
    * (no point in calling nested transactions for code we control)
    *
    * @throws IllegalStateException if no migration plan can be found
    */
  @throws[IllegalStateException]("If no migration plan can be found for given versions")
  def migrate(storage: DaoDB, fromVersion: Int, toVersion: Int)(implicit db: SQLiteDatabase): Unit = {
    if (fromVersion != toVersion) {

      //If there is an existing backup, replace the current db with it so that we apply migrations to the backed up data.
      if (ZmsVersion.DEBUG) {
        if (storage.backupFile.exists()) copy(storage.backupFile, new File(db.getPath))
        else copy(new File(db.getPath), storage.backupFile)
      }

      plan(fromVersion, toVersion) match {
        case Nil => throw new IllegalStateException(s"No migration plan from: $fromVersion to: $toVersion")
        case ms =>
          try {
            ms.foreach { m =>
              verbose(s"applying migration: $m")
              m(db)
              db.execSQL(s"PRAGMA user_version = ${m.toVersion}")
            }
            if (ZmsVersion.DEBUG) clearBackup(storage)
          } catch {
            case NonFatal(e) =>
              error(s"Migration failed for $storage, from: $fromVersion to: $toVersion", e)
              HockeyApp.saveException(e, s"Migration failed for $storage, from: $fromVersion to: $toVersion")
              storage.backupFailed ! true
              fallback(storage, db)
          }
      }
    }
  }

  def copy(src: File, dst: File) =
    new FileOutputStream(dst).getChannel.transferFrom(new FileInputStream(src).getChannel, 0, Int.MaxValue)

  def clearBackup(storage: DaoDB) = storage.backupFile.delete()

  def fallback(storage: DaoDB, db: SQLiteDatabase): Unit = {
    warn(s"Dropping all data for $storage.")
    storage.dropAllTables(db)
    storage.onCreate(db)
  }
}
