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

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.cache.CacheEntryData.CacheEntryDao
import com.waz.content.ZmsDatabase
import com.waz.db.Col._
import com.waz.db.ZGlobalDB.{DbName, DbVersion, Migrations, daos}
import com.waz.db.migrate.{AccountDataMigration, TableDesc, TableMigration}
import com.waz.model.AccountData.AccountDataDao
import com.waz.model.TeamData.TeamDataDoa
import com.waz.model.otr.ClientId
import com.waz.model.{AccountId, UserId}
import com.waz.utils.wrappers.DB
import com.waz.utils.{IoUtils, JsonDecoder, JsonEncoder, Resource}
import com.waz.znet.AuthenticationManager.Token

class ZGlobalDB(context: Context, dbNameSuffix: String = "")
  extends DaoDB(context.getApplicationContext, DbName + dbNameSuffix, null, DbVersion, daos, Migrations.migrations(context)) {

  override def onUpgrade(db: SQLiteDatabase, from: Int, to: Int): Unit = {
    if (from < 5) clearAllData(db)
    else super.onUpgrade(db, from, to)
  }

  def clearAllData(db: SQLiteDatabase) = {
    debug("wiping global db...")
    dropAllTables(db)
    onCreate(db)
  }
}

object ZGlobalDB {
  val DbName = "ZGlobal.db"
  val DbVersion = 21

  lazy val daos = Seq(AccountDataDao, CacheEntryDao, TeamDataDoa)

  object Migrations {

    def migrations(context: Context) = Seq(
      Migration(5, 6) { implicit db =>
        import CacheEntryDao._

        debug("Upgrading global db to accommodate for upgraded image caching...")
        Seq("path", "lastUsed", "timeout") foreach { col => db.execSQL(s"ALTER TABLE CacheEntry ADD COLUMN $col TEXT") }
        setPathForFileEntries(context.getCacheDir)
        moveToTimeouts()
      },
      Migration(6, 7) { implicit db =>
        debug("Upgrading global db to prepare for phone number verification...")
        db.execSQL(s"ALTER TABLE ZUsers ADD COLUMN phone TEXT")
      },
      Migration(7, 8) { implicit db =>
        debug("Upgrading global DB to remember if phone number was verified…")
        db.execSQL(s"ALTER TABLE ZUsers ADD COLUMN phone_verified INTEGER")
        db.execSQL(s"UPDATE ZUsers SET phone_verified = phone is not null")
      },
      Migration(8, 9) { implicit db => db.execSQL("ALTER TABLE CacheEntry ADD COLUMN enc_key TEXT") },
      Migration(9, 10) { implicit db =>
        db.execSQL("ALTER TABLE CacheEntry ADD COLUMN mime TEXT default ''")
        db.execSQL("ALTER TABLE CacheEntry ADD COLUMN file_name TEXT")
      },
      Migration(10, 11) { implicit db =>
        db.execSQL("DELETE FROM CacheEntry WHERE enc_key IS NOT NULL") // encryption handling was changed, so it's easiest to just drop old cache entries
      },
      Migration(11, 12) { implicit db =>
        db.execSQL("ALTER TABLE CacheEntry ADD COLUMN length INTEGER")
      },
      Migration(12, 13)(Migrations.v11(context)),
      Migration(13, 14) {
        implicit db => AccountDataMigration.v14(db)
      },
      Migration(14, 15) { db =>
//      no longer valid
      },
      Migration(15, 16) { db =>
//      no longer valid
      },
      Migration(16, 17) { db =>
        db.execSQL(s"ALTER TABLE Accounts ADD COLUMN registered_push TEXT")
      },
      Migration(17, 18) { db =>
        db.execSQL("ALTER TABLE Accounts ADD COLUMN teamId TEXT")
        db.execSQL("UPDATE Accounts SET teamId = ''")
        db.execSQL("ALTER TABLE Accounts ADD COLUMN self_permissions INTEGER DEFAULT 0")
        db.execSQL("ALTER TABLE Accounts ADD COLUMN copy_permissions INTEGER DEFAULT 0")
      },
      Migration(18, 19) { db =>
        db.execSQL("CREATE TABLE IF NOT EXISTS Teams (_id TEXT PRIMARY KEY, name TEXT, creator TEXT, icon TEXT, icon_key TEXT)")
      },
      Migration(19, 20) { db =>
        AccountDataMigration.v20(db)
      },
      Migration(20, 21) { db =>
        db.execSQL("ALTER TABLE Accounts ADD COLUMN pending_team_name TEXT DEFAULT NULL")
      }
    )

