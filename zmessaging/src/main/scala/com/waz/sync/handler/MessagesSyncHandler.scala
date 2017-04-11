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
package com.waz.sync.handler

import android.content.Context
import com.waz.HockeyApp
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.api.impl.ErrorResponse.internalError
import com.waz.api.{EphemeralExpiration, Message}
import com.waz.cache.CacheService
import com.waz.content.{MembersStorage, MessagesStorage}
import com.waz.model.AssetData.{ProcessingTaskKey, UploadTaskKey}
import com.waz.model.AssetStatus.{Syncable, UploadCancelled, UploadFailed}
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.{Ephemeral, Knock, Location, MsgEdit}
import com.waz.model._
import com.waz.model.sync.ReceiptType
import com.waz.service.assets._
import com.waz.service.conversation.{ConversationEventsService, ConversationsContentUpdater}
import com.waz.service.messages.{MessagesContentUpdater, MessagesService}
import com.waz.service.otr.OtrService
import com.waz.service.{MetaDataService, _}
import com.waz.sync.client.MessagesClient
import com.waz.sync.otr.OtrSyncHandler
import com.waz.sync.queue.ConvLock
import com.waz.sync.{SyncResult, SyncServiceHandle}
import com.waz.threading.CancellableFuture
import com.waz.threading.CancellableFuture.CancelException
import com.waz.utils.{RichFutureEither, _}
import com.waz.znet.Response.Status
import com.waz.znet.ZNetClient.{ErrorOr, ErrorOrResponse}
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.Future.successful

