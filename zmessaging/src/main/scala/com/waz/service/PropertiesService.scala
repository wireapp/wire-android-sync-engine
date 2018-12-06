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

import scala.language.implicitConversions
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.content.{PropertiesStorage, PropertyValue, UserPreferences}
import com.waz.log.ZLog2._
import com.waz.model.{PropertyEvent, ReadReceiptEnabledPropertyEvent, UnknownPropertyEvent}
import com.waz.service.EventScheduler.Stage
import com.waz.service.assets2.AssetStorageImpl.Codec
import com.waz.sync.SyncServiceHandle
import com.waz.utils.RichFuture
import com.waz.utils.events.Signal
import io.circe.{Decoder, Encoder}

import scala.concurrent.Future

trait PropertiesService {
  val eventProcessor: Stage.Atomic

  def updateProperty[T: Encoder: Decoder](key: PropertyKey, value: T): Future[Unit]

  def setReadReceiptsEnabled(enabled: Boolean): Future[Unit]
  def readReceiptsEnabled: Signal[Boolean]
}

class PropertiesServiceImpl(prefs: UserPreferences, syncServiceHandle: SyncServiceHandle, storage: PropertiesStorage) extends PropertiesService {
  import com.waz.threading.Threading.Implicits.Background

  val eventProcessor: Stage.Atomic = EventScheduler.Stage[PropertyEvent]{ (_, events) =>
    RichFuture.traverseSequential(events)(processEvent)
  }

  for {
    readReceipts <- getProperty[Int](PropertyKey.ReadReceiptsEnabled)
    if readReceipts.isEmpty
    _ <- syncServiceHandle.syncProperties()
  } ()

  private def processEvent(event: PropertyEvent): Future[Unit] = {
    event match {
      case ReadReceiptEnabledPropertyEvent(value) =>
        getProperty[Int](PropertyKey.ReadReceiptsEnabled).flatMap {
          case Some(current) if current != value =>
            for {
              _ <- prefs(UserPreferences.ReadReceiptsRemotelyChanged) := true
              _ <- updateProperty(PropertyKey.ReadReceiptsEnabled, value)
            } yield ()
          case _ =>
            Future.successful({})
        }
      case UnknownPropertyEvent(key, _) =>
        verbose(l"Unhandled property event $key")
        Future.successful({})
    }
  }

  def updateProperty[T: Encoder: Decoder](key: PropertyKey, value: T): Future[Unit] = {
    import io.circe.syntax._
    storage.save(PropertyValue(key.str, value.asJson.toString()))
  }

  def getProperty[T: Encoder: Decoder](key: PropertyKey): Future[Option[T]] = {
    import io.circe.parser._

    storage.find(key).map {
      case Some(v) => decode[T](v.value) match {
        case Left(_) => Option.empty[T]
        case Right(x) => Option(x)
      }
      case _ => Option.empty[T]
    }
  }

  def propertySignal[T: Encoder: Decoder](key: PropertyKey): Signal[Option[T]] = {
    import io.circe.parser._

    storage.optSignal(key).map {
      case Some(v) => decode[T](v.value) match {
          case Left(_) => Option.empty[T]
          case Right(x) => Option(x)
      }
      case _ => Option.empty[T]
    }
  }

  def setReadReceiptsEnabled(enabled: Boolean): Future[Unit] = {
    val enabledInt = if (enabled) 1 else 0
    for {
      current <- getProperty[Int](PropertyKey.ReadReceiptsEnabled).map(_.getOrElse(0))
      _ <- if (current != enabledInt) updateProperty(PropertyKey.ReadReceiptsEnabled, enabledInt) else Future.successful({})
      _ <- if (current != enabledInt) syncServiceHandle.postProperty(PropertyKey.ReadReceiptsEnabled, enabledInt) else Future.successful({})
    } yield ()
  }

  def readReceiptsEnabled: Signal[Boolean] = propertySignal[Int](PropertyKey.ReadReceiptsEnabled).map(_.exists(_ > 0))
}

case class PropertyKey(str: String) {
  override def toString: String = str
}

object PropertyKey extends (String => PropertyKey) {
  implicit def toString(key: PropertyKey): String = key.str
  implicit def fromString(str: String): PropertyKey = PropertyKey(str)
  implicit val PropertyKeyCodec: Codec[PropertyKey, String] = Codec.create(_.str, PropertyKey.apply)

  val ReadReceiptsEnabled: PropertyKey = "WIRE_RECEIPT_MODE"
}