    implicit object ZmsDatabaseRes extends Resource[ZmsDatabase] {
      override def close(r: ZmsDatabase): Unit = r.close()
    }

    implicit object DbRes extends Resource[DB] {
      override def close(r: DB): Unit = r.close()
    }

    def v11(context: Context): DB => Unit = { implicit db =>
      // migrates ZUsers to AccountData, extracts some info from user specific KeyValue storage

      val SelfUserId = "self_user_id"
      val OtrClientId = "otr-client-id"
      val OtrClientRegState = "otr-client-reg-state"

      val c = db.query("ZUsers", Array("_id"), null, null, null, null, null)
      val ids = Seq.tabulate(c.getCount) { idx =>
        c.moveToPosition(idx)
        c.getString(0)
      }
      c.close()

      val accountData: Map[AccountId, (Option[UserId], Option[ClientId], Option[String])] = ids.map { id =>
        val values = try {
          IoUtils.withResource(new ZmsDatabase(UserId(id), context)) { zmsDb =>
            IoUtils.withResource(zmsDb.dbHelper.getReadableDatabase) { zd =>
              val c = zd.query("KeyValues", Array("key", "value"), s"key IN ('$SelfUserId', '$OtrClientId', '$OtrClientRegState')", null, null, null, null)
              Seq.tabulate(c.getCount) { i => c.moveToPosition(i); c.getString(0) -> c.getString(1) }.toMap
            }
          }
        } catch {
          case _: Throwable => Map.empty[String, String]
        }

        AccountId(id) -> (values.get(SelfUserId).map(UserId), values.get(OtrClientId).map(ClientId(_)), values.get(OtrClientRegState))
      }.toMap

      val moveConvs = new TableMigration(TableDesc("ZUsers", Columns.v12.all), TableDesc("Accounts", Columns.v13.all)) {

        import Columns.{v12 => src, v13 => dst}

        override val bindings: Seq[Binder] = Seq(
          dst.Id := src.Id,
          dst.Email := src.Email,
          dst.Hash := src.Hash,
          dst.EmailVerified := src.EmailVerified,
          dst.Cookie := src.Cookie,
          dst.Phone := src.Phone,
          dst.Token := { _ => None },
          dst.UserId := { c => accountData.get(src.Id(c)).flatMap(_._1) },
          dst.ClientId := { c => accountData.get(src.Id(c)).flatMap(_._2) },
          dst.ClientRegState := { c => accountData.get(src.Id(c)).flatMap(_._3).getOrElse("UNKNOWN") }
        )
      }

      moveConvs.migrate(db)
      db.execSQL("DROP TABLE IF EXISTS ZUsers")
    }
  }

  object Columns {

    object v12 {
      val Id = id[AccountId]('_id, "PRIMARY KEY")
      val Email = opt(emailAddress('email))
      val Hash = text('hash)
      val EmailVerified = bool('verified)
      val PhoneVerified = bool('phone_verified)
      val Cookie = opt(text('cookie))
      val Phone = opt(phoneNumber('phone))

      val all = Seq(Id, Email, Hash, EmailVerified, PhoneVerified, Cookie, Phone)
    }

    object v13 {
      val Id = id[AccountId]('_id, "PRIMARY KEY")
      val Email = opt(emailAddress('email))
      val Hash = text('hash)
      val EmailVerified = bool('verified)
      val Cookie = opt(text('cookie))
      val Phone = opt(phoneNumber('phone))
      val Token = opt(text[Token]('access_token, JsonEncoder.encodeString[Token], JsonDecoder.decode[Token]))
      val UserId = opt(id[UserId]('user_id))
      val ClientId = opt(id[ClientId]('client_id))
      val ClientRegState = text('reg_state)

      val all = Seq(Id, Email, Hash, EmailVerified, Cookie, Phone, Token, UserId, ClientId, ClientRegState)
    }

  }

}
