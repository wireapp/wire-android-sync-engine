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
package com.waz.service.otr

import java.io._
import java.util.Date

import android.util.Base64
import com.waz.RobolectricUtils
import com.waz.api.Verification
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericMessage.TextMessage
import com.waz.model._
import com.waz.model.otr.{Client, ClientId, SignalingKey}
import com.waz.testutils._
import com.waz.testutils.Implicits._
import com.waz.threading.Threading.Implicits.Background
import com.waz.utils.IoUtils
import com.waz.utils.crypto.AESUtils
import com.waz.utils.events.Signal
import com.wire.cryptobox.CryptoBox
import org.json.JSONObject
import org.robolectric.shadows.ShadowLog
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import com.waz.ZLog.ImplicitTag._
import com.waz.cache.LocalData
import com.waz.model.GenericContent.EncryptionAlgorithm
import com.waz.service.PreferenceServiceImpl

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class OtrServiceSpec extends FeatureSpec with Matchers with OptionValues with BeforeAndAfter with RobolectricTests with RobolectricUtils with ScalaFutures with DefaultPatienceConfig { test =>

  after {
    ShadowLog.stream = null
  }

  feature("Symmetric encryption") {

    scenario("Encrypt and decrypt an image") {
      val key = AESKey()

      def in = getClass.getResourceAsStream("/images/penguin.png")
      val tmp = File.createTempFile("img", ".enc")

      val sha = AESUtils.encrypt(key, in, new FileOutputStream(tmp))

      info(s"key: $key")
      info(s"sha: s $sha")
      info(s"encrypted file: ${tmp.getAbsolutePath}")

      val tmpOut = File.createTempFile("dec", ".png")

      val mac1 = AESUtils.decrypt(key, new FileInputStream(tmp), new FileOutputStream(tmpOut))

      IoUtils.toByteArray(new FileInputStream(tmpOut)).toSeq shouldEqual IoUtils.toByteArray(in).toSeq
      mac1 shouldEqual sha
    }

    scenario("encrypt and decrypt data") {
      val data = Array.fill(100)(Random.nextInt.toByte)
      val key = AESKey()

      val bos = new ByteArrayOutputStream()
      val sha = AESUtils.encrypt(key, new ByteArrayInputStream(data), bos)
      val cipher = bos.toByteArray

      info(s"cipher len: ${cipher.length}")

      com.waz.utils.sha2(cipher) shouldEqual sha.str

      val data1 = AESUtils.decrypt(key, cipher)
      data1.toSeq shouldEqual data.toSeq
    }

    scenario("decrypt gcm") {
      val client = Client(ClientId("12f3ed50e81cc843"), "", signalingKey = Some(SignalingKey(AESKey("btjQSb+hXVsQggMCtl9y3ownUBXiguv\\/g1+X25MtCeY="), "avsJUNtqWCJt8t091LXa1iuhrCx4T3YdvmwSliEmD8Y=")), verified = Verification.VERIFIED)
      val mac = "D883DKeiQc9KEg9iFoRDyVFLzJN0VCnmVzSQF6GtISE="
      val data = "kSTPAie6bOwLDodirEe8P9YWxpsc1pl1OCsEryoDxGmXxPshaob1BZVscYUpSHbgBbuTDt5qL7Q3gHv++GH0Jbw1bRSwBJjQsqHXHOITVO/iX4E2TXPH1yZ5lmrZkxScKt7GPYJXYZRGj1NGV8HCgFI76+A778X5xQM+JCz05T7ObqTCSYUxg46OWiumfqt/NzfiwaDCj/nNYeL596bM7gFFCD1qLxO54fWSvzkgctOXe0mqJl8VAJlY0iF4XURSSfhPsZm/GJxPpV7/JaLiR/jcA+j2Je84Yc/tLhUgNZdZCF1WU/9RKeKax+rO5V+n27SYfWn8KWuADfUW/eyBDCUf3mdrc4WNGuPcQAq9HbFm7agJJxXAYHH21NQZHTk4SmO6KATfKUqujE20xyJVbz7Mzf5MfPQrid5CvdZnlgXpaonEJigckkBXeFhbQokGcKXdLOI3zF0BOez5VDFJ1H3vHWwcSy2Yf/vSX9Lk2JIyC1s+1nSCVgFnOai/hBDD9RzUvQrpiEaJWQGhA28UkOJEZKGRqtiXY7az3L12AhL7++xc13cl3O81cRHXtU9EC9tREVseSW7BMwrPgxF2Cx3RLA+M0uHUDDEPbEACnwGt1R8lRekawC5d3rjA7SHmQolV18t/7YvmbQBUggf46mw1rT/vIyul8NPYpBUM4lqPGT30LTj6+6DsDv6viVrsEeuB9UfpZfFLFWAN5AXeJNpYGf7DRdsCf3TaHrTvz7+/8AV01dC1WNnX4TqoIkUMSct0DLp2WsO9DqEeJYgxStw0zxAO5EAoQ6FgJ2h9FltrQs6xvUbgHb7XzN7/aSOkMiDu75yh8CqnXX092R5htOa+bTbeeEmStpgpM5MynOSmY1KKf9U46/OYqxu72t2PTqPQ7+xsYmyOtrfv4cGM7WOBFAh0+MAkDls/59+MqtIP+1mh2Fw+hHJQct4QmsgI"
      val bytes = Base64.decode(data, 0)
      info(s"bytes len: ${bytes.length}")

      OtrService.hmacSha256(client.signalingKey.get, bytes).toSeq shouldEqual Base64.decode(mac, 0)

      val plain = AESUtils.decrypt(client.signalingKey.get.encKey, bytes)
      info(new String(plain, "utf8"))
      val json = new JSONObject(new String(plain, "utf8"))
      json.has("data") shouldEqual true
      json.has("user") shouldEqual true
      json.getString("user") shouldEqual "18ffb782-2a26-4f0b-bcf8-150575b7a6ec"
    }
  }

  feature("Client registration") {

    var syncCheckRequests = Seq.empty[FiniteDuration]

    lazy val zms = new MockZMessaging() {
      override lazy val otrClientsService: OtrClientsService = new OtrClientsService(selfUserId, Signal.const(Some(clientId)), otrClient, kvStorage, otrClientsStorage, sync, lifecycle, verificationUpdater) {
        override def requestSyncIfNeeded(retryInterval: Timeout): Future[Unit] =
          Future successful { syncCheckRequests = syncCheckRequests :+ retryInterval }
      }
    }

    scenario("init zms") {
      zms.lifecycle.acquireUi()
      withDelay(zms.lifecycle.uiActive.currentValue shouldEqual Some(true))
    }

    scenario("Request self clients sync when session is created from message") {
      zms.otrService.sessions.onCreateFromMessage ! "1"
      withDelay {
        syncCheckRequests should contain(1.hour)
      }
    }

    scenario("Throttle requests") {
      val count = syncCheckRequests.length
      zms.otrService.sessions.onCreateFromMessage ! "1"
      zms.otrService.sessions.onCreateFromMessage ! "2"
      zms.otrService.sessions.onCreateFromMessage ! "3"
      zms.otrService.sessions.onCreateFromMessage ! "4"

      awaitUi(200.millis)
      syncCheckRequests should have size count
    }
  }

  feature("Integration") {

    class TestClient(val self: UserData, remote: UserData) extends MockZMessaging(selfUserId = self.id) {

      lazy val (selfClient, _, prekeys) = cryptoBox.createClient(clientId).futureValue.get
      val conv = ConversationData(ConvId(remote.id.str), RConvId(remote.id.str), None, remote.id, ConversationType.OneToOne)

      usersStorage.addOrOverwrite(self)
      usersStorage.addOrOverwrite(remote)

      convsStorage.insert(conv)
      membersStorage.add(conv.id, self.id, remote.id)
    }

    lazy val user1 = UserData("user1")
    lazy val user2 = UserData("user2")

    lazy val client1 = new TestClient(user1, user2)
    lazy val client2 = new TestClient(user2, user1)

    lazy val conv1 = client1.conv
    lazy val conv2 = client2.conv

    lazy val (c1, pre1) = (client1.selfClient, client1.prekeys)
    lazy val (c2, pre2) = (client2.selfClient, client2.prekeys)

    def send(m: GenericMessage, from: TestClient, to: TestClient) = {
      val content = Await.result(from.otrService.encryptMessage(from.conv.id, m), 5.seconds).content(to.self.id)(to.selfClient.id)
      val event = OtrMessageEvent(to.conv.remoteId, new Date, from.self.id, from.selfClient.id, to.selfClient.id, content)
      val res = Await.result(to.otrService.decryptOtrEvent(event), 5.seconds)
      res shouldBe 'right
      res.right.get shouldEqual m
      res
    }

    def sendFrom1(m: GenericMessage = TextMessage("test", Map.empty)) = send(m, client1, client2)

    def sendFrom2(m: GenericMessage = TextMessage("test", Map.empty)) = send(m, client2, client1)

    scenario("init") {
      client1
      client2

      awaitUi(1.second)

      Await.result(Future.sequence(Seq(
        client1.otrClientsService.updateSelfClients(Seq(c1)),
        client2.otrClientsService.updateSelfClients(Seq(c2)),
        client1.otrClientsService.updateUserClients(user2.id, Seq(c2)),
        client2.otrClientsService.updateUserClients(user1.id, Seq(c1)),
        client1.otrService.sessions.getOrCreateSession(OtrService.sessionId(user2.id, c2.id), pre2.head),
        client2.otrService.sessions.getOrCreateSession(OtrService.sessionId(user1.id, c1.id), pre1.head)
      )), 5.seconds)

      client1.otrClientsService.getSelfClient.futureValue shouldBe 'defined
      client1.otrService.sessions.getSession(OtrService.sessionId(user2.id, c2.id)).futureValue shouldBe 'defined

      info(s"user1: ${user1.id}, c1: $c1")
      info(s"user1: ${user2.id}, c1: $c2")
    }

    scenario("init sessions by exchanging one message") {
      val m = TextMessage("test", Map.empty)
      sendFrom1(m).right.get shouldEqual m
    }

    scenario("don't generate content for self client when sending a message") {
      val content = client1.otrService.encryptMessage(conv1.id, TextMessage("test", Map.empty)).futureValue.content
      content should have size 1
      content.keySet should contain(user2.id)
    }

    scenario(s"send more text messages") {
      sendFrom2(TextMessage("test1", Map.empty))
      sendFrom1(TextMessage("test2", Map.empty))
      sendFrom2(TextMessage("test3", Map.empty))
      sendFrom1(TextMessage("test4", Map.empty))
    }

    scenario(s"don't generate message to yourself") {
      val content = Await.result(client1.otrService.encryptMessage(conv1.id, TextMessage("test", Map.empty)), 5.seconds).content
      content.keys should have size 1
    }

    scenario(s"report specific error on REMOTE_IDENTITY_CHANGED") {
      val file = new File(new File("target/cryptobox-jni/temp"), Random.nextInt.toHexString)
      file.mkdirs() shouldEqual true
      val box = CryptoBox.open(file.getAbsolutePath)
      val session = box.initSessionFromPreKey("session1", client2.prekeys(1))

      val msg = TextMessage("test5", Map.empty)
      val event = OtrMessageEvent(client2.conv.remoteId, new Date, client1.self.id, client1.selfClient.id, client2.selfClient.id, session.encrypt(GenericMessage.toByteArray(msg)))

      Await.result(client2.otrService.decryptOtrEvent(event), 5.seconds) shouldEqual Left(IdentityChangedError(client1.self.id, client1.selfClient.id))
    }
  }

  feature("assets encryption") {

    scenario("encrypt an asset using CBC") {

      lazy val zms = new MockZMessaging() {
        override lazy val prefs = new PreferenceServiceImpl(context) {
          override def v31AssetsEnabled = false
        }
      }

      val key = AESKey()
      val data = Array.fill(100)(Random.nextInt.toByte)

      val encryptedF = zms.otrService.encryptAssetData(key, LocalData(data))

      ScalaFutures.whenReady(encryptedF){
        case (sha, encrypted, encryptionAlg) =>
          encryptionAlg shouldEqual(EncryptionAlgorithm.AES_CBC)
          val decrypted = zms.otrService.decryptAssetData(AssetId(), Some(key), Some(sha), encrypted.byteArray, None)
          decrypted.getOrElse(fail(s"Decrypted ($key, $data) as None")) shouldEqual(data)
      }

    }
  }
}
