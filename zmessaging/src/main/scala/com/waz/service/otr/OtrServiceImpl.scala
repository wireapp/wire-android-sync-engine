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
import javax.crypto.Mac

import com.waz.HockeyApp
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.cache.{CacheService, LocalData}
import com.waz.content.{GlobalPreferences, MembersStorageImpl, OtrClientsStorage}
import com.waz.model.GenericContent.ClientAction.SessionReset
import com.waz.model.GenericContent._
import com.waz.model._
import com.waz.model.otr._
import com.waz.service._
import com.waz.service.conversation.ConversationsContentUpdaterImpl
import com.waz.service.push.PushServiceSignals
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.OtrClient
import com.waz.sync.client.OtrClient.EncryptedContent
import com.waz.threading.Threading
import com.waz.utils.crypto.AESUtils
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.{LoggedTry, _}
import com.wire.cryptobox.CryptoException
import org.json.JSONObject

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.Future.sequence
import scala.concurrent.duration._


trait OtrService {
  def decryptCloudMessage(data: Array[Byte], mac: Array[Byte]): Future[Option[JSONObject]]
  def decryptAssetData(assetId: AssetId, otrKey: Option[AESKey], sha: Option[Sha256], data: Option[Array[Byte]], encryption: Option[EncryptionAlgorithm]): Option[Array[Byte]]
}

