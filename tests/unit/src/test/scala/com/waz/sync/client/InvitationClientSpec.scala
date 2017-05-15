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
package com.waz.sync.client

import java.util.Locale

import com.waz.client.RegistrationClient
import com.waz.model._
import com.waz.service.BackendConfig
import com.waz.sync.client.InvitationClient.ConfirmedInvitation
import com.waz.threading.CancellableFuture
import com.waz.utils.wrappers.URI
import com.waz.znet.ContentEncoder.{ByteArrayRequestContent}
import com.waz.znet.Response.{HttpStatus, Status}
import com.waz.znet.ZNetClient.{EmptyAsyncClient, EmptyClient}
import com.waz.znet._
import org.json.JSONObject
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._
import org.threeten.bp.Instant

class InvitationClientSpec extends FeatureSpec with Matchers with Inside with ScalaFutures with RobolectricTests {

  feature("Invitation request encoding") {
    scenario("Invite contact by email") {
      inviteBy(Left(EmailAddress("meep@meow.me")))
      inside(mostRecentRequest.getBody) { case c: ByteArrayRequestContent if c.contentType == "application/json" =>
        val json = new JSONObject(new String(c.sourceData, "UTF-8"))
        Map(
          "email" -> "meep@meow.me",
          "invitee_name" -> "Meep",
          "inviter_name" -> "Moop",
          "message" -> "Hey!",
          "locale" -> "de-DE"
        ).foreach { case (k, v) => json.getString(k) shouldEqual v }
      }
    }

    scenario("Invite contact by phone") {
      inviteBy(Right(PhoneNumber("+301234567890")))
      inside(mostRecentRequest.getBody) { case c: ByteArrayRequestContent if c.contentType == "application/json" =>
        val json = new JSONObject(new String(c.sourceData, "UTF-8"))
        Map(
          "phone" -> "+301234567890",
          "invitee_name" -> "Meep",
          "inviter_name" -> "Moop",
          "message" -> "Hey!",
          "locale" -> "de-DE"
        ).foreach { case (k, v) => json.getString(k) shouldEqual v }
      }
    }
  }

  feature("Invitation response decoding") {
    scenario("Request was successful") {
      nextResponse = Response(HttpStatus(Status.Created), JsonObjectResponse(json))
      whenReady(inviteBy(Left(EmailAddress("meep@meow.me"))))(inside(_) {
        case Right(Right(invitation)) => invitation shouldEqual decoded
      })
    }

    scenario("Request was successful but body was empty") {
      nextResponse = emptyResponse.copy(headers = Response.createHeaders("Location" -> "/connections/1508c3bd-4566-4688-906e-93035971c193"))
      whenReady(inviteBy(Left(EmailAddress("meep@meow.me"))))(inside(_) { case Right(Left(u)) => u shouldEqual UserId("1508c3bd-4566-4688-906e-93035971c193") })
    }

    scenario("Request ends in redirect") {
      nextResponse = Response(HttpStatus(Status.SeeOther), EmptyResponse, Response.createHeaders("Location" -> "/connections/1508c3bd-4566-4688-906e-93035971c193"))
      whenReady(inviteBy(Left(EmailAddress("meep@meow.me"))))(inside(_) { case Right(Left(u)) => u shouldEqual UserId("1508c3bd-4566-4688-906e-93035971c193") })
    }

    scenario("Request ends in redirect with deprecated URI") {
      nextResponse = Response(HttpStatus(Status.SeeOther), EmptyResponse, Response.createHeaders("Location" -> "/self/connections/1508c3bd-4566-4688-906e-93035971c193"))
      whenReady(inviteBy(Left(EmailAddress("meep@meow.me"))))(inside(_) { case Right(Left(u)) => u shouldEqual UserId("1508c3bd-4566-4688-906e-93035971c193") })
    }
  }

  feature("Requesting invitation info") {
    scenario("Successful retrieval") {
      nextResponse = Response(HttpStatus(Status.Success), JsonObjectResponse(json))
      whenReady(registrationClient.getInvitationDetails(PersonalInvitationToken("#totallysecuretoken")).future)(inside(_) {
        case Right(invitation) => invitation shouldEqual decoded
      })
    }

    scenario("Retrieval fails") {
      nextResponse = Response(HttpStatus(Status.BadRequest, "invalid-invitation-code"), EmptyResponse)
      whenReady(registrationClient.getInvitationDetails(PersonalInvitationToken("#totallysecuretoken")).future)(inside(_) { case Left(e) => e.getCode shouldEqual Status.NotFound })
    }
  }

  @volatile var mostRecentRequest: Request[_] = _
  @volatile var nextResponse: Response = emptyResponse

  lazy val invitationClient = new InvitationClient(new EmptyClient() {
      override def apply[A](r: Request[A]): CancellableFuture[Response] = {
        mostRecentRequest = r
        CancellableFuture.successful(nextResponse)
      }
    }
  )

  lazy val registrationClient = new RegistrationClient(new EmptyAsyncClient {
    override def apply(uri: URI, request: Request[_]): CancellableFuture[Response] =
      CancellableFuture.successful(nextResponse)
  }, BackendConfig.StagingBackend)

  def inviteBy(method: Either[EmailAddress, PhoneNumber]) = invitationClient.postInvitation(Invitation(ContactId("meep"), method, "Meep", "Moop", "Hey!", Some(Locale.GERMANY))).future

  lazy val json = new JSONObject(
    """{
      |  "email": "meep@meow.me",
      |  "inviter": "30c1c16d-98c0-4112-9927-010e542b7787",
      |  "name": "Meep",
      |  "created_at": "2015-05-15T13:53:14.476Z",
      |  "id": "867d5a4e-b37c-47a1-be70-321fda2905c1"
      |}""".stripMargin)

  lazy val decoded = ConfirmedInvitation(
    InvitationId("867d5a4e-b37c-47a1-be70-321fda2905c1"),
    "Meep",
    Left(EmailAddress("meep@meow.me")),
    Instant.parse("2015-05-15T13:53:14.476Z"))

  lazy val emptyResponse = Response(HttpStatus(Status.Created))

  implicit val defaultPatience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))
}