class MessagesSyncHandler(context: Context, service: MessagesService, msgContent: MessagesContentUpdater, convEvents: ConversationEventsService,
                          client: MessagesClient, otr: OtrService, otrSync: OtrSyncHandler, convs: ConversationsContentUpdater, storage: MessagesStorage,
                          assetSync: AssetSyncHandler, network: NetworkModeService, metadata: MetaDataService, prefs: PreferenceService,
                          sync: SyncServiceHandle, assets: AssetService, users: UserService, cache: CacheService,
                          members: MembersStorage, errors: ErrorsService, timeouts: Timeouts) {

  import com.waz.threading.Threading.Implicits.Background

  private implicit val logTag: LogTag = logTagFor[MessagesSyncHandler]

  def postDeleted(convId: ConvId, msgId: MessageId): Future[SyncResult] =
    convs.convById(convId) flatMap {
      case Some(conv) =>
        val msg = GenericMessage(Uid(), Proto.MsgDeleted(conv.remoteId, msgId))
        otrSync.postOtrMessage(ConvId(users.selfUserId.str), RConvId(users.selfUserId.str), msg) map {_.fold(e => SyncResult(e), _ => SyncResult.Success)}
      case None =>
        successful(SyncResult(internalError("conversation not found")))
    }

  def postRecalled(convId: ConvId, msgId: MessageId, recalled: MessageId): Future[SyncResult] =
    convs.convById(convId) flatMap {
      case Some(conv) =>
        val msg = GenericMessage(msgId.uid, Proto.MsgRecall(recalled))
        otrSync.postOtrMessage(conv.id, conv.remoteId, msg) flatMap {
          case Left(e) => successful(SyncResult(e))
          case Right(time) =>
            msgContent.updateMessage(msgId)(_.copy(editTime = time.instant, state = Message.Status.SENT)) map { _ => SyncResult.Success }
        }
      case None =>
        successful(SyncResult(internalError("conversation not found")))
    }

  def postReceipt(convId: ConvId, msgId: MessageId, userId: UserId, tpe: ReceiptType): Future[SyncResult] =
    convs.convById(convId) flatMap {
      case Some(conv) =>
        val (msg, recipients) = tpe match {
          case ReceiptType.Delivery         => (GenericMessage(msgId.uid, Proto.Receipt(msgId)), Set(userId))
          case ReceiptType.EphemeralExpired => (GenericMessage(msgId.uid, Proto.MsgRecall(msgId)), Set(users.selfUserId, userId))
        }

        otrSync.postOtrMessage(conv.id, conv.remoteId, msg, Some(recipients), nativePush = false) map {
          case Left(e) => SyncResult(e)
          case Right(time) => SyncResult.Success
        }
      case None =>
        successful(SyncResult(internalError("conversation not found")))
    }

  def postMessage(convId: ConvId, id: MessageId, editTime: Instant)(implicit convLock: ConvLock): Future[SyncResult] = {

    def shouldGiveUpSending(msg: MessageData) = network.isOfflineMode || timeouts.messages.sendingTimeout.elapsedSince(msg.time)

    storage.getMessage(id) flatMap { message =>
      message.fold(successful(None: Option[ConversationData]))(msg => convs.convById(msg.convId)) map { conv =>
        (message, conv)
      }
    } flatMap {
      case (Some(msg), Some(conv)) =>
        postMessage(conv, msg, editTime)
          .recover {
            case c: CancelException =>
              SyncResult(ErrorResponse.Cancelled)
            case e: Throwable =>
              error(s"postMessage($conv, $msg) failed", e)
              SyncResult.Failure(Some(internalError(e.getMessage)), shouldRetry = false)
          }
          .flatMap {
            case SyncResult.Success => service.messageSent(conv.id, msg) map (_ => SyncResult.Success)
            case res@SyncResult.Failure(Some(ErrorResponse.Cancelled), _) =>
              verbose(s"postMessage($msg) was cancelled")
              msgContent.updateMessage(id)(_.copy(state = Message.Status.FAILED_READ)) map { _ => res }
            case res@SyncResult.Failure(error, shouldRetry) =>
              val shouldGiveUp = shouldGiveUpSending(msg)
              warn(s"postMessage failed with res: $res, shouldRetry: $shouldRetry, shouldGiveUp: $shouldGiveUp, offline: ${network.isOfflineMode}, msg.localTime: ${msg.localTime}")
              if (!shouldRetry || shouldGiveUp)
                service.messageDeliveryFailed(conv.id, msg, error.getOrElse(internalError(s"shouldRetry: $shouldRetry, shouldGiveUp: $shouldGiveUp, offline: ${network.isOfflineMode}"))).map(_ => SyncResult.Failure(error, shouldRetry = false))
              else successful(res)

          }
      case (Some(msg), None) =>
        HockeyApp.saveException(new Exception("postMessage failed, couldn't find conversation for msg"), s"msg: $msg")
        service.messageDeliveryFailed(msg.convId, msg, internalError("conversation not found")) map (_ => SyncResult.aborted())

      case (msg, conv) =>
        HockeyApp.saveException(new Exception("postMessage failed, couldn't find a message nor conversation"), s"msg id: $id")
        successful(SyncResult.aborted())
    }
  }

  private def postMessage(conv: ConversationData, msg: MessageData, reqEditTime: Instant)(implicit convLock: ConvLock): Future[SyncResult] = {

    def postTextMessage() = {
      val (gm, isEdit) =
        msg.protos.lastOption match {
          case Some(m@GenericMessage(id, MsgEdit(ref, text))) if reqEditTime != Instant.EPOCH => (m, true) // will send edit only if original message was already sent (reqEditTime > EPOCH)
          case _ => (GenericMessage.TextMessage(msg), false)
        }

      postMessage(conv, msg.ephemeral, gm) flatMap {
        case Right(time) if isEdit =>
          // delete original message and create new message with edited content
          service.applyMessageEdit(conv.id, msg.userId, time.instant, gm) map {
            case Some(m) => Right(m)
            case _ => Right(msg.copy(time = time.instant))
          }

        case Right(time) => successful(Right(msg.copy(time = time.instant)))
        case Left(err) => successful(Left(err))
      }
    }

    import Message.Type._

    def post: ErrorOr[Instant] = msg.msgType match {
      case MessageData.IsAsset() => Cancellable(UploadTaskKey(msg.assetId))(uploadAsset(conv, msg)).future
      case KNOCK => postMessage(conv, msg.ephemeral, GenericMessage(msg.id.uid, msg.ephemeral, Proto.Knock())).map(_.map(_.instant))
      case TEXT | TEXT_EMOJI_ONLY => postTextMessage().map(_.map(_.time))
      case RICH_MEDIA =>
        postTextMessage() flatMap {
          case Right(m) => sync.postOpenGraphData(conv.id, m.id, m.editTime) map { _ => Right(m.time) }
          case Left(err) => successful(Left(err))
        }
      case LOCATION =>
        msg.protos.headOption match {
          case Some(GenericMessage(id, loc: Location)) if msg.isEphemeral =>
            postMessage(conv, msg.ephemeral, GenericMessage(id, Ephemeral(msg.ephemeral, loc))).map(_.map(_.instant))
          case Some(proto) =>
            postMessage(conv, msg.ephemeral, proto).map(_.map(_.instant))
          case None =>
            successful(Left(internalError(s"Unexpected location message content: $msg")))
        }
      case tpe =>
        msg.protos.headOption match {
          case Some(proto) if !msg.isEphemeral =>
            verbose(s"sending generic message: $proto")
            postMessage(conv, msg.ephemeral, proto).map(_.map(_.instant))
          case Some(proto) =>
            successful(Left(internalError(s"Can not send generic ephemeral message: $msg")))
          case None =>
            successful(Left(internalError(s"Unsupported message type in postOtrMessage: $tpe")))
        }
    }

    post flatMap {
      case Right(time) =>
        verbose(s"postOtrMessage($msg) successful $time")
        messageSent(conv.id, msg, time) map { _ => SyncResult.Success }
      case Left(error@ErrorResponse(Status.Forbidden, _, "unknown-client")) =>
        verbose(s"postOtrMessage($msg), failed: $error")
        otr.clients.onCurrentClientRemoved() map { _ => SyncResult(error) }
      case Left(error@ErrorResponse.Cancelled) =>
        verbose(s"postOtrMessage($msg) cancelled")
        successful(SyncResult(error))
      case Left(error) =>
        verbose(s"postOtrMessage($msg), failed: $error")
        successful(SyncResult(error))
    }
  }

  private def uploadAsset(conv: ConversationData, msg: MessageData)(implicit convLock: ConvLock): ErrorOrResponse[Instant] = {
    verbose(s"uploadAsset($conv, $msg)")

    def postAssetMessage(asset: AssetData, preview: Option[AssetData]): ErrorOrResponse[Instant] = {
      val proto = GenericMessage(msg.id.uid, msg.ephemeral, Proto.Asset(asset, preview))
      CancellableFuture.lift(postMessage(conv, msg.ephemeral, proto) flatMap {
        case Right(time) =>
          verbose(s"posted asset message for: $asset")
          service.content.updateMessage(msg.id)(_.copy(protos = Seq(proto), time = time.instant)) map { _ => Right(time.instant) }
        case Left(err) =>
          warn(s"posting asset message failed: $err")
          Future successful Left(err)
      })
    }

    //TODO Dean: Update asset status to UploadInProgress after posting original - what about images...?
    def postOriginal(asset: AssetData): ErrorOrResponse[Instant] =
      if (asset.status != AssetStatus.UploadNotStarted) CancellableFuture successful Right(msg.time)
      else asset.mime match {
        case Mime.Image() => CancellableFuture.successful(Right(msg.time))
        case _ => postAssetMessage(asset, None)
      }

    def sendWithV3(asset: AssetData) = {
      postOriginal(asset).flatMap {
        case Left(err) => CancellableFuture successful Left(err)
        case Right(origTime) =>
          convLock.release()
          //send preview
          CancellableFuture.lift(asset.previewId.map(assets.storage.get).getOrElse(Future successful None)).flatMap {
            case Some(prev) => assetSync.uploadAssetData(prev.id).flatMap {
              case Right(Some(updated)) =>
                postAssetMessage(asset, Some(updated)).map {
                  case (Right(_)) => Right(Some(updated))
                  case (Left(err)) => Left(err)
                }
              case Right(None) => CancellableFuture successful Right(None)
              case Left(err) => CancellableFuture successful Left(err)
            }
            case None => CancellableFuture successful Right(None)
          }.flatMap { //send asset
            case Right(prev) =>
              assetSync.uploadAssetData(asset.id).flatMap {
                case Right(Some(updated)) => postAssetMessage(updated, prev).map(_.fold(Left(_), _ => Right(origTime)))
                case Right(None) => CancellableFuture successful Right(Instant.EPOCH) //TODO Dean: what's a good default
                case Left(err) if err.message.contains(AssetSyncHandler.AssetTooLarge) =>
                  CancellableFuture.lift(errors.addAssetTooLargeError(conv.id, msg.id).map {_ => Left(err)})
                case Left(err) => CancellableFuture successful Left(err)
              }
            case Left(err) => CancellableFuture successful Left(err)
          }
      }
    }

    //want to wait until asset meta and preview data is loaded before we send any messages
    AssetProcessing.get(ProcessingTaskKey(msg.assetId)).flatMap { _ =>
      CancellableFuture lift assets.storage.get(msg.assetId).flatMap {
        case None => CancellableFuture successful Left(internalError(s"no asset found for msg: $msg"))
        case Some(asset) if asset.status == AssetStatus.UploadCancelled => CancellableFuture successful Left(ErrorResponse.Cancelled)
        case Some(asset) =>
          verbose(s"Sending asset: $asset")
          sendWithV3(asset)
      }
    }
  }

  private def postMessage(conv: ConversationData, exp: EphemeralExpiration, msg: GenericMessage) =
    recipients(conv, exp) flatMap { otrSync.postOtrMessage(conv.id, conv.remoteId, msg, _) }

  private def recipients(conv: ConversationData, exp: EphemeralExpiration) =
    if (exp == EphemeralExpiration.NONE) Future successful None
    else if (conv.convType == ConversationType.Group) {
      // ephemeral msgs should not be sent to self devices,
      // to handle that properly we need special backend parameters to exclude self user devices (don't report missing)
      // FIXME: for now we just send it to active conv members, this can lead to 'lost' messages in case of races
      members.getActiveUsers(conv.id).map { ms => Some(ms.toSet - users.selfUserId) }
    } else
      Future successful Some(Set(UserId(conv.id.str))) // send only to other users' devices

  private[waz] def messageSent(convId: ConvId, msg: MessageData, time: Instant) = {
    debug(s"otrMessageSent($convId. $msg, $time)")

    def updateLocalTimes(conv: ConvId, prevTime: Instant, time: Instant) =
      msgContent.updateLocalMessageTimes(conv, prevTime, time) flatMap { updated =>
        val prevLastTime = updated.lastOption.fold(prevTime)(_._1.time)
        val lastTime = updated.lastOption.fold(time)(_._2.time)
        // update conv lastRead time if there is no unread message after the message that was just sent
        convs.storage.update(conv,
          c => if (!c.lastRead.isAfter(prevLastTime)) c.copy(lastRead = lastTime) else c
        )
      }

    for {
      _ <- updateLocalTimes(convId, msg.time, time)
      _ <- convs.updateLastEvent(convId, time)
    } yield ()
  }

  def postAssetStatus(cid: ConvId, mid: MessageId, expiration: EphemeralExpiration, status: Syncable): Future[SyncResult] = {
    def post(conv: ConversationData, asset: AssetData): ErrorOr[Unit] =
      if (asset.status != status) successful(Left(internalError(s"asset $asset should have status $status")))
      else status match {
        case UploadCancelled =>
          postMessage(conv, expiration, GenericMessage(mid.uid, expiration, Proto.Asset(asset))).flatMapRight(_ => storage.remove(mid))
        case UploadFailed =>
          postMessage(conv, expiration, GenericMessage(mid.uid, expiration, Proto.Asset(asset))).mapRight(_ => ())
      }

    for {
      conv   <- convs.storage.get(cid) or internalError(s"conversation $cid not found")
      msg    <- storage.get(mid) or internalError(s"message $mid not found")
      aid     = msg.right.map(_.assetId)
      asset <- aid.flatMapFuture(id => assets.storage.get(id).or(internalError(s"asset $id not found")))
      result <- conv.flatMapFuture(c => asset.flatMapFuture(a => post(c, a)))
    } yield result.fold(SyncResult(_), _ => SyncResult.Success)
  }
}
