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

import com.waz.api.ProvisionedApiSpec
import com.waz.testutils.DefaultPatienceConfig
import com.waz.testutils.Matchers._
import com.waz.threading.Threading
import com.waz.utils.wrappers.URI
import com.waz.znet.Response.SuccessStatus
import com.waz.znet._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class OpenGraphClientSpec extends FeatureSpec with Matchers with ProvisionedApiSpec with ScalaFutures with DefaultPatienceConfig {

  val provisionFile = "/one_conversation.json"

  implicit val timeout = 10.seconds: Timeout
  implicit lazy val ec = Threading.Background

  def client = zmessaging.openGraphClient

  scenario("Fetch header of large website, should stop download early") {
    lazy val LargeWebsiteLink = URI.parse("http://unicode.org/emoji/charts/full-emoji-list.html")

    val resp = zmessaging.zNetClient(new Request[Unit](baseUri = Some(LargeWebsiteLink), requiresAuthentication = false, decoder = Some(OpenGraphClient.ResponseDecoder))).await()

    resp should beMatching({
      case Response(SuccessStatus(), StringResponse(html), _) if html.length < 10 * 1024 => info(s"response size: ${html.length}")
    })
  }

  scenario("Load open graph data from wire.com") {
    val resp = client.loadMetadata(URI.parse("http://www.wire.com")).await()
    resp shouldBe 'right
    resp.right.get shouldBe defined
    val data = resp.right.get.get
    info(s"loaded data: $data")
  }

  scenario("Load open graph data from twitter") {
    val resp = client.loadMetadata(URI.parse("https://twitter.com/SoundCloud/status/750403925585432576")).await()
    resp shouldBe 'right
    resp.right.get shouldBe defined
    val data = resp.right.get.get
    info(s"loaded data: $data")
  }

  scenario("Load open graph data from nytimes") {
    val resp = client.loadMetadata(URI.parse("http://nytimes.com/2016/07/11/business/front-page-editorials-aim-to-soothe-the-grief-stricken.html")).await()
    resp shouldBe 'right
    resp.right.get shouldBe defined
    val data = resp.right.get.get
    info(s"loaded data: $data")
  }
}
