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
package com.waz.sync.otr

import java.util.Date

import com.waz.ZLog._
import com.waz.api.Verification
import com.waz.api.impl.ErrorResponse
import com.waz.api.impl.ErrorResponse.internalError
import com.waz.cache.{CacheService, LocalData}
import com.waz.content.ConversationStorage
import com.waz.model.AssetData.RemoteData
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.assets.AssetService
import com.waz.service.conversation.ConversationsService
import com.waz.service.messages.MessagesService
import com.waz.service.otr.OtrService
import com.waz.service.{ErrorsService, PreferenceService, UserService}
import com.waz.sync.SyncResult
import com.waz.sync.client.AssetClient.UploadResponse
import com.waz.sync.client.MessagesClient.OtrMessage
import com.waz.sync.client.OtrClient.{ClientMismatch, EncryptedContent, MessageResponse}
import com.waz.sync.client.{AssetClient, MessagesClient, OtrClient}
import com.waz.threading.CancellableFuture
import com.waz.utils.crypto.AESUtils
import com.waz.znet.ZNetClient.ErrorOrResponse

import scala.concurrent.Future
import scala.concurrent.Future.successful

class OtrSyncHandler(client: OtrClient, msgClient: MessagesClient, assetClient: AssetClient, service: OtrService, assets: AssetService,
                     convs: ConversationsService, convStorage: ConversationStorage, users: UserService, messages: MessagesService,
                     errors: ErrorsService, clientsSyncHandler: OtrClientsSyncHandler, cache: CacheService, prefs: PreferenceService) {

  import OtrSyncHandler._
  import com.waz.threading.Threading.Implicits.Background

  def postOtrMessage(conv: ConversationData, message: GenericMessage): Future[Either[ErrorResponse, Date]] =
    postOtrMessage(conv.id, conv.remoteId, message)

  def postOtrMessage(convId: ConvId, remoteId: RConvId, message: GenericMessage, recipients: Option[Set[UserId]] = None, nativePush: Boolean = true): Future[Either[ErrorResponse, Date]] =
    service.clients.getSelfClient flatMap {
      case Some(otrClient) =>
        postEncryptedMessage(convId, message, recipients = recipients) {
          case (content, retry) if content.estimatedSize < MaxContentSize => msgClient.postMessage(remoteId, OtrMessage(otrClient.id, content, nativePush = nativePush), ignoreMissing(retry), recipients)
          case (content, retry) =>
            verbose(s"Message content too big, will post as External. Estimated size: ${content.estimatedSize}")
            postExternalMessage(otrClient.id, convId, remoteId, message, recipients, nativePush)
        }
      case None =>
        successful(Left(internalError("Client is not registered")))
    }

  // will retry 3 times, at first we try to send message in normal way,
  // when it fails we will try sending empty messages to contacts for which we can not encrypt the message
  // in last try we will use 'ignore_missing' flag
  private def postEncryptedMessage(convId: ConvId, message: GenericMessage, retry: Int = 0, previous: EncryptedContent = EncryptedContent.Empty, recipients: Option[Set[UserId]] = None)(f: (EncryptedContent, Int) => ErrorOrResponse[MessageResponse]): Future[Either[ErrorResponse, Date]] =
  convStorage.get(convId) flatMap {
    case Some(conv) if conv.verified == Verification.UNVERIFIED && message.hasCalling =>
      successful(Left(ErrorResponse.Unverified))
    case Some(conv) if conv.verified == Verification.UNVERIFIED =>
      // refusing to send messages to 'degraded' conversation, UI should show error and ask user to verify devices (or ignore it - which will change state to UNKNOWN)
      errors.addConvUnverifiedError(convId, MessageId(message.messageId)) map { _ => Left(ErrorResponse.Unverified) }
    case _ =>
      service.encryptMessage(convId, message, retry > 0, previous, recipients) flatMap { content =>
        f(content, retry).future flatMap {
          case Right(MessageResponse.Success(ClientMismatch(redundant, _, deleted, time))) =>
            // XXX: we are ignoring redundant clients, we rely on members list to encrypt messages, so if user left the conv then we won't use his clients on next message
            service.deleteClients(deleted) map { _ => Right(time) }
          case Right(MessageResponse.Failure(ClientMismatch(redundant, missing, deleted, _))) =>
            service.deleteClients(deleted) flatMap { _ =>
              if (retry > 2)
                successful(Left(internalError(s"postEncryptedMessage failed with missing clients after several retries: $missing")))
              else
                clientsSyncHandler.syncSessions(missing) flatMap {
                  case None =>
                    // XXX: encrypt relies on conv members list, we only add clients for users in conv,
                    // if members list is broken then we will always end up with missing clients,
                    // maybe we should update members list in this place ???
                    postEncryptedMessage(convId, message, retry + 1, content, recipients)(f)
                  case Some(err) if retry < 3 =>
                    error(s"syncSessions for missing clients failed: $err")
                    postEncryptedMessage(convId, message, retry + 1, content, recipients)(f)
                  case Some(err) =>
                    successful(Left(err))
                }
            }
          case Left(err) =>
            error(s"postOtrMessage failed with error: $err")
            successful(Left(err))
        }
      }
  }

  private def ignoreMissing(retry: Int) = retry > 1

  private def postExternalMessage(clientId: ClientId, convId: ConvId, remoteId: RConvId, message: GenericMessage, recipients: Option[Set[UserId]], nativePush: Boolean): ErrorOrResponse[MessageResponse] = {
    val key = AESKey()
    val (sha, data) = AESUtils.encrypt(key, GenericMessage.toByteArray(message))

    CancellableFuture.lift {
      postEncryptedMessage(convId, GenericMessage(Uid(message.messageId), Proto.External(key, sha)), recipients = recipients) { (content, retry) =>
        msgClient.postMessage(remoteId, OtrMessage(clientId, content, Some(data), nativePush), ignoreMissing(retry), recipients)
      } map {
        // that's a bit of a hack, but should be harmless
        case Right(time) => Right(MessageResponse.Success(ClientMismatch(Map.empty, Map.empty, Map.empty, time)))
        case Left(err) => Left(err)
      }
    }
  }

  def uploadAssetDataV3(data: LocalData, key: Option[AESKey], mime: Mime = Mime.Default): CancellableFuture[Either[ErrorResponse, RemoteData]] =
    CancellableFuture.lift(service.clients.getSelfClient).flatMap {
      case Some(otrClient) =>
        key match {
          case Some(k) => CancellableFuture.lift(service.encryptAssetData(k, data)) flatMap {
            case (sha, encrypted) => assetClient.uploadAsset(encrypted, Mime.Default).map { //encrypted data => Default mime
              case Right(UploadResponse(rId, _, token)) => Right(RemoteData(Some(rId), token, key, Some(sha)))
              case Left(err) => Left(err)
            }
          }
          case _ => assetClient.uploadAsset(data, mime, public = true).map {
            case Right(UploadResponse(rId, _, _)) => Right(RemoteData(Some(rId)))
            case Left(err) => Left(err)
          }
        }
      case None => CancellableFuture.successful(Left(internalError("Client is not registered")))
    }

  def postSessionReset(convId: ConvId, user: UserId, client: ClientId) = {

    val msg = GenericMessage(Uid(), Proto.ClientAction.SessionReset)

    val convData = convStorage.get(convId) flatMap {
      case None => convStorage.get(ConvId(user.str))
      case conv => successful(conv)
    }

    def msgContent = service.encryptTargetedMessage(user, client, msg) flatMap {
      case Some(ct) => successful(Some(ct))
      case None =>
        clientsSyncHandler.syncSessions(Map(user -> Seq(client))) flatMap { _ =>
          service.encryptTargetedMessage(user, client, msg)
        }
    }

    convData flatMap {
      case None => successful(SyncResult(internalError(s"conv not found: $convId, for user: $user in postSessionReset")))
      case Some(conv) =>
        service.clients.getSelfClient flatMap {
          case None => successful(SyncResult(internalError(s"client not registered")))
          case Some(otrClient) =>
            msgContent flatMap {
              case None => successful(SyncResult(internalError(s"session not found for $user, $client")))
              case Some(content) =>
                msgClient.postMessage(conv.remoteId, OtrMessage(otrClient.id, content), ignoreMissing = true).future map {
                  case Right(_) => SyncResult.Success
                  case Left(err) => SyncResult(err)
                }
            }
        }
    }
  }
}

object OtrSyncHandler {
  private implicit val tag: LogTag = logTagFor[OtrSyncHandler]

  val MaxInlineSize  = 10 * 1024
  val MaxContentSize = 256 * 1024 // backend accepts 256KB for otr messages, but we would prefer to send less
}
