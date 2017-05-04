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
package com.waz.service.call

import com.waz.api.VoiceChannelState.{OTHER_CALLING, SELF_CALLING, SELF_CONNECTED, SELF_JOINING}
import com.waz.api.{NetworkMode, VoiceChannelState}
import com.waz.content.MembersStorage
import com.waz.model.ConversationData.ConversationType
import com.waz.model.{UserId, _}
import com.waz.service.call.AvsV3.ClosedReason.{AnsweredElsewhere, Normal, StillOngoing}
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.messages.MessagesService
import com.waz.service.{MediaManagerService, NetworkModeService}
import com.waz.specs.AndroidFreeSpec
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.wrappers.Context
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}
import org.threeten.bp.Instant

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

class CallingServiceSpec extends FeatureSpec with Matchers with MockFactory with BeforeAndAfterAll with AndroidFreeSpec {

  implicit val eventContext = EventContext.Implicits.global
  implicit val executionContext = new SerialDispatchQueue(name = "CallingServiceSpec")

  val context        = mock[Context]
  val avsMock        = mock[AvsV3]
  val flows          = mock[FlowManagerService]
  val mm             = mock[MediaManagerService]
  val network        = mock[NetworkModeService]
  val convs          = mock[ConversationsContentUpdater]
  val members        = mock[MembersStorage]
  val callLogService = mock[CallLogService]
  val messages       = mock[MessagesService]

  val selfUserId     = UserId("selfUserId")

