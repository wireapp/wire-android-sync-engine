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

import android.content.{Context, SharedPreferences}
import com.waz.api._
import com.waz.api.impl.{EmailCredentials, ZMessagingApi}
import com.waz.content.{Database, GlobalDatabase}
import com.waz.model.{MessageContent => _, _}
import com.waz.testutils.Implicits._
import com.waz.threading.Threading
import com.waz.ui.UiModule
import com.waz.znet.{AsyncClient, ClientWrapper, TestClientWrapper}
import org.scalatest.{BeforeAndAfterAll, RobolectricTests, Suite}

import scala.concurrent.{Future, Promise}
import scala.util.Random

class RemoteZms(ui: UiModule) extends ZMessagingApi()(ui) {
  import Threading.Implicits.Background

  onCreate(ui.context)
  onResume()

  def login(email: String, pass: String): Future[Unit] = {
    val p = Promise[Unit]()
    onInit(new InitListener {
      override def onInitialized(user: Self): Unit = {
        login(EmailCredentials(EmailAddress(email), Some(pass)), new LoginListener {
          override def onSuccess(user: Self): Unit = p.trySuccess(())

          override def onFailed(code: Int, message: String, label: String): Unit = p.tryFailure(new Exception(s"login failed: $code, $message, $label"))
        })
      }
    })
    p.future.onComplete(res => println(s"login $email, completed: $res"))
    p.future
  }

  def findConv(id: RConvId): Future[IConversation] = {
    val p = Promise[IConversation]()
    val convs = getConversations
    var listener: UpdateListener = null
    def check() =
      convs.find(_.data.remoteId == id) foreach { conv =>
        convs.removeUpdateListener(listener)
        p.trySuccess(conv)
      }
    listener = new UpdateListener {
      override def updated(): Unit = check()
    }
    convs.addUpdateListener(listener)
    check()
    p.future
  }

  def postMessage(conv: RConvId, msg: MessageContent) = findConv(conv).map { _.sendMessage(msg) }
}

trait RemoteZmsSpec extends RobolectricTests with BeforeAndAfterAll { suite: Suite with ApiSpec =>

  def globalModule(dataTag: String = Random.nextInt().toHexString): GlobalModule =  new GlobalModule(context, testBackend) { global =>
    override lazy val clientWrapper: ClientWrapper = TestClientWrapper
    override lazy val client: AsyncClient = testClient
    override lazy val timeouts: Timeouts = suite.timeouts

    override lazy val storage: Database = new GlobalDatabase(context, dataTag)
    override lazy val metadata: MetaDataService = new MetaDataService(context) {
      override val cryptoBoxDirName: String = "otr_" + dataTag
    }

    override lazy val prefs: PreferenceServiceImpl = new PreferenceServiceImpl(context) {
      override lazy val preferences: SharedPreferences = context.getSharedPreferences("zmessaging_" + dataTag, Context.MODE_PRIVATE)
      override lazy val uiPreferences: SharedPreferences = context.getSharedPreferences("zmessaging_ui_" + dataTag, Context.MODE_PRIVATE)
    }
    override lazy val factory: ZMessagingFactory = new ZMessagingFactory(global) {
      override def baseStorage(accountId: AccountId): StorageModule = new StorageModule(context, accountId, dataTag)
    }
  }

  override protected def beforeAll(): Unit = {
    Threading.AssertsEnabled = false
    super.beforeAll()
  }

  def createRemoteZms(dataTag: String = Random.nextInt().toHexString) = new RemoteZms(new UiModule(new Accounts(globalModule(dataTag))))
}
