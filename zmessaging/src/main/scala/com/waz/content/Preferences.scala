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
package com.waz.content

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.{Context, SharedPreferences}
import com.waz.ZLog._
import com.waz.content.Preferences.Preference.PrefCodec
import com.waz.content.Preferences.{PrefKey, Preference}
import com.waz.media.manager.context.IntensityLevel
import com.waz.model.KeyValueData.KeyValueDataDao
import com.waz.model._
import com.waz.sync.client.OAuth2Client.RefreshToken
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.utils.{CachedStorageImpl, Serialized, TrimmingLruCache, returning}
import com.waz.znet.AuthenticationManager.{Cookie, Token}
import org.json.JSONObject
import org.threeten.bp.{Duration, Instant}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

trait Preferences {

  implicit protected val dispatcher: ExecutionContext
  implicit protected val logTag: LogTag

  //protected for test prefs
  protected var cache = Map.empty[PrefKey[_], Preference[_]]

  final def preference[A: PrefCodec](key: PrefKey[A]): Preference[A] =
    cache.getOrElse(key, returning(buildPreference(key))(cache += key -> _)).asInstanceOf[Preference[A]]

  protected def buildPreference[A: PrefCodec](key: PrefKey[A]): Preference[A] =
    Preference[A](this, key)

  protected def getValue[A: PrefCodec](key: PrefKey[A]): Future[A]
  def setValue[A: PrefCodec](key: PrefKey[A], value: A): Future[Unit]
}

object Preferences {

  case class Preference[A: PrefCodec](private val prefs: Preferences, key: PrefKey[A]) {

    import Threading.Implicits.Background

    def apply():          Future[A]    = prefs.getValue(key).map { v => verbose(s"Getting $key: $v")(prefs.logTag); v }
    def update(value: A): Future[Unit] = {
      verbose(s"Setting $key: $value")(prefs.logTag)
      prefs.setValue(key, value).map { _ => signal.publish(value, Threading.Background)}
    }

    def :=(value: A):      Future[Unit] = update(value)
    def mutate(f: A => A): Future[Unit] = apply().flatMap(cur => update(f(cur)))

    lazy val signal: SourceSignal[A] = {
      returning(Signal[A]()) { s =>
        apply().onSuccess { case v => s.publish(v, Threading.Background) }
      }
    }
  }

  object Preference {

    trait PrefCodec[A] {
      def encode(v: A): String
      def decode(str: String): A
      val default: A
    }

    //TODO maybe we can use JSON codecs at some point...
    object PrefCodec {
      def apply[A](enc: A => String, dec: String => A, defaultVal: A): PrefCodec[A] = new PrefCodec[A] {
        override def encode(v: A): String = enc(v)
        override def decode(str: String): A = dec(str)
        override val default = defaultVal
      }

      implicit lazy val StrCodec     = apply[String] (identity,       identity,                   "")
      implicit lazy val IntCodec     = apply[Int]    (String.valueOf, java.lang.Integer.parseInt, 0)
      implicit lazy val LongCodec    = apply[Long]   (String.valueOf, java.lang.Long.parseLong,   0)
      implicit lazy val BooleanCodec = apply[Boolean](String.valueOf, java.lang.Boolean.parseBoolean, false)

      implicit def idCodec[A: Id]: PrefCodec[A] = apply[A](implicitly[Id[A]].encode, implicitly[Id[A]].decode, implicitly[Id[A]].empty)
      implicit def optCodec[A: PrefCodec]: PrefCodec[Option[A]] = apply[Option[A]](_.fold("")(implicitly[PrefCodec[A]].encode), { str => if (str == "") None else Some(implicitly[PrefCodec[A]].decode(str)) }, None)

      implicit lazy val InstantCodec = apply[Instant](d => String.valueOf(d.toEpochMilli), s => Instant.ofEpochMilli(java.lang.Long.parseLong(s)), Instant.EPOCH)
      implicit lazy val DurationCodec = apply[Duration](d => String.valueOf(d.toMillis), s => Duration.ofMillis(java.lang.Long.parseLong(s)), Duration.ZERO)

      implicit lazy val AuthTokenCodec = apply[Option[Token]] (
        { t => optCodec[String].encode(t map Token.Encoder.apply map (_.toString)) },
        { s => optCodec[String].decode(s) map (new JSONObject(_)) map (Token.Decoder.apply(_)) },
        None)
      implicit lazy val AuthCookieCodec = apply[Cookie] (_.str, Cookie, Cookie(""))

