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
package com.waz.service

import android.content.Context
import android.net.Uri
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.media.manager.{MediaManager, MediaManagerListener}
import com.waz.media.manager.config.Configuration
import com.waz.media.manager.context.IntensityLevel
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventContext, SourceSignal}
import com.waz.utils._
import com.waz.zms.R
import org.json.JSONObject

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try

trait MediaManagerService {
  def mediaManager: Option[MediaManager]
}

class DefaultMediaManagerService(context: Context, prefs: PreferenceServiceImpl) extends MediaManagerService {
  import com.waz.service.MediaManagerService._

  private implicit val dispatcher = new SerialDispatchQueue(name = "MediaManagerService")
  private implicit val ev = EventContext.Global

  lazy val isSpeakerOn = new SourceSignal[Boolean](mediaManager map (_.isLoudSpeakerOn))

  lazy val mediaManager = LoggedTry {
    val manager = MediaManager.getInstance(context.getApplicationContext)
    manager.addListener(listener)
    audioConfig.foreach(manager.registerMediaFromConfiguration)
    manager
  } .toOption

  lazy val listener: MediaManagerListener = new MediaManagerListener {
    override def mediaCategoryChanged(convId: String, category: Int): Int = category // we don't need to do anything in here, I guess, and the return value gets ignored anyway

    override def onPlaybackRouteChanged(route: Int): Unit = {
      val pbr = PlaybackRoute.fromAvsIndex(route)
      debug(s"onPlaybackRouteChanged($pbr)")
      isSpeakerOn ! (pbr == PlaybackRoute.Speaker)
    }
  }

  private lazy val prefAll = Try(context.getResources.getString(R.string.pref_sound_value_all)).getOrElse("all")
  private lazy val prefSome = Try(context.getResources.getString(R.string.pref_sound_value_some)).getOrElse("some")
  private lazy val prefNone = Try(context.getResources.getString(R.string.pref_sound_value_none)).getOrElse("none")
  private lazy val soundsPrefKey = Try(context.getResources.getString(R.string.pref_sound_option_key)).getOrElse("PREF_KEY_SOUND") // hardcoded value used in tests

  private lazy val intensityMap = Map(prefAll -> IntensityLevel.FULL, prefSome -> IntensityLevel.SOME, prefNone -> IntensityLevel.NONE)

  private lazy val soundsPref = prefs.uiPreferenceStringSignal(soundsPrefKey)

  soundsPref.signal { value =>
    val intensity = intensityMap.getOrElse(value, IntensityLevel.FULL)
    verbose(s"setting intensity to: $intensity")
    withMedia { _.setIntensity(intensity) }
  }

  lazy val audioConfig =
    LoggedTry(new JSONObject(IoUtils.asString(context.getAssets.open(AudioConfigAsset)))).toOption

  lazy val audioConfigUris =
    audioConfig.map(new Configuration(_).getSoundMap.asScala.mapValues { value =>
      val packageName = context.getPackageName
      Uri.parse(s"android.resource://$packageName/${context.getResources.getIdentifier(value.getPath, "raw", packageName)}")
    }).getOrElse(Map.empty[String, Uri])

  def getSoundUri(name: String): Option[Uri] = audioConfigUris.get(name)

  def setSpeaker(speaker: Boolean) = withMedia { mm => if (speaker) mm.turnLoudSpeakerOn() else mm.turnLoudSpeakerOff() }

  private def withMedia[T](op: MediaManager => T): Future[Option[T]] = mediaManager.mapFuture(m => Future(op(m)))
}

object MediaManagerService {
  val AudioConfigAsset = "android.json"
}