  feature("Basics") {
    scenario("CallingService intialization") {
      val service = initCallingService()
      service.onReady(3)
      result(service.v3Available.head) shouldEqual true
      result(service.requestedCallVersion.head) shouldEqual 3
    }

    scenario("Incoming call 1:1 call goes through SELF_JOINING to become SELF_CONNECTED") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(), RConvId(), Some("1:1 Conv"), selfUserId, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SELF_JOINING))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SELF_CONNECTED && cur.others == Set(otherUser)))

      service.onIncomingCall(_1t1Conv.remoteId, otherUser, videoCall = false, shouldRing = true)
      (avsMock.answerCall _).expects(*, *).once().onCall { (_, _) =>
        service.onEstablishedCall(_1t1Conv.remoteId, otherUser)
      }
      service.startCall(_1t1Conv.id)
      result(checkpoint1.head)
      result(checkpoint2.head)
    }

    scenario("Incoming group call goes through SELF_JOINING to become SELF_CONNECTED") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), selfUserId, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SELF_JOINING))
      val checkpoint2 = callCheckpoint(service, _.contains(groupConv.id),
        _.exists(cur => cur.convId == groupConv.id && cur.state == SELF_CONNECTED && cur.others == Set(groupMember1, groupMember2)))

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)
      (avsMock.answerCall _).expects(*, *).once().onCall { (_, _) =>
        service.onEstablishedCall(groupConv.remoteId, groupMember1)
        service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      }
      service.startCall(groupConv.id)
      result(checkpoint1.head)
      result(checkpoint2.head)
    }

    scenario("Outgoing 1:1 call goes through SELF_CALLING to SELF_JOINING to SELF_CONNECTED") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(), RConvId(), Some("1:1 Conv"), selfUserId, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SELF_CALLING && cur.caller == selfUserId && cur.others == Set(otherUser)))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SELF_JOINING && cur.caller == selfUserId && cur.others == Set(otherUser)))
      val checkpoint3 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SELF_CONNECTED && cur.caller == selfUserId && cur.others == Set(otherUser)))

      (avsMock.startCall _).expects(*, *, *).once().returning(Future.successful(0))
      (members.getByConv _).expects(*).once().returning(Future.successful(IndexedSeq(
        ConversationMemberData(selfUserId, _1t1Conv.id),
        ConversationMemberData(otherUser, _1t1Conv.id)
      )))

      service.startCall(_1t1Conv.id)
      result(checkpoint1.head)

      service.onOtherSideAnsweredCall(_1t1Conv.remoteId)
      result(checkpoint2.head)

      service.onEstablishedCall(_1t1Conv.remoteId, otherUser)
      result(checkpoint3.head)
    }

    scenario("Outgoing group call goes through SELF_CALLING to SELF_JOINING to SELF_CONNECTED") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), selfUserId, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()

      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SELF_CALLING && cur.caller == selfUserId && cur.others == Set(groupMember1, groupMember2)))
      val checkpoint2 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SELF_JOINING && cur.caller == selfUserId && cur.others == Set(groupMember1, groupMember2)))
      val checkpoint3 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SELF_CONNECTED && cur.caller == selfUserId && cur.others == Set(groupMember1, groupMember2)))

      (avsMock.startCall _).expects(*, *, *).once().returning(Future.successful(0))
      (members.getByConv _).expects(*).once().returning(Future.successful(IndexedSeq(
        ConversationMemberData(selfUserId, groupConv.id),
        ConversationMemberData(groupMember1, groupConv.id),
        ConversationMemberData(groupMember2, groupConv.id)
      )))

      service.startCall(groupConv.id)
      result(checkpoint1.head)

      service.onOtherSideAnsweredCall(groupConv.remoteId)
      result(checkpoint2.head)

      //TODO which user from a group conversation gets passed down here?
      service.onEstablishedCall(groupConv.remoteId, groupMember1)
      service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))

      result(checkpoint3.head)
    }
  }

  feature("Ending calls") {
    scenario("Leave group call that will continue running in the background") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), selfUserId, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(_.state == SELF_CONNECTED))
      val checkpoint2 = callCheckpoint(service, _.contains(groupConv.id), _.isEmpty)

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)
      (avsMock.answerCall _).expects(*, *).once().onCall { (_, _) =>
        service.onEstablishedCall(groupConv.remoteId, groupMember1)
        service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      }
      service.startCall(groupConv.id)
      result(checkpoint1.head)

      (avsMock.endCall _).expects(*, *).once().onCall { (rId, isGroup) =>
        service.onClosedCall(StillOngoing, groupConv.remoteId, Instant.now, groupMember1)
      }
      service.endCall(groupConv.id) //avs won't call the ClosedHandler if a group call is still ongoing in the background
      result(checkpoint2.head)
    }

    scenario("Incoming group call answered on another device") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), selfUserId, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(_.state == OTHER_CALLING))
      val checkpoint2 = callCheckpoint(service, _.isEmpty, _.isEmpty)

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)
      result(checkpoint1.head)

      service.onClosedCall(AnsweredElsewhere, groupConv.remoteId, Instant.now, groupMember1)
      result(checkpoint2.head)
    }

    scenario("Reject incoming 1:1 call should remove it from activeCall, and then also from backgroundCalls after timeout") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(), RConvId(), Some("1:1 Conv"), selfUserId, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == OTHER_CALLING))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.isEmpty)
      val checkpoint3 = callCheckpoint(service, _.isEmpty, _.isEmpty)

      service.onIncomingCall(_1t1Conv.remoteId, otherUser, videoCall = false, shouldRing = true)
      result(checkpoint1.head)

      (avsMock.rejectCall _).expects(*, *).once().onCall { (_, _) =>
        service.onClosedCall(StillOngoing, _1t1Conv.remoteId, Instant.now, otherUser)
      }
      service.endCall(_1t1Conv.id)
      result(checkpoint2.head)

      service.onClosedCall(Normal, _1t1Conv.remoteId, Instant.now, otherUser)
      result(checkpoint3.head)
    }

    scenario("Current: Cancel outgoing group call should remove it from background and active calls") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), selfUserId, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()

      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SELF_CALLING && cur.caller == selfUserId && cur.others == Set(groupMember1, groupMember2)))
      val checkpoint2 = callCheckpoint(service, _.isEmpty, _.isEmpty)

      (avsMock.startCall _).expects(*, *, *).once().returning(Future.successful(0))
      (members.getByConv _).expects(*).once().returning(Future.successful(IndexedSeq(
        ConversationMemberData(selfUserId, groupConv.id),
        ConversationMemberData(groupMember1, groupConv.id),
        ConversationMemberData(groupMember2, groupConv.id)
      )))

      service.startCall(groupConv.id)
      result(checkpoint1.head)

      (avsMock.endCall _).expects(groupConv.remoteId, true).once().onCall { (_, _) =>
        service.onClosedCall(Normal, groupConv.remoteId, Instant.now, groupMember1)
      }
      service.endCall(groupConv.id)
      result(checkpoint2.head)
    }
  }

  feature("Simultaneous calls") {

    scenario("Receive incoming call while 1:1 call ongoing") {

      val ongoingUserId = UserId()
      val ongoingConv = ConversationData(ConvId(ongoingUserId.str), RConvId(ongoingUserId.str), Some("Ongoing Conv"), selfUserId, ConversationType.OneToOne)

      val incomingUserId = UserId()
      val incomingConv = ConversationData(ConvId(incomingUserId.str), RConvId(incomingUserId.str), Some("Incoming Conv"), selfUserId, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().onCall { rConvId: RConvId =>
        Future.successful(rConvId match {
          case ongoingConv.remoteId => Some(ongoingConv)
          case incomingConv.remoteId => Some(incomingConv)
          case _ => None
        })
      }
      (convs.convById _).expects(ConvId(ongoingUserId.str)).anyNumberOfTimes().returning(Future.successful(Some(ongoingConv)))

      val service = initCallingService()

      val checkpoint = callCheckpoint(service, _.contains(ongoingConv.id), cur => cur.exists(_.state == SELF_CONNECTED) && cur.exists(_.others.contains(ongoingUserId)))

      service.onIncomingCall(ongoingConv.remoteId, ongoingUserId, videoCall = false, shouldRing = true)
      (avsMock.answerCall _).expects(*, *).once().onCall { (_, _) =>
        service.onEstablishedCall(ongoingConv.remoteId, ongoingUserId)
      }
      service.startCall(ongoingConv.id)
      service.onIncomingCall(incomingConv.remoteId, incomingUserId, videoCall = false, shouldRing = false) //Receive the second call after first is established

      result(checkpoint.head)
    }

    scenario("With a background group call, receive a 1:1 call, finish it, and then still join the group call afterwards") {

      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), selfUserId, ConversationType.Group)

      val otoUser = UserId("otoUser")
      val otoConv = ConversationData(ConvId(otoUser.str), RConvId(otoUser.str), Some("1:1 Conv"), selfUserId, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().onCall { rConvId: RConvId =>
        Future.successful(rConvId match {
          case groupConv.remoteId => Some(groupConv)
          case otoConv.remoteId => Some(otoConv)
          case _ => None
        })
      }

      (convs.convById _).expects(*).anyNumberOfTimes().onCall { convId: ConvId =>
        Future.successful(convId match {
          case groupConv.id => Some(groupConv)
          case otoConv.id => Some(otoConv)
          case _ => None
        })
      }

      (avsMock.setVideoSendActive _).expects(otoConv.remoteId, false).anyNumberOfTimes()
      val service = initCallingService()

      //Checkpoint 1: Receive and reject a group call
      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.isEmpty)

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)
      (avsMock.rejectCall _).expects(*, *).anyNumberOfTimes().onCall { (_, _) =>
        service.onClosedCall(StillOngoing, groupConv.remoteId, Instant.now, groupMember1)
      }
      service.endCall(groupConv.id) //user rejects the group call

      result(checkpoint1.head)

      //Checkpoint 2: Receive and accept a 1:1 call
      val checkpoint2 = callCheckpoint(service,
        act => act.contains(groupConv.id) && act.contains(otoConv.id),
        _.exists(curr => curr.others.contains(otoUser) && curr.state == VoiceChannelState.SELF_CONNECTED))

      service.onIncomingCall(otoConv.remoteId, otoUser, videoCall = false, shouldRing = true)
      (avsMock.answerCall _).expects(*, *).once().onCall { (rId, _) =>
        service.onEstablishedCall(otoConv.remoteId, otoUser)
      }
      service.startCall(otoConv.id) //user accepts 1:1 call

      result(checkpoint2.head)

      val checkpoint3 = callCheckpoint(service, _.contains(groupConv.id), _.exists(curr => curr.others == Set(groupMember1, groupMember2) && curr.state == VoiceChannelState.SELF_CONNECTED))
      
      (avsMock.endCall _).expects(*, *).once().onCall { (rId, _) =>
        service.onClosedCall(Normal, otoConv.remoteId, Instant.now, otoUser)
      }
      service.endCall(otoConv.id)

      (avsMock.answerCall _).expects(*, *).once().onCall { (rId, _) =>
        service.onEstablishedCall(groupConv.remoteId, groupMember1)
        service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      }
      service.startCall(groupConv.id)

      result(checkpoint3.head)
    }
  }

  def callCheckpoint(service: CallingService, activeCheck: Map[ConvId, CallInfo] => Boolean, currentCheck: Option[CallInfo] => Boolean) =
    (for {
      active <- service.availableCalls
      current <- service.currentCall
    } yield (active, current)).filter { case (active, current) =>
      activeCheck(active) && currentCheck(current)
    }

  def signalTest[A](signal: Signal[A])(test: A => Boolean)(trigger: => Unit): Unit = {
    signal.disableAutowiring()
    trigger
    result(signal.filter(test).head)
  }

  def initCallingService(context:        Context                     = context,
                         self:           UserId                      = selfUserId,
                         avs:            AvsV3                       = avsMock,
                         convs:          ConversationsContentUpdater = convs,
                         members:        MembersStorage              = members,
                         flows:          FlowManagerService          = flows,
                         messages:       MessagesService             = messages,
                         media:          MediaManagerService         = mm,
                         callLogService: CallLogService              = callLogService,
                         network:        NetworkModeService          = network) = {
    val initPromise = Promise[Unit]()
    (context.startService _).expects(*).anyNumberOfTimes().returning(true)
    (avs.available _).expects().once().returning(Future.successful({}))
    (flows.flowManager _).expects().once().returning(None)
    (media.mediaManager _).expects().once().returning(None)
    (callLogService.addEstablishedCall _).expects(*, *, *).anyNumberOfTimes().returning(Future.successful({}))
    (messages.addMissedCallMessage(_:RConvId, _:UserId, _:Instant)).expects(*, *, *).anyNumberOfTimes().returning(Future.successful(None))
    (messages.addMissedCallMessage(_:ConvId, _:UserId, _:Instant)).expects(*, *, *).anyNumberOfTimes().returning(Future.successful(None))
    (messages.addSuccessfulCallMessage _).expects(*, *, *, *).anyNumberOfTimes().returning(Future.successful(None))
    (network.networkMode _).expects().once().returning(Signal.empty[NetworkMode])
    (avs.init _).expects(*).once().onCall{ service: CallingService =>
      initPromise.success({})
      initPromise.future
    }
    val service = new DefaultCallingService(context, self, avs, convs, members, null, flows, messages, media, null, callLogService, network, null)
    result(initPromise.future)
    service
  }
}
