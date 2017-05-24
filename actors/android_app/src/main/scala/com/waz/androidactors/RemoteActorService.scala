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
package com.waz.androidactors

import java.io.File

import akka.actor.ActorDSL._
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorDSL, ActorRef, ActorSystem, Terminated}
import akka.pattern.gracefulStop
import android.app.{Notification, PendingIntent}
import android.content.{Context, Intent}
import android.net.wifi.WifiManager
import com.typesafe.config.ConfigFactory
import com.waz.ZLog._
import com.waz.content.GlobalPreferences
import com.waz.content.Preferences.PrefKey
import com.waz.provision.RemoteProcessActor
import com.waz.service.BackendConfig
import com.waz.threading.Threading
import com.waz.utils.{LoggedTry, Serialized}
import com.waz.utils.events.{ServiceEventContext, Signal}
import com.waz.zms.FutureService
import com.waz.znet.TestClientWrapper

import scala.concurrent.Future
import scala.concurrent.duration._

class RemoteActorService(context: Context) {
  import Threading.Implicits.Background
  import android.os.Build._
  import com.waz.utils.events.EventContext.Implicits.global
  private implicit val tag: LogTag = logTagFor[RemoteActorService]
  val prefs = GlobalPreferences(context)

  val background  = prefs.preference(PrefKey[Boolean]("background", false))
  val name        = prefs.preference(PrefKey[String]("name", s"$MANUFACTURER $MODEL"))
  val backendPref = prefs.preference(PrefKey[String]("env", BackendConfig.StagingBackend.environment))

  val backend = backendPref.signal map BackendConfig.byName

  lazy val mWifiManager = context.getSystemService(Context.WIFI_SERVICE).asInstanceOf[WifiManager]

  implicit lazy val actorSystem = LoggedTry {
    def intToIP(i: Int) = (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF)
    val info = mWifiManager.getConnectionInfo
    val ip = intToIP(info.getIpAddress)
    debug(s"wifi info: $info, ip: $ip")
    val config = ConfigFactory.parseString(
      s"""
         |akka.remote.netty {
         |  tcp.hostname = "$ip"
         |  ssl.hostname = "$ip"
         |  udp.hostname = "$ip"
         |}
       """.stripMargin).withFallback(ConfigFactory.load("remote_actor"))
    ActorSystem.create("RemoteProcessSystem", config)
  } .get

  private val currentActor = Signal[ActorRef]()
  private val actorConfig = Signal(name.signal, backend)

  ActorDSL.actor(new Act {
    currentActor { context.watch(_) }

    become {
      case Terminated(ref) =>
        if (currentActor.currentValue.contains(ref))
          actorConfig.currentValue foreach {
            case (name, backend) => updateActor(name, backend)
          }
    }
  })

  actorConfig.throttle(1.second) {
    case (name, backend) => updateActor(name, backend)
  }

  private def updateActor(name: String, backend: BackendConfig) = Serialized.future(this, "updateRemoteActor") {
    println(s"updateActor($name, $backend)")
    currentActor.currentValue.fold(Future successful true) { act => gracefulStop(act, 5.seconds) } map { stopped =>
      println(s"prev actor stopped: $stopped, creating: ($name, $backend)")
      val dbs = new File(context.getFilesDir.getParentFile, "databases")
      dbs.listFiles() foreach { db =>
        println(s"deleting db: $db, res:" + db.delete())
      }
      currentActor ! actorSystem.actorOf(RemoteProcessActor.props(context, name, None, backend, TestClientWrapper()), name.replaceAll("\\s+", "_"))
    }
  }

  val actorState = {
    val signal = Signal[RemoteProcessActor.State]()

    lazy val listener = ActorDSL.actor(new Act {
      become {
        case CurrentState(ref, state: RemoteProcessActor.State) => signal ! state
        case Transition(ref, oldState, newState: RemoteProcessActor.State) => signal ! newState
      }
    })

    currentActor flatMap { act =>
      act.tell(SubscribeTransitionCallBack(listener), listener)
      signal
    }
  }

  background.signal { if(_) context.startService(BackgroundService.intent(context)) }
}

class BackgroundService extends FutureService with ServiceEventContext {
  val NotificationId = 1

  lazy val service = getApplication.asInstanceOf[ActorsApplication].remoteActor
  lazy val notification =
    new Notification.Builder(getApplicationContext)
      .setSmallIcon(android.R.drawable.ic_dialog_alert)
      .setContentText("Remote ZMS actor running")
      .setContentIntent(PendingIntent.getActivity(getApplicationContext, 0, new Intent(getApplicationContext, classOf[MainActivity]), 0))
      .build()

  override def onCreate(): Unit = {
    super.onCreate()

    service.background.signal.on(Threading.Ui) {
      case true => startForeground(NotificationId, notification)
      case _ => stopForeground(true)
    }
  }

  override protected def onIntent(intent: Intent, id: Int): Future[Any] = {
    service.background.signal.filter(_ == false).head
  }
}

object BackgroundService {
  def intent(implicit context: Context) = new Intent(context, classOf[BackgroundService])
}