      implicit lazy val SpotifyRefreshTokenCodec = apply[RefreshToken](_.str, RefreshToken, RefreshToken(""))

      //TODO use an enumcodec somehow
      implicit lazy val IntensityLevelCodec = apply[IntensityLevel](_.toString, IntensityLevel.valueOf, IntensityLevel.FULL)
    }
  }

  case class PrefKey[A: PrefCodec](str: String, customDefault: A = null.asInstanceOf[A]) {
    val default = Option(customDefault).getOrElse(implicitly[PrefCodec[A]].default)

    override def toString = s"$str (def: $default)"
  }
}


/**
  * Global preference based on Android SharedPreferences. Note, here we need to save preferences to the correct primitive
  * type in SharedPreferences, as the Android system uses these types by default (e.g., a CheckBoxPreference defined in XML
  * will store a boolean preference in the shared prefs document
  */
class GlobalPreferences(context: Context, prefs: SharedPreferences) extends Preferences {

  override protected implicit val dispatcher = new SerialDispatchQueue(name = "GlobalPreferencesDispatcher")
  override protected implicit val logTag = logTagFor[GlobalPreferences]

  def v31AssetsEnabled = false

  //TODO eventually remove
  private def migrate() = Serialized(GlobalPreferences.MigrationKey)(dispatcher {
    val oldPrefFiles = Seq("zmessaging", "com.waz.zclient.user.preferences")
      .map(name => Option(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
      .collect { case Some(pref) => pref }

    val editor = prefs.edit()

    oldPrefFiles
      .map(_.getAll.asScala)
      .foldLeft(Map.empty[String, Any]){ case (cur, i) => cur ++ i }
      .foreach {
        case (key, value) =>
          value match {
            case v: String  => debug(s"Migrating String:  $key: $value"); editor.putString(key, v)
            case v: Boolean => debug(s"Migrating Boolean: $key: $value"); editor.putBoolean(key, v)
            case v: Int     => debug(s"Migrating Int:     $key: $value"); editor.putInt(key, v)
            case v: Long    => debug(s"Migrating Long:    $key: $value"); editor.putLong(key, v)
            case v => warn(s"Preference $key: $v has unexpected type. Leaving out of migration")
          }
      }

    if (editor.commit()) {
      oldPrefFiles.foreach { pref =>
        pref.edit().clear().commit()
      }
    }
  })

  //TODO would be nice to hide this, but for now it's fine
  def getFromPref[A: PrefCodec](key: PrefKey[A]) = {
    val codec = implicitly[PrefCodec[A]]
    import PrefCodec._
    (codec match {
      case IntCodec     => prefs.getInt    (key.str, key.default.asInstanceOf[Int])
      case BooleanCodec => prefs.getBoolean(key.str, key.default.asInstanceOf[Boolean])
      case LongCodec    => prefs.getLong   (key.str, key.default.asInstanceOf[Long])
      case _            => Option(prefs.getString (key.str, null)).map(codec.decode).getOrElse(key.default)
    }).asInstanceOf[A]
  }

  override protected def buildPreference[A: PrefCodec](key: PrefKey[A]) =
    new Preference[A](this, key) {

      //No need to update the signal. The SharedPreferences Listener will do this for us.
      override def update(value: A) = {
        verbose(s"Setting $key: $value")
        setValue[A](key, value)
      }

      private def load = getFromPref(key)

      override lazy val signal = new SourceSignal[A](Some(load)) {

        private val listener = new OnSharedPreferenceChangeListener {
          override def onSharedPreferenceChanged(sharedPreferences: SharedPreferences, k: String): Unit =
            if (key.str == k) set(Some(load), Some(Threading.Background))
        }

        override def onWire(): Unit = {
          prefs.registerOnSharedPreferenceChangeListener(listener)
          value = Some(load)
        }

        override def onUnwire(): Unit = prefs.unregisterOnSharedPreferenceChangeListener(listener)
      }
    }

  override protected def getValue[A: PrefCodec](key: PrefKey[A]): Future[A] =
    dispatcher(getFromPref[A](key))

  override def setValue[A: PrefCodec](key: PrefKey[A], value: A): Future[Unit] =
    dispatcher {
      import PrefCodec._
      val codec = implicitly[PrefCodec[A]]
      val editor = prefs.edit()
      codec match {
        case IntCodec     => editor.putInt    (key.str, value.asInstanceOf[Int])
        case BooleanCodec => editor.putBoolean(key.str, value.asInstanceOf[Boolean])
        case LongCodec    => editor.putLong   (key.str, value.asInstanceOf[Long])
        case _            => editor.putString (key.str, codec.encode(value))
      }
      editor.apply()
    }
}

/**
  * Per-user preference storage in user db.
  */
class UserPreferences(context: Context, storage: ZmsDatabase) extends CachedStorageImpl[String, KeyValueData](new TrimmingLruCache(context, Fixed(128)), storage)(KeyValueDataDao, "KeyValueStorage_Cached") with Preferences {

  override protected implicit val dispatcher = Threading.Background
  override protected implicit val logTag = logTagFor[UserPreferences]

  override protected def getValue[A: PrefCodec](key: PrefKey[A]) = {
    get(key.str).map(_.map(_.value)).map(_.map(implicitly[PrefCodec[A]].decode).getOrElse(key.default))
  }

  override def setValue[A: PrefCodec](key: PrefKey[A], value: A) =
    put(key.str, KeyValueData(key.str, implicitly[PrefCodec[A]].encode(value))).map(_ => {})

  def migrate(gPrefs: GlobalPreferences) = Serialized.future(GlobalPreferences.MigrationKey) {
    for {
      _ <- migrateFromGlobal(gPrefs)
      _ <- migrateToGlobal(gPrefs)
    } yield ()
  }

  def migrateToGlobal(gPrefs: GlobalPreferences)(implicit ec: ExecutionContext) = {
    list().map(_.map(kvd => kvd.key -> kvd.value).toMap).flatMap { currentPrefs =>
      currentPrefs.get("analytics_enabled").map(PrefCodec.BooleanCodec.decode).fold {
        Future.successful(())
      } {
        case false =>
          gPrefs.setValue(GlobalPreferences.AnalyticsEnabled, false).map(_ => remove("analytics_enabled"))
        case _ =>
          remove("analytics_enabled")
      }
    }
  }

  //TODO eventually remove
  def migrateFromGlobal(gPrefs: GlobalPreferences)(implicit ec: ExecutionContext) = {
    list().map(_.map(_.key)).flatMap { currentPrefs =>
      verbose(s"migrating global prefs to user prefs: $currentPrefs")
      import UserPreferences._

      val shareContacts = if (!currentPrefs.contains(ShareContacts.str))    setValue(ShareContacts,    gPrefs.getFromPref(GlobalPreferences._ShareContacts))    else Future.successful({})
      val darKTheme     = if (!currentPrefs.contains(DarkTheme.str))        setValue(DarkTheme,        gPrefs.getFromPref(GlobalPreferences._DarkTheme))        else Future.successful({})
      val sounds        =
        if (!currentPrefs.contains(Sounds.str)) {
          import IntensityLevel._
          val level = gPrefs.getFromPref(GlobalPreferences._SoundsPrefKey) match {
            case "all" => FULL
            case "some" => SOME
            case "none" => NONE
            case _ => FULL
          }
          setValue(Sounds, level)
        }
        else Future.successful({})
      val wifiDownload =
        if (!currentPrefs.contains(DownloadImagesAlways.str)) {
          val enabled = gPrefs.getFromPref(GlobalPreferences._DownloadImages) match {
            case "always" => false
            case "wifi" => true
          }
          setValue(DownloadImagesAlways, enabled)
        }
        else Future.successful({})

      for {
        _ <- shareContacts
        _ <- darKTheme
        _ <- sounds
        _ <- wifiDownload
      } yield {}
    }
  }

}

object GlobalPreferences {

  val MigrationKey = "PreferenceMigration"

  def apply(context: Context): GlobalPreferences = {
    returning(new GlobalPreferences(context, context.getSharedPreferences("com.wire.preferences", Context.MODE_PRIVATE)))(_.migrate())
  }

  lazy val CurrentAccountPref = PrefKey[Option[AccountId]]("CurrentUserPref")
  lazy val FirstTimeWithTeams = PrefKey[Boolean]("first_time_with_teams", customDefault = true)

  lazy val BackendDrift       = PrefKey[Duration]("backend_drift")

  //TODO think of a nicer way of ensuring that these key values are used in UI - right now, we need to manually check they're correct
  lazy val AutoAnswerCallPrefKey      = PrefKey[Boolean]("PREF_KEY_AUTO_ANSWER_ENABLED")
  lazy val V31AssetsEnabledKey        = PrefKey[Boolean]("PREF_V31_ASSETS_ENABLED")
  lazy val WsForegroundKey            = PrefKey[Boolean]("PREF_KEY_WS_FOREGROUND_SERVICE_ENABLED")

  lazy val PushEnabledKey             = PrefKey[Boolean]("PUSH_ENABLED", customDefault = true)
  lazy val PushToken                  = PrefKey[Option[PushToken]]("PUSH_TOKEN")

  lazy val LastUpToDateSyncTime       = PrefKey[Long]   ("LastUpToDateSync")
  lazy val LastCheckedVersion         = PrefKey[Int]    ("UpToDateVersion")
  lazy val VersionUpToDate            = PrefKey[Boolean]("UpToDate", customDefault = true)

  lazy val LastCacheCleanup           = PrefKey[Long]("LastCacheCleanup")

  lazy val GPSErrorDialogShowCount    = PrefKey[Int]("PREF_PLAY_SERVICES_ERROR_SHOW_COUNT")

  lazy val ResetPushToken             = PrefKey[Boolean]("RESET_PUSH_TOKEN", customDefault = true)
  lazy val AnalyticsEnabled           = PrefKey[Boolean]("PREF_KEY_PRIVACY_ANALYTICS_ENABLED", customDefault = true)

  //DEPRECATED!!! Use the UserPreferences instead!!
  lazy val _ShareContacts              = PrefKey[Boolean]("PREF_KEY_PRIVACY_CONTACTS")
  lazy val _DarkTheme                  = PrefKey[Boolean]("DarkTheme")
  lazy val _SoundsPrefKey              = PrefKey[String] ("PREF_KEY_SOUND")
  lazy val _DownloadImages             = PrefKey[String]("zms_pref_image_download")

}

object UserPreferences {

  def apply(context: Context, storage: ZmsDatabase, globalPreferences: GlobalPreferences) =
    returning(new UserPreferences(context, storage))(_.migrate(globalPreferences))

  lazy val ShareContacts            = PrefKey[Boolean]       ("share_contacts")
  lazy val DarkTheme                = PrefKey[Boolean]       ("dark_theme")
  lazy val Sounds                   = PrefKey[IntensityLevel]("sounds")

  lazy val DownloadImagesAlways     = PrefKey[Boolean]       ("download_images_always", customDefault = true)

  lazy val LastSlowSyncTimeKey     = PrefKey[Option[Long]]        ("last_slow_sync_time")
  lazy val SelectedConvId          = PrefKey[Option[ConvId]]      ("selected_conv_id")
  lazy val SpotifyRefreshToken     = PrefKey[Option[RefreshToken]]("spotify_refresh_token")
  lazy val ShouldSyncConversations = PrefKey[Boolean]             ("should_sync_conversations", customDefault = true)
  lazy val ShouldSyncInitial       = PrefKey[Boolean]             ("should_sync_teams", customDefault = true) // TODO: try to change the name to should_sync_initial

  lazy val LastNotificationClearTime = PrefKey[Instant]    ("last_ui_visible_time")
  lazy val OtrLastPrekey             = PrefKey[Int]        ("otr_last_prekey_id")
  lazy val ClientRegVersion          = PrefKey[Int]        ("otr_client_reg_version")
  lazy val LastStableNotification    = PrefKey[Option[Uid]]("last_notification_id")

  lazy val LastSelfClientsSyncRequestedTime = PrefKey[Long]("last_self_clients_sync_requested")

  lazy val LastReceivedConvEvent     = PrefKey[Instant]("last_received_conv_event_time")
  lazy val LastFetchedConvEvent      = PrefKey[Instant]("last_fetched_conv_event_time", customDefault = Instant.ofEpochMilli(1))
  lazy val LastFetchedConvEventLocal = PrefKey[Instant]("last_fetched_local_time")
  lazy val GcmRegistrationTime       = PrefKey[Instant]("gcm_registration_time")
  lazy val GcmRegistrationRetry      = PrefKey[Int]    ("gcm_registration_retry_count")

  lazy val AddressBookVersion         = PrefKey[Option[Int]]    ("address_book_version_of_last_upload")
  lazy val AddressBookLastUpload      = PrefKey[Option[Instant]]("address_book_last_upload_time")

  lazy val RingTone = PrefKey[String]("ringtone_key")
  lazy val TextTone = PrefKey[String]("text_key")
  lazy val PingTone = PrefKey[String]("ping_key")

  lazy val VBREnabled        = PrefKey[Boolean]("variable_bit_rate_enabled", customDefault = true)
  lazy val VibrateEnabled    = PrefKey[Boolean]("vibrate_enabled")
  lazy val SendButtonEnabled = PrefKey[Boolean]("send_button_enabled", customDefault = true)

}
