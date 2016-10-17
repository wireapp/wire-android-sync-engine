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

import android.database.sqlite.SQLiteDatabase
import com.waz.Analytics
import com.waz.ZLog._

import scala.util.control.NonFatal

trait Migration { self =>
  val fromVersion: Int
  val toVersion: Int
  
  def apply(db: SQLiteDatabase): Unit
}

object Migration {
  val AnyVersion = -1

  def apply(from: Int, to: Int)(migrations: (SQLiteDatabase => Unit)*): Migration = new Migration {
    override val toVersion = to
    override val fromVersion = from

    override def apply(db: SQLiteDatabase): Unit = migrations.foreach(_(db))

    override def toString: LogTag = s"Migration from: $from to $to"
  }

  def to(to: Int)(migrations: (SQLiteDatabase => Unit)*): Migration = apply(AnyVersion, to)(migrations:_*)
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
   * Falls back to dropping all data if migration fails.
    *
    * @throws IllegalStateException if no migration plan can be found
   */
  @throws[IllegalStateException]("If no migration plan can be found for given versions")
  def migrate(storage: DaoDB, fromVersion: Int, toVersion: Int)(implicit db: SQLiteDatabase): Unit = {
    if (fromVersion != toVersion) {
      plan(fromVersion, toVersion) match {
        case Nil => throw new IllegalStateException(s"No migration plan from: $fromVersion to: $toVersion")
        case ms =>
          try {
            ms.foreach { m =>
              verbose(s"applying migration: $m")
              inTransaction {
                m(db)
                db.execSQL(s"PRAGMA user_version = ${m.toVersion}")
              }
            }
          } catch {
            case NonFatal(e) =>
              error(s"Migration failed for $storage, from: $fromVersion to: $toVersion", e)
              Analytics.saveException(e, s"Migration failed for $storage, from: $fromVersion to: $toVersion")
              inTransaction {
                fallback(storage, db)
              }
          }
      }
    }
  }

  def fallback(storage: DaoDB, db: SQLiteDatabase): Unit = {
    warn(s"Dropping all data for $storage.")
    storage.dropAllTables(db)
    storage.onCreate(db)
  }
}
