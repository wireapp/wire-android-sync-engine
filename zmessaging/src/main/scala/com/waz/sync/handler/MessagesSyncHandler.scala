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

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.api.impl.ErrorResponse
import com.waz.api.impl.ErrorResponse.{Cancelled, internalError}
import com.waz.cache2.CacheService
import com.waz.content.{MembersStorage, MessagesStorage}
import com.waz.model.AssetData.{ProcessingTaskKey, UploadTaskKey}
import com.waz.model.AssetStatus.{Syncable, UploadCancelled, UploadFailed}
import com.waz.model.GenericContent.{Ephemeral, Knock, Location, MsgEdit}
import com.waz.model.GenericMessage.TextMessage
import com.waz.model._
import com.waz.model.sync.ReceiptType
import com.waz.service._
import com.waz.service.assets._
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.assets2.Asset.{General, Image}
import com.waz.service.assets2.RawPreview.Ready
import com.waz.service.assets2.{AssetService, _}
import com.waz.service.conversation.{ConversationOrderEventsService, ConversationsContentUpdater}
import com.waz.service.messages.{MessagesContentUpdater, MessagesService}
import com.waz.service.otr.OtrClientsService
import com.waz.service.tracking.TrackingService
import com.waz.sync.SyncHandler.RequestInfo
import com.waz.sync.SyncResult.Failure
import com.waz.sync.client.{ErrorOr, ErrorOrResponse}
import com.waz.sync.otr.OtrSyncHandler
import com.waz.sync.queue.ConvLock
import com.waz.sync.{SyncResult, SyncServiceHandle}
import com.waz.threading.CancellableFuture
import com.waz.utils.{RichFutureEither, _}
import com.waz.znet2.http.ResponseCode

import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.concurrent.duration.FiniteDuration

