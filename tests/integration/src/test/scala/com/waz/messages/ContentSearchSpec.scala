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
package com.waz.messages

import com.waz.api.MessageContent.Text
import com.waz.api._
import com.waz.model.ConvId
import com.waz.service.RemoteZmsSpec
import com.waz.testutils.Matchers._
import com.waz.testutils.UnreliableAsyncClient
import org.scalatest._
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.ExecutionContext.Implicits.global

class ContentSearchSpec extends PropSpec with Matchers with TableDrivenPropertyChecks with BeforeAndAfterAll with ProvisionedApiSpec with RemoteZmsSpec {
  test =>
  override val provisionFile = "/two_users_connected.json"

  lazy val auto2 = createRemoteZms()

  lazy val convs = api.getConversations
  lazy val conv = convs.get(0)
  lazy val msgs = conv.getMessages
  lazy val messageIndexStore = zmessaging.messagesIndexStorage

  override lazy val testClient = new UnreliableAsyncClient

  override def beforeAll(): Unit = {
    super.beforeAll()
    withDelay(msgs should have size 1)
    awaitUiFuture(auto2.login(provisionedEmail("auto2"), "auto2_pass"))
  }

  def deleteLastMessage(): Unit = {
    val msg = msgs.getLastMessage
    msg.delete()
    withDelay {
      msgs should have size 1
      msg.isDeleted shouldEqual true
    }
  }

  property("Search result for text message") {
    val examples =
      Table(
        ("Message", "Query", "Result"),
        ("aabb", "aabb", Some("aabb")),
        ("aabb", "a", Some("aabb")),
        ("aabb", "bb", None),
        ("aabb", "bb", None),
        ("bb  aa", "aa", Some("bb  aa")),
        ("aa  bb", "aa bb", Some("aa  bb")),
        ("bb\naa", "aa", Some("bb\naa")),
        ("bb\n\naa", "aa", Some("bb\n\naa")),
        ("aa aa aa", "aa", Some("aa aa aa")),
        ("aa bb aa", "aa", Some("aa bb aa")),
        ("aa cc", "aa bb", None),
        ("aa aa aa", "aa aa", Some("aa aa aa")),
        ("a aa", " a", Some("a aa")),
        ("a aa", "a ", Some("a aa")),
        ("aa.bb", "bb", Some("aa.bb")),
        ("aa...bb", "bb", Some("aa...bb")),
        ("aa.bb", "aa", Some("aa.bb")),
        ("aa...bb", "aa", Some("aa...bb")),
        ("aa-bb", "aa-bb", Some("aa-bb")),
        ("aa-bb", "aa bb", Some("aa-bb")),
        ("aa-bb", "aa", Some("aa-bb")),
        ("aa-bb", "bb", Some("aa-bb")),
        ("aa/bb", "aa", Some("aa/bb")),
        ("aa/bb", "bb", Some("aa/bb")),
        ("aa:bb", "aa", Some("aa:bb")),
        ("aa:bb", "bb", Some("aa:bb")),
        ("aa 11:45 am bb", "11:45", Some("aa 11:45 am bb")),
        ("bb\uD83D\uDE0A", "\uD83D\uDE0A", None),
        ("@peter", "peter", Some("@peter")),
        ("rené", "Rene", Some("rené")),
        ("Håkon", "håkon", Some("Håkon")),
        ("bb бб bb", "бб", Some("bb бб bb")),
        ("bb бб bb", "bb", Some("bb бб bb")),
        ("苹果", "苹果", Some("苹果")),
        ("苹果", "Ping", Some("苹果")),
        ("<8000 x a's>", "<8000 x a's>", Some("<8000 x a's>")),
        ("aa \uD83D\uDE0A\uD83D\uDE0A bb", "\uD83D\uDE0A\uD83D\uDE0A", Some("aa \uD83D\uDE0A\uD83D\uDE0A bb"))
      )

    forAll(examples) { (message: String, query: String, result: Option[String]) =>
      conv.sendMessage(new Text(message))
      withDelay(msgs should have size 2)
      messageIndexStore.searchText(ContentSearchQuery(query), Some(ConvId(conv.getId))) map { cursor =>
        result match {
          case Some(_) =>
            cursor should have size 1
            Some(cursor.apply(0).message.contentString)
          case None =>
            cursor should have size 0
            None
        }
      } should eventually(be(result))
      deleteLastMessage()
    }
  }

}
