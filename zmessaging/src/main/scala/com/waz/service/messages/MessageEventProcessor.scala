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
package com.waz.service.messages

import com.waz.api.{Message, Verification}
import com.waz.content.MessagesStorage
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.log.LogSE._
import com.waz.model.GenericContent.{Asset, Calling, Cleared, DeliveryReceipt, Ephemeral, Knock, LastRead, Location, MsgDeleted, MsgEdit, MsgRecall, Reaction, Text}
import com.waz.model.{GenericContent, _}
import com.waz.service.EventScheduler
import com.waz.service.assets2.{AssetService, AssetStatus, DownloadAsset, DownloadAssetStatus, DownloadAssetStorage, GeneralAsset, Asset => Asset2}
import com.waz.service.conversation.{ConversationsContentUpdater, ConversationsService}
import com.waz.service.otr.OtrService
import com.waz.service.otr.VerificationStateUpdater.{ClientAdded, ClientUnverified, MemberAdded, VerificationChange}
import com.waz.threading.Threading
import com.waz.utils.crypto.ReplyHashing
import com.waz.utils.events.EventContext
import com.waz.utils.{RichFuture, _}

import scala.concurrent.Future

class MessageEventProcessor(selfUserId:           UserId,
                            storage:              MessagesStorage,
                            content:              MessagesContentUpdater,
                            assets:               AssetService,
                            replyHashing:         ReplyHashing,
                            msgsService:          MessagesService,
                            convsService:         ConversationsService,
                            convs:                ConversationsContentUpdater,
                            otr:                  OtrService,
                            downloadAssetStorage: DownloadAssetStorage) extends DerivedLogTag {

  import MessageEventProcessor._
  import Threading.Implicits.Background
  private implicit val ec = EventContext.Global

  val messageEventProcessingStage = EventScheduler.Stage[MessageEvent] { (convId, events) =>
    convs.processConvWithRemoteId(convId, retryAsync = true) { conv =>
      verbose(l"processing events for conv: $conv, events: $events")
      convsService.isGroupConversation(conv.id).flatMap { isGroup =>
        storage.blockStreams(true)
        returning(processEvents(conv, isGroup, events)){ _ => storage.blockStreams(false) }
      }
    }
  }

  private[service] def processEvents(conv: ConversationData, isGroup: Boolean, events: Seq[MessageEvent]): Future[Set[MessageData]] = {
    verbose(l"processEvents: ${conv.id} isGroup:$isGroup ${events.map(_.from)}")

    val toProcess = events.filter {
      case GenericMessageEvent(_, _, _, msg) if GenericMessage.isBroadcastMessage(msg) => false
      case e => conv.cleared.forall(_.isBefore(e.time))
    }

    verbose(l"SYNC process events (${toProcess.size})")

    for {
      eventData     <- Future.traverse(toProcess)(localDataForEvent)
      modifications =  eventData.map(eald => createModifications(conv, isGroup, eald))
      msgs          <- getModifiedMessages(modifications)
      _             =  verbose(l"SYNC messages from events: ${msgs.map(m => m.id -> m.msgType)}")
      _             <- addUnexpectedMembers(conv.id, events)
      res           <- content.addMessages(conv.id, msgs)
      _             <- Future.traverse(modifications.flatMap(_.assets))(assets.save)
      _             <- updateLastReadFromOwnMessages(conv.id, msgs)
      _             <- deleteCancelled(modifications)
      _             <- applyRecalls(conv.id, toProcess)
      _             <- applyEdits(conv.id, toProcess)
      _             =  verbose(l"SYNC processing events finished")
    } yield res
  }

  private def checkReplyHashes(msgs: Seq[MessageData]) = {
    val (standard, quotes) = msgs.partition(_.quote.isEmpty)

    for {
      originals     <- storage.getMessages(quotes.flatMap(_.quote.map(_.message)): _*)
      hashes        <- replyHashing.hashMessages(originals.flatten)
      updatedQuotes =  quotes.map(q => q.quote match {
        case Some(QuoteContent(message, validity, hash)) if hashes.contains(message) =>
          val newValidity = hash.contains(hashes(message))
          if (validity != newValidity) q.copy(quote = Some(QuoteContent(message, newValidity, hash) )) else q
        case _ => q
      })
    } yield standard ++ updatedQuotes
  }

  private def localDataForEvent(event: MessageEvent) =
    for {
      message <- event match {
        case GenericMessageEvent(_, _, _, c) => storage.get(MessageId(c.messageId))
        case _                               => Future.successful(None)
      }
      asset <- message.flatMap(_.assetId) match {
        case Some(dId: DownloadAssetId) => downloadAssetStorage.find(dId)
        case _                          => Future.successful(None)
      }
    } yield EventAndLocalData(event, message, asset)

  private def getModifiedMessages(modifications: Seq[EventModifications]) =
    checkReplyHashes(modifications.collect { case m if m.message != MessageData.Empty => m.message })

  private def addUnexpectedMembers(convId: ConvId, events: Seq[MessageEvent]) = {
    val potentiallyUnexpectedMembers = events.filter {
      case e: MemberLeaveEvent if e.userIds.contains(e.from) => false
      case _ => true
    }.map(_.from).toSet
    if (potentiallyUnexpectedMembers.nonEmpty)
      convsService.addUnexpectedMembersToConv(convId, potentiallyUnexpectedMembers)
    else Future.successful(())
  }

  private def applyRecalls(convId: ConvId, toProcess: Seq[MessageEvent]) = {
    val recalls = toProcess collect {
      case GenericMessageEvent(_, time, from, msg @ GenericMessage(_, MsgRecall(_))) => (msg, from, time)
    }
    Future.traverse(recalls) {
      case (GenericMessage(id, MsgRecall(ref)), user, time) =>
        msgsService.recallMessage(convId, ref, user, MessageId(id.str), time, Message.Status.SENT)
    }
  }

  // TODO: handle mentions in case of MsgEdit
  private def applyEdits(convId: ConvId, toProcess: Seq[MessageEvent]) = {
    val edits = toProcess collect {
      case GenericMessageEvent(_, time, from, msg @ GenericMessage(_, MsgEdit(_, _))) => (msg, from, time)
    }
    RichFuture.traverseSequential(edits) {
      case (gm @ GenericMessage(_, MsgEdit(_, Text(_, _, _, _))), user, time) =>
        msgsService.applyMessageEdit(convId, user, time, gm)
    }
  }

  private def updatedAssets(id: Uid, content: Any, downloadAsset: Option[DownloadAsset]): Seq[(GeneralAsset, Option[GeneralAsset])] = {
    verbose(l"update asset for event: $id")

    content match {

      case asset: Asset if asset.hasUploaded =>
        val preview = Option(asset.preview).map(Asset2.create)
        val updatedDownloadAsset = downloadAsset.map(da => da.copy(preview = preview.map(_.id).orElse(da.preview), status = AssetStatus.Done))
        val asset2 = Asset2.create(updatedDownloadAsset.getOrElse(DownloadAsset.create(asset)), asset.getUploaded)

        verbose(l"Received asset v3 with preview")
        List((asset2, preview))

      case Text(_, _, linkPreviews, _) =>
        linkPreviews
          .collect { case lp if lp.image != null && lp.image.hasUploaded => lp }
          .map { lp =>
            val asset = Asset2.create(DownloadAsset.create(lp.image), lp.image.getUploaded)
            verbose(l"Received link preview asset: ${asset.id}")
            (asset, Option.empty[GeneralAsset])
          }

      case asset: Asset if DownloadAsset.getStatus(asset) == DownloadAssetStatus.Failed && asset.original.hasImage =>
        verbose(l"Received a message about a failed image upload: $id. Dropping")
        List.empty

      case asset: Asset if DownloadAsset.getStatus(asset) == DownloadAssetStatus.Cancelled =>
        verbose(l"Uploader cancelled asset: $id")
        val asset2 = downloadAsset.map(_.copy(status = DownloadAssetStatus.Cancelled)).getOrElse(DownloadAsset.create(asset))
        List((asset2, None))

      case asset: Asset =>
        val preview = Option(asset.preview).map(Asset2.create)
        val updatedDownloadAsset = downloadAsset.map(da => da.copy(preview = preview.map(_.id).orElse(da.preview), status = DownloadAsset.getStatus(asset)))
        val asset2 = updatedDownloadAsset.getOrElse(DownloadAsset.create(asset))
        verbose(l"Received asset without remote data - we will expect another update")
        List((asset2, preview))

      case Ephemeral(_, content) =>
        updatedAssets(id, content, downloadAsset)

      case _ =>
        List.empty
    }
  }

  private def createModifications(conv: ConversationData, isGroup: Boolean, eventAndLocalData: EventAndLocalData): EventModifications = {
    val convId = conv.id
    val event = eventAndLocalData.event

    def forceReceiptMode: Option[Int] = conv.receiptMode.filter(_ => isGroup)

    //v3 assets go here
    def content(id: MessageId, msgContent: Any, from: UserId, time: RemoteInstant, proto: GenericMessage): MessageData = msgContent match {
      case Text(text, mentions, links, quote) =>
        val (tpe, content) = MessageData.messageContent(text, mentions, links)
        verbose(l"MessageData content: $content")
        val quoteContent = quote.map(q => QuoteContent(MessageId(q.quotedMessageId), validity = false, Some(Sha256(q.quotedMessageSha256))))
        val messageData = MessageData(id, conv.id, tpe, from, content, time = time, localTime = event.localTime, protos = Seq(proto), quote = quoteContent, forceReadReceipts = forceReceiptMode)
          messageData.adjustMentions(false).getOrElse(messageData)
      case Knock() =>
        MessageData(id, conv.id, Message.Type.KNOCK, from, time = time, localTime = event.localTime, protos = Seq(proto), forceReadReceipts = forceReceiptMode)
      case Reaction(_, _) => MessageData.Empty
      case asset: Asset if asset.original == null =>
        MessageData(id, convId, Message.Type.UNKNOWN, from, time = time, localTime = event.localTime, protos = Seq(proto), forceReadReceipts = forceReceiptMode)
      case asset: Asset if DownloadAsset.getStatus(asset) == DownloadAssetStatus.Cancelled =>
        MessageData.Empty
      case asset: Asset if asset.original.hasVideo =>
        MessageData(id, convId, Message.Type.VIDEO_ASSET, from, time = time, localTime = event.localTime, protos = Seq(proto), forceReadReceipts = forceReceiptMode)
      case asset: Asset if asset.original.hasAudio =>
        MessageData(id, convId, Message.Type.AUDIO_ASSET, from, time = time, localTime = event.localTime, protos = Seq(proto), forceReadReceipts = forceReceiptMode)
      case asset: Asset if asset.original.hasImage =>
        MessageData(id, convId, Message.Type.ASSET, from, time = time, localTime = event.localTime, protos = Seq(proto), forceReadReceipts = forceReceiptMode)
      case _: Asset =>
        MessageData(id, convId, Message.Type.ANY_ASSET, from, time = time, localTime = event.localTime, protos = Seq(proto), forceReadReceipts = forceReceiptMode)
      case Location(_, _, _, _) =>
        MessageData(id, convId, Message.Type.LOCATION, from, time = time, localTime = event.localTime, protos = Seq(proto), forceReadReceipts = forceReceiptMode)
      case LastRead(_, _) => MessageData.Empty
      case Cleared(_, _) => MessageData.Empty
      case MsgDeleted(_, _) => MessageData.Empty
      case MsgRecall(_) => MessageData.Empty
      case MsgEdit(_, _) => MessageData.Empty
      case DeliveryReceipt(_) => MessageData.Empty
      case GenericContent.ReadReceipt(_) => MessageData.Empty
      case Calling(_) => MessageData.Empty
      case Ephemeral(expiry, ct) =>
        content(id, ct, from, time, proto).copy(ephemeral = expiry)
      case _ =>
        error(l"unexpected generic message content for id: $id")
        // TODO: this message should be processed again after app update, maybe future app version will understand it
        MessageData(id, conv.id, Message.Type.UNKNOWN, from, time = time, localTime = event.localTime, protos = Seq(proto))
    }

    /**
      * Creates safe version of incoming message.
      * Messages sent by malicious contacts might contain content intended to break the app. One example of that
      * are very long text messages, backend doesn't restrict the size much to allow for assets and group messages,
      * because of encryption it's also not possible to limit text messages there. On client such messages are handled
      * inline, and will cause memory problems.
      * We may need to do more involved checks in future.
      */
    def sanitize(msg: GenericMessage): GenericMessage = msg match {
      case GenericMessage(uid, t @ Text(text, mentions, links, quote)) if text.length > MaxTextContentLength =>
        GenericMessage(uid, Text(text.take(MaxTextContentLength), mentions, links.filter { p => p.url.length + p.urlOffset <= MaxTextContentLength }, quote, t.expectsReadConfirmation))
      case _ =>
        msg
    }

    val id = MessageId()
    event match {
      case ConnectRequestEvent(_, time, from, text, recipient, name, email) =>
        EventModifications(MessageData(id, convId, Message.Type.CONNECT_REQUEST, from, MessageData.textContent(text), recipient = Some(recipient), email = email, name = Some(name), time = time, localTime = event.localTime))
      case RenameConversationEvent(_, time, from, name) =>
        EventModifications(MessageData(id, convId, Message.Type.RENAME, from, name = Some(name), time = time, localTime = event.localTime))
      case MessageTimerEvent(_, time, from, duration) =>
        EventModifications(MessageData(id, convId, Message.Type.MESSAGE_TIMER, from, time = time, duration = duration, localTime = event.localTime))
      case MemberJoinEvent(_, time, from, userIds, firstEvent) =>
        EventModifications(MessageData(id, convId, Message.Type.MEMBER_JOIN, from, members = userIds.toSet, time = time, localTime = event.localTime, firstMessage = firstEvent))
      case ConversationReceiptModeEvent(_, time, from, 0) =>
        EventModifications(MessageData(id, convId, Message.Type.READ_RECEIPTS_OFF, from, time = time, localTime = event.localTime))
      case ConversationReceiptModeEvent(_, time, from, receiptMode) if receiptMode > 0 =>
        EventModifications(MessageData(id, convId, Message.Type.READ_RECEIPTS_ON, from, time = time, localTime = event.localTime))
      case MemberLeaveEvent(_, time, from, userIds) =>
        EventModifications(MessageData(id, convId, Message.Type.MEMBER_LEAVE, from, members = userIds.toSet, time = time, localTime = event.localTime))
      case OtrErrorEvent(_, time, from, IdentityChangedError(_, _)) =>
        EventModifications(MessageData(id, conv.id, Message.Type.OTR_IDENTITY_CHANGED, from, time = time, localTime = event.localTime))
      case OtrErrorEvent(_, time, from, otrError) =>
        EventModifications(MessageData(id, conv.id, Message.Type.OTR_ERROR, from, time = time, localTime = event.localTime))
      case GenericMessageEvent(_, time, from, proto) =>
        val sanitized @ GenericMessage(uid, msgContent) = sanitize(proto)
        val id = MessageId(uid.str)
        val assets = updatedAssets(uid, sanitized.getAsset, eventAndLocalData.asset)
        val message = content(id, msgContent, from, time, sanitized).copy(assetId = assets.headOption.map(_._1.id))
        EventModifications(message, assets)
      case _: CallMessageEvent =>
        EventModifications(MessageData.Empty)
      case _ =>
        warn(l"Unexpected event for addMessage: $event")
        EventModifications(MessageData.Empty)}
  }

  private def deleteCancelled(modifications: Seq[EventModifications]): Future[Unit] = {
    val toRemove = modifications.filter { m =>
      m.assetWithPreview.headOption match {
        case Some((asset: DownloadAsset, _)) => asset.status == DownloadAssetStatus.Cancelled
        case _ => false
      }
    }

    for {
      _ <- Future.traverse(toRemove.map(_.message))(msg => storage.remove(msg.id))
      _ <- Future.traverse(toRemove.flatMap(_.assets))(asset => assets.delete(asset.id))
    } yield ()
  }

  private def updateLastReadFromOwnMessages(convId: ConvId, msgs: Seq[MessageData]) =
    msgs.reverseIterator.find(_.userId == selfUserId).fold2(Future.successful(None), msg => convs.updateConversationLastRead(convId, msg.time))

  def addMessagesAfterVerificationUpdate(updates: Seq[(ConversationData, ConversationData)], convUsers: Map[ConvId, Seq[UserData]], changes: Map[UserId, VerificationChange]) =
    Future.traverse(updates) {
      case (prev, up) if up.verified == Verification.VERIFIED => msgsService.addOtrVerifiedMessage(up.id)
      case (prev, up) if prev.verified == Verification.VERIFIED =>
        verbose(l"addMessagesAfterVerificationUpdate with prev=${prev.verified} and up=${up.verified}")
        val convId = up.id
        val changedUsers = convUsers(convId).filter(!_.isVerified).flatMap { u => changes.get(u.id).map(u.id -> _) }
        val (users, change) =
          if (changedUsers.forall(c => c._2 == ClientAdded)) (changedUsers map (_._1), ClientAdded)
          else if (changedUsers.forall(c => c._2 == MemberAdded)) (changedUsers map (_._1), MemberAdded)
          else (changedUsers collect { case (user, ClientUnverified) => user }, ClientUnverified)

        val (self, other) = users.partition(_ == selfUserId)
        for {
          _ <- if (self.nonEmpty) msgsService.addOtrUnverifiedMessage(convId, Seq(selfUserId), change) else Future.successful(())
          _ <- if (other.nonEmpty) msgsService.addOtrUnverifiedMessage(convId, other, change) else Future.successful(())
        } yield ()
      case _ =>
        Future.successful(())
    }

}

object MessageEventProcessor {
  val MaxTextContentLength = 8192

  case class EventModifications(message: MessageData,
                                assetWithPreview: Seq[(GeneralAsset, Option[GeneralAsset])] = List.empty) {
    val assets: Seq[GeneralAsset] = assetWithPreview.flatMap {
      case (asset, Some(preview)) => List(asset, preview)
      case (asset, None) => List(asset)
    }
  }

  case class EventAndLocalData(event: MessageEvent, message: Option[MessageData], asset: Option[DownloadAsset])
}