class MessagesSyncHandler(selfUserId: UserId,
                          service:    MessagesService,
                          msgContent: MessagesContentUpdater,
                          clients:    OtrClientsService,
                          otrSync:    OtrSyncHandler,
                          convs:      ConversationsContentUpdater,
                          storage:    MessagesStorage,
                          assetSync:  AssetSyncHandler,
                          sync:       SyncServiceHandle,
                          assets:     AssetService,
                          rawAssetStorage: RawAssetStorage,
                          cache:      CacheService,
                          members:    MembersStorage,
                          tracking:   TrackingService,
                          errors:     ErrorsService,
                          timeouts: Timeouts) {
  import com.waz.threading.Threading.Implicits.Background

  def postDeleted(convId: ConvId, msgId: MessageId): Future[SyncResult] =
    convs.convById(convId).flatMap {
      case Some(conv) =>
        val msg = GenericMessage(Uid(), Proto.MsgDeleted(conv.remoteId, msgId))
        otrSync
          .postOtrMessage(ConvId(selfUserId.str), msg)
          .map(SyncResult(_))
      case None =>
        successful(Failure("conversation not found"))
    }


  def postRecalled(convId: ConvId, msgId: MessageId, recalled: MessageId): Future[SyncResult] =
    convs.convById(convId) flatMap {
      case Some(conv) =>
        val msg = GenericMessage(msgId.uid, Proto.MsgRecall(recalled))
        otrSync.postOtrMessage(conv.id, msg).flatMap {
          case Left(e) => successful(SyncResult(e))
          case Right(time) =>
            msgContent
              .updateMessage(msgId)(_.copy(editTime = time, state = Message.Status.SENT))
              .map(_ => SyncResult.Success)
        }
      case None =>
        successful(Failure("conversation not found"))
    }

  def postReceipt(convId: ConvId, msgs: Seq[MessageId], userId: UserId, tpe: ReceiptType): Future[SyncResult] =
    convs.convById(convId) flatMap {
      case Some(conv) =>
        val (msg, recipients) = tpe match {
          case ReceiptType.Delivery         => (GenericMessage(msgs.head.uid, Proto.DeliveryReceipt(msgs))(Proto.DeliveryReceipt), Set(userId))
          case ReceiptType.Read             => (GenericMessage(msgs.head.uid, Proto.ReadReceipt(msgs))(Proto.ReadReceipt), Set(userId))
          case ReceiptType.EphemeralExpired => (GenericMessage(msgs.head.uid, Proto.MsgRecall(msgs.head)), Set(selfUserId, userId))
        }

        otrSync
          .postOtrMessage(conv.id, msg, Some(recipients), nativePush = false)
          .map(SyncResult(_))
      case None =>
        successful(Failure("conversation not found"))
    }

  def postMessage(convId: ConvId, id: MessageId, editTime: RemoteInstant)(implicit info: RequestInfo): Future[SyncResult] =
    storage.getMessage(id).flatMap { message =>
      message
        .fold(successful(None: Option[ConversationData]))(msg => convs.convById(msg.convId))
        .map(conv => (message, conv))
    }.flatMap {
      case (Some(msg), Some(conv)) =>
        postMessage(conv, msg, editTime).flatMap {
          case Right(time) =>
            verbose(s"postOtrMessage($msg) successful $time")
            for {
              _ <- service.messageSent(convId, msg.id, time)
              (prevLastTime, lastTime) <- msgContent.updateLocalMessageTimes(convId, msg.time, time)
                .map(_.lastOption.map { case (p, c) => (p.time, c.time)}.getOrElse((msg.time, time)))
              // update conv lastRead time if there is no unread message after the message that was just sent
              _ <- convs.storage.update(convId, c => if (!c.lastRead.isAfter(prevLastTime)) c.copy(lastRead = lastTime) else c)
              _ <- convs.updateLastEvent(convId, time)
            } yield SyncResult.Success

          case Left(error) =>
            for {
              _ <- error match {
                case ErrorResponse(ResponseCode.Forbidden, _, "unknown-client") =>
                  clients.onCurrentClientRemoved()
                case _ =>
                  Future.successful({})
              }
              result <- SyncResult(error) match {
                case r: SyncResult.Failure =>
                  service
                    .messageDeliveryFailed(convId, msg, error)
                    .map(_ => r)
                case r =>
                  Future.successful(r)
              }
            } yield result
        }

      case (Some(msg), None) =>
        service
          .messageDeliveryFailed(msg.convId, msg, internalError("conversation not found"))
          .map(_ => Failure("postMessage failed, couldn't find conversation for msg"))

      case _ =>
        successful(Failure("postMessage failed, couldn't find either message or conversation"))
    }

  private def postMessage(conv: ConversationData, msg: MessageData, reqEditTime: RemoteInstant) = {

    def postTextMessage() = {
      val adjustedMsg = msg.adjustMentions(true).getOrElse(msg)

      val (gm, isEdit) =
        adjustedMsg.protos.lastOption match {
          case Some(m@GenericMessage(id, MsgEdit(ref, text))) if !reqEditTime.isEpoch =>
            (m, true) // will send edit only if original message was already sent (reqEditTime > EPOCH)
          case _ =>
            (TextMessage(adjustedMsg), false)
        }

      otrSync.postOtrMessage(conv.id, gm).flatMap {
        case Right(time) if isEdit =>
          // delete original message and create new message with edited content
          service.applyMessageEdit(conv.id, msg.userId, RemoteInstant(time.instant), gm) map {
            case Some(m) => Right(m)
            case _ => Right(msg.copy(time = RemoteInstant(time.instant)))
          }
        case Right(time) => successful(Right(msg.copy(time = time)))
        case Left(err) => successful(Left(err))
      }
    }

    import Message.Type._

    msg.msgType match {
      case _ if msg.isAssetMessage => Cancellable(UploadTaskKey(msg.assetId))(uploadAsset(conv, msg)).future
      case KNOCK => otrSync.postOtrMessage(conv.id, GenericMessage(msg.id.uid, msg.ephemeral, Proto.Knock(msg.expectsRead.getOrElse(false))))
      case TEXT | TEXT_EMOJI_ONLY => postTextMessage().map(_.map(_.time))
      case RICH_MEDIA =>
        postTextMessage().flatMap {
          case Right(m) => sync.postOpenGraphData(conv.id, m.id, m.editTime).map(_ => Right(m.time))
          case Left(err) => successful(Left(err))
        }
      case LOCATION =>
        msg.protos.headOption match {
          case Some(GenericMessage(id, loc: Location)) if msg.isEphemeral =>
            otrSync.postOtrMessage(conv.id, GenericMessage(id, Ephemeral(msg.ephemeral, loc)))
          case Some(proto) =>
            otrSync.postOtrMessage(conv.id, proto)
          case None =>
            successful(Left(internalError(s"Unexpected location message content: $msg")))
        }
      case tpe =>
        msg.protos.headOption match {
          case Some(proto) if !msg.isEphemeral =>
            verbose(s"sending generic message: $proto")
            otrSync.postOtrMessage(conv.id, proto)
          case Some(_) =>
            successful(Left(internalError(s"Can not send generic ephemeral message: $msg")))
          case None =>
            successful(Left(internalError(s"Unsupported message type in postOtrMessage: $tpe")))
        }
    }
  }

  private def uploadAsset(conv: ConversationData, msg: MessageData)(implicit convLock: ConvLock): ErrorOrResponse[RemoteInstant] = {
    import cats.data.EitherT
    import cats.instances.future._
    import cats.instances.either._
    import cats.syntax.either._

    verbose(s"uploadAsset($conv, $msg)")
    type Result[T] = EitherT[Future, ErrorResponse, T]

//    def postAssetMessage(asset: AssetData, preview: Option[AssetData]): ErrorOrResponse[RemoteInstant] = {
//      val proto = GenericMessage(msg.id.uid, msg.ephemeral, Proto.Asset(asset, preview))
//      CancellableFuture.lift(otrSync.postOtrMessage(conv.id, proto) flatMap {
//        case Right(time) =>
//          verbose(s"posted asset message for: $asset")
//          msgContent.updateMessage(msg.id)(_.copy(protos = Seq(proto), time = time)) map { _ => Right(time) }
//        case Left(err) =>
//          warn(s"posting asset message failed: $err")
//          Future successful Left(err)
//      })
//    }

    def postAssetMessage(asset: RawAsset[General], preview: Option[Asset[General]]): Result[RemoteInstant] = {
      val proto = GenericMessage(msg.id.uid, msg.ephemeral, Proto.Asset(asset, preview))
      for {
        time <- EitherT(otrSync.postOtrMessage(conv.id, proto))
        _ <- EitherT.liftF(msgContent.updateMessage(msg.id)(_.copy(protos = Seq(proto), time = time)))
      } yield time
    }

    //TODO Dean: Update asset status to UploadInProgress after posting original - what about images...?
    def postOriginal(asset: RawAsset[General]): Result[RemoteInstant] =
      if (asset.uploadStatus != UploadStatus.NotStarted) EitherT.fromEither(msg.time.asRight)
      else asset.details match {
        case _: Image => EitherT.fromEither(msg.time.asRight)
        case _ => postAssetMessage(asset, None)
      }

//    def sendWithV3(asset: RawAsset[General]) = {
//      postOriginal(asset).flatMap {
//        case Left(err) => CancellableFuture successful Left(err)
//        case Right(origTime) =>
//          convLock.release()
//          //send preview
//          CancellableFuture.lift(asset.previewId.map(assets.getAssetData).getOrElse(Future successful None)).flatMap {
//            case Some(prev) =>
//              service.retentionPolicy(conv).flatMap { retention =>
//                assetSync.uploadAssetData(prev.id, retention = retention).flatMap {
//                  case Right(updated) =>
//                    postAssetMessage(asset, Some(updated)).map {
//                      case Right(_) => Right(Some(updated))
//                      case Left(err) => Left(err)
//                    }
//                  case Left(err) => CancellableFuture successful Left(err)
//                }
//              }
//            case None => CancellableFuture successful Right(None)
//          }.flatMap { //send asset
//            case Right(prev) =>
//              service.retentionPolicy(conv).flatMap { retention =>
//                assetSync.uploadAssetData(asset.id, retention = retention).flatMap {
//                  case Right(updated) => postAssetMessage(updated, prev).map(_.fold(Left(_), _ => Right(origTime)))
//                  case Left(err) if err.message.contains(AssetSyncHandler.AssetTooLarge) =>
//                    CancellableFuture.lift(errors.addAssetTooLargeError(conv.id, msg.id).map { _ => Left(err) })
//                  case Left(err) => CancellableFuture successful Left(err)
//                }
//              }
//            case Left(err) => CancellableFuture successful Left(err)
//          }
//      }
//    }

    def sendWithV3(rawAsset: RawAsset[General]) = {
      for {
        time <- postOriginal(rawAsset)
        _ = convLock.release()
        updatedRawAsset <- rawAsset.preview match {
          case RawPreview.NotReady => assets.createAndSavePreview(rawAsset)
          case _ => Future.successful(rawAsset)
        }
        previewAsset <- updatedRawAsset.preview match {
          case RawPreview.NotUploaded(rawAssetId) =>
            assets.uploadAsset(rawAssetId).flatMap(asset => postAssetMessage(updatedRawAsset, Some(asset)).value)
          case _ => CancellableFuture.successful(())
        }
        asset <- assets.uploadAsset(updatedRawAsset.id)
        _ <- postAssetMessage(asset, previewAsset)
      } yield time
    }

    //want to wait until asset meta and preview data is loaded before we send any messages
    AssetProcessing.get(ProcessingTaskKey(msg.assetId)).flatMap { _ =>
      CancellableFuture lift rawAssetStorage.find(RawAssetId(msg.id.str)).flatMap {
        case None => CancellableFuture successful Left(internalError(s"no asset found for msg: $msg"))
        case Some(asset) if asset.uploadStatus == UploadStatus.Cancelled => CancellableFuture successful Left(ErrorResponse.Cancelled)
        case Some(asset) =>
          verbose(s"Sending asset: $asset")
          sendWithV3(asset)
      }
    }
  }

  def postAssetStatus(cid: ConvId, mid: MessageId, expiration: Option[FiniteDuration], status: Syncable): Future[SyncResult] = {
    def post(conv: ConversationData, asset: AssetData): ErrorOr[Unit] =
      if (asset.status != status) successful(Left(internalError(s"asset $asset should have status $status")))
      else status match {
        case UploadCancelled => otrSync.postOtrMessage(conv.id, GenericMessage(mid.uid, expiration, Proto.Asset(asset, expectsReadConfirmation = false))).flatMapRight(_ => storage.remove(mid))
        case UploadFailed if asset.isImage => successful(Left(internalError(s"upload failed for image $asset")))
        case UploadFailed => otrSync.postOtrMessage(conv.id, GenericMessage(mid.uid, expiration, Proto.Asset(asset, expectsReadConfirmation = false))).mapRight(_ => ())
      }

    for {
      conv   <- convs.storage.get(cid) or internalError(s"conversation $cid not found")
      msg    <- storage.get(mid) or internalError(s"message $mid not found")
      aid    = msg.right.map(_.assetId)
      asset  <- aid.flatMapFuture(id => assets.getAssetData(id).or(internalError(s"asset $id not found")))
      result <- conv.flatMapFuture(c => asset.flatMapFuture(a => post(c, a)))
    } yield SyncResult(result)
  }
}