class OtrServiceImpl(selfUserId: UserId, clientId: ClientId, val clients: OtrClientsService, push: PushServiceSignals,
                 cryptoBox: CryptoBoxService, members: MembersStorageImpl, convs: ConversationsContentUpdaterImpl,
                 sync: SyncServiceHandle, cache: CacheService, metadata: MetaDataService, clientsStorage : OtrClientsStorage,
                 prefs: GlobalPreferences) extends OtrService {
  import EventContext.Implicits.global
  import OtrService._
  import Threading.Implicits.Background

  push.onSlowSyncNeeded { _ => push.afterProcessing { sync.syncSelfClients() } }

  lazy val sessions = returning(cryptoBox.sessions) { sessions =>
    // request self clients sync to update prekeys on backend
    // we've just created a session from message, this means that some user had to obtain our prekey from backend (so we can upload it)
    // using signal and sync interval parameter to limit requests to one an hour
    Signal.wrap(sessions.onCreateFromMessage).throttle(15.seconds) { _ => clients.requestSyncIfNeeded(1.hour) }
  }

  def eventTransformer(events: Vector[Event]): Future[Vector[Event]] = {
    @tailrec def transform(es: Vector[Event], accu: Vector[Future[Vector[Event]]]): Vector[Future[Vector[Event]]] =
      if (es.isEmpty) accu
      else {
        val ph = isOtrEvent(es.head)
        val (batch, remaining) = es.span(isOtrEvent(_) == ph)
        val batched = if (ph) collectEvents(clientId, batch) else Future.successful(batch)
        transform(remaining, accu :+ batched)
      }
    sequence(transform(events, Vector.empty)).map(_.flatten)
  }

  private lazy val isOtrEvent: Event => Boolean = PartialFunction.cond(_) { case _: OtrEvent => true }

  private def collectEvents(clientId: ClientId, events: Vector[Event]): Future[Vector[Event]] =
    Future.traverse(events) {
      case ev @ OtrMessageEvent(conv, time, from, sender, `clientId`, data, extData) =>
        decryptOtrEvent(ev) map {
          case Left(Duplicate) => None
          case Left(error) => Some(OtrErrorEvent(conv, time, from, error))
          case Right(GenericMessage(msgId, External(key, sha))) =>
            decodeExternal(key, Some(sha), extData) match {
              case None =>
                error(s"External message could not be decoded External($key, $sha), data: $extData")
                Some(OtrErrorEvent(conv, time, from, DecryptionError("symmetric decryption failed", from, sender)))
              case Some(GenericMessage(_, Calling(content))) =>
                Some(CallMessageEvent(conv, time, from, sender, content)) //call messages need sender client id
              case Some(extMsg) =>
                Some(GenericMessageEvent(conv, time, from, extMsg).withLocalTime(ev.localTime))
            }
          case Right(GenericMessage(mId, SessionReset)) if metadata.internalBuild => // display session reset notifications in internal build
             Some(GenericMessageEvent(conv, time, from, GenericMessage(mId, Text("System msg: session reset", Map.empty, Nil))))
          case Right(GenericMessage(mId, SessionReset)) => None // ignore session reset notifications
          case Right(GenericMessage(mId, Calling(content))) =>
            Some(CallMessageEvent(conv, time, from, sender, content)) //call messages need sender client id
          case Right(msg) =>
            Some(GenericMessageEvent(conv, time, from, msg).withLocalTime(ev.localTime))
        }

      case ev @ OtrAssetEvent(conv, time, from, sender, `clientId`, dataId, _, data) =>
        decryptOtrEvent(ev) map {
          case Left(Duplicate) => None
          case Left(error) => Some(OtrErrorEvent(conv, time, from, error))
          case Right(msg) => Some(GenericAssetEvent(conv, time, from, msg, dataId, data).withLocalTime(ev.localTime))
        }
      case ev: OtrEvent if ev.recipient != clientId =>
        verbose(s"Skipping otr event not intended for us: $ev")
        Future successful None
      case ev =>
        error(s"Unhandled OtrEvent: $ev")
        Future successful None
    } map (_.flatten)

  private def decodeExternal(key: AESKey, sha: Option[Sha256], extData: Option[Array[Byte]]) =
    for {
      data  <- extData if sha.forall(_.matches(data))
      plain <- LoggedTry(AESUtils.decrypt(key, data)).toOption
      msg  <- LoggedTry(GenericMessage(plain)).toOption
    } yield msg

  private[otr] def decryptOtrEvent(ev: OtrEvent): Future[Either[OtrError, GenericMessage]] =
    clients.getOrCreateClient(ev.from, ev.sender) flatMap { _ =>
      decryptMessage(ev.from, ev.sender, ev.ciphertext)
        .map(Right(_))
        .recoverWith {
          case e: CryptoException =>
            import CryptoException.Code._
            e.code match {
              case DUPLICATE_MESSAGE =>
                verbose(s"detected duplicate message for event: $ev")
                Future successful Left(Duplicate)
              case OUTDATED_MESSAGE =>
                error(s"detected outdated message for event: $ev")
                reportOtrError(e, ev)
                Future successful Left(Duplicate)
              case REMOTE_IDENTITY_CHANGED =>
                reportOtrError(e, ev)
                Future successful Left(IdentityChangedError(ev.from, ev.sender))
              case _ =>
                reportOtrError(e, ev)
                Future successful Left(DecryptionError(e.getMessage, ev.from, ev.sender))
            }
      }
    }

  // update client info and send error report to hockey, we want client info to somehow track originating platform
  private def reportOtrError(e: CryptoException, ev: OtrEvent) = sync.syncClients(ev.from) map { _ =>
    clients.getClient(ev.from, ev.sender) foreach { client =>
      HockeyApp.saveException(e, s"event: $ev, client: $client")
    }
  }

  def resetSession(conv: ConvId, user: UserId, client: ClientId) =
    for {
      _ <- sessions.deleteSession(sessionId(user, client))
      _ <- clientsStorage.updateVerified(user, client, verified = false)
      _ <- sync.syncPreKeys(user, Set(client))
      syncId <- sync.postSessionReset(conv, user, client)
    } yield syncId

  def decryptCloudMessage(data: Array[Byte], mac: Array[Byte]) = clients.getSelfClient map {
    case Some(client @ Client(_, _, _, _, _, _, Some(key), _, _)) =>
      verbose(s"decrypting gcm for client $client")
      if (hmacSha256(key, data).toSeq != mac.toSeq) {
        warn(s"gcm MAC doesn't match")
        None
      } else
        LoggedTry(new JSONObject(new String(AESUtils.decrypt(key.encKey, data), "utf8"))).toOption
    case c =>
      warn(s"can not decrypt gcm, no signaling key found: $c")
      None
  }

  def decryptMessage(user: UserId, clientId: ClientId, msg: Array[Byte]): Future[GenericMessage] =
    sessions.decryptMessage(sessionId(user, clientId), msg) .map { plain =>
      verbose(s"decrypted data len: ${plain.length}")
      GenericMessage(plain)
    }

  def encryptTargetedMessage(user: UserId, client: ClientId, msg: GenericMessage): Future[Option[OtrClient.EncryptedContent]] = {
    val msgData = GenericMessage.toByteArray(msg)

    sessions.withSession(sessionId(user, client)) { session =>
      EncryptedContent(Map(user -> Map(client -> session.encrypt(msgData))))
    }
  }

  /**
    * @param useFakeOnError when true, we will return bomb emoji as msg content on encryption errors (for failing client)
    * @param partialResult partial content encrypted in previous run, we will use that instead of encrypting again when available
    * @param recipients users who this message shall be encrypted for; None means 'all active users'
    */
  def encryptMessage(convId: ConvId, msg: GenericMessage, useFakeOnError: Boolean = false, partialResult: EncryptedContent = EncryptedContent.Empty, recipients: Option[Set[UserId]] = None): Future[OtrClient.EncryptedContent] = {
    val msgData = GenericMessage.toByteArray(msg)

    def previous(user: UserId, client: ClientId) =
      partialResult.content.get(user).flatMap(_.get(client)).filter(arr => arr.nonEmpty && arr != EncryptionFailedMsg)

    def encrypt(user: UserId, clients: Seq[Client]) = Future.traverse(clients) { client =>
      previous(user, client.id) match {
        case Some(bytes) => Future successful Some(client.id -> bytes)
        case None =>
          verbose(s"encrypt for client: $client")
          sessions.withSession(sessionId(user, client.id)) { session =>
            client.id -> session.encrypt(msgData)
          } recover {
            case e: Throwable =>
              HockeyApp.saveException(e, s"encryption failed for user: $user, client: $client")
              if (useFakeOnError) Some(client.id -> EncryptionFailedMsg)
              else None
          }
      }
    } map { ms => user -> ms.flatten.toMap }

    members.getActiveUsers(convId) flatMap { all =>
      val users = all.filter(id => recipients.forall(_(id)))
      verbose(s"active users: $all, filtered: $users")
      Future.traverse(users) { user =>
        targetClients(user) flatMap { encrypt(user, _) }
      } map (res => EncryptedContent(res.toMap.filter(_._2.nonEmpty)))
    }
  }

  // list of clients to which the message should be sent for given user
  private def targetClients(user: UserId) =
    clientsStorage.getClients(user) map { cs =>
      if (user == selfUserId) cs.filter(_.id != clientId)
      else cs
    }

  def deleteClients(userMap: Map[UserId, Seq[ClientId]]) =
    Future.traverse(userMap) {
      case (user, cs) =>
        clients.removeClients(user, cs) flatMap { _ =>
          Future.traverse(cs) { c => sessions.deleteSession(sessionId(user, c)) }
        }
    }

  def fingerprintSignal(userId: UserId, cId: ClientId): Signal[Option[Array[Byte]]] =
    if (userId == selfUserId && cId == clientId) Signal.future(cryptoBox { cb => Future successful cb.getLocalFingerprint })
    else cryptoBox.sessions.remoteFingerprint(sessionId(userId, cId))

  def encryptAssetDataCBC(key: AESKey, data: LocalData): Future[(Sha256, LocalData, EncryptionAlgorithm)] = {
    import Threading.Implicits.Background

    def encryptFile() = cache.createForFile(length = Some(sizeWithPaddingAndIV(data.length))) map { entry =>
      val mac = AESUtils.encrypt(key, data.inputStream, entry.outputStream)
      (mac, entry, EncryptionAlgorithm.AES_CBC)
    }

    def encryptBytes() = {
      val bos = new ByteArrayOutputStream()
      val mac = AESUtils.encrypt(key, data.inputStream, bos)
      cache.addData(CacheKey(), bos.toByteArray) map { (mac, _, EncryptionAlgorithm.AES_CBC) }
    }

    data.byteArray.fold(encryptFile()){ _ => encryptBytes() }
  }

  // TODO: AN-5168. Right now throws a NotImplementedError when called; to be implemented later
  def encryptAssetDataGCM(key: AESKey, data: LocalData): Future[(Sha256, LocalData, EncryptionAlgorithm)] = ???

  def encryptAssetData(key: AESKey, data: LocalData):Future[(Sha256, LocalData, EncryptionAlgorithm)] =
    if(prefs.v31AssetsEnabled) encryptAssetDataGCM(key, data)
    else encryptAssetDataCBC(key, data)

  def decryptAssetDataCBC(assetId: AssetId, otrKey: Option[AESKey], sha: Option[Sha256], data: Option[Array[Byte]]): Option[Array[Byte]] = {
    data.flatMap { arr =>
      otrKey.map { key =>
        if (sha.forall(_.str == com.waz.utils.sha2(arr))) LoggedTry(AESUtils.decrypt(key, arr)).toOption else None
      }.getOrElse {
        warn(s"got otr asset event without otr key: $assetId")
        Some(arr)
      }
    }.filter(_.nonEmpty)
  }

  // TODO: AN-5167. Right now throws a NotImplementedError when called; to be implemented later
  def decryptAssetDataGCM(assetId: AssetId, otrKey: Option[AESKey], sha: Option[Sha256], data: Option[Array[Byte]]): Option[Array[Byte]] = ???

  override def decryptAssetData(assetId: AssetId, otrKey: Option[AESKey], sha: Option[Sha256], data: Option[Array[Byte]], encryption: Option[EncryptionAlgorithm]) =
    (prefs.v31AssetsEnabled, encryption) match {
      case (true, Some(EncryptionAlgorithm.AES_GCM)) => decryptAssetDataGCM(assetId, otrKey, sha, data)
      case _ => decryptAssetDataCBC(assetId, otrKey, sha, data)
    }

}

object OtrService {

  val EncryptionFailedMsg = "\uD83D\uDCA3".getBytes("utf8")

  def sessionId(user: UserId, client: ClientId) = s"${user}_$client"

  def hmacSha256(key: SignalingKey, data: Array[Byte]) = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key.mac)
    mac.doFinal(data)
  }

  def sizeWithPaddingAndIV(size: Long) = size + (32L - (size % 16L))
}
