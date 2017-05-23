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

import com.koushikdutta.async.ByteBufferList
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.threading.CancellableFuture
import com.waz.utils.wrappers.URI
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet.Response._
import com.waz.znet.ResponseConsumer.{ConsumerState, EmptyResponseConsumer, StringConsumer}
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet._
import org.json.JSONObject

class OpenGraphClient(netClient: ZNetClient) {
  import OpenGraphClient._
  import com.waz.threading.Threading.Implicits.Background

  def loadMetadata(uri: URI): ErrorOrResponse[Option[OpenGraphData]] = {

    def load(uri: URI, cookie: Map[String, String]): ErrorOrResponse[Option[OpenGraphData]] = {

      val headers = Map(
        AsyncClient.UserAgentHeader -> DesktopUserAgent,  // using empty User-Agent to avoid getting mobile website version
        "Cookie" -> cookie.map { case (k, v) => s"$k=$v" } .mkString("; ")
      )

      val req = Request[Unit](Request.HeadMethod, baseUri = Some(uri), decoder = Some(ResponseDecoder), requiresAuthentication = false, headers = headers, followRedirect = false)
      netClient(req) flatMap {
        case (Response(SuccessStatus(), StringResponse(_), _) | Response(SuccessStatus(), EmptyResponse, _)) => // this means that ResponseDecoder accepted the content type, we can proceed with GET
          netClient.withErrorHandling("loadOpenGraph", req.copy(httpMethod = Request.GetMethod)) {
            case Response(SuccessStatus(), OpenGraphDataResponse(data), _) => Some(data)
            case Response(SuccessStatus(), _, _) => None
          }
        case Response(HttpStatus(Status.SeeOther | Status.MovedTemporarily | Status.MovedPermanently, _), _, hs) =>
          hs("Location") match {
            case Some(location) =>
              val cs = hs("Set-Cookie").fold(Map.empty[String, String]) { str =>
                CookiePattern.findAllMatchIn(str).map { m => m.group(1) -> m.group(2) } .toMap
              }
              load(URI.parse(location), cookie ++ cs)
            case None =>
              CancellableFuture successful Left(ErrorResponse.internalError("unexpected response, redirect without location header"))
          }
        case Response(SuccessStatus(), _, _) =>
          verbose(s"loadMetadata(), HEAD indicates unsupported content type for $uri")
          CancellableFuture successful Right(None)
        case Response(ClientErrorStatus(), _, _) =>
          verbose(s"loadMetadata(), HEAD request failed with client error for $uri")
          CancellableFuture successful Right(None)
        case Response(ConnectionError(msg), _, _) =>
          verbose(s"loadMetadata(), HEAD request failed with connection error [$msg] for $uri")
          // either we are offline or uri doesn't point to existing website,
          // it's hard to distinguish between those cases, so we will just ignore the preview to be safe
          CancellableFuture successful Right(None)
        case resp @ Response(status, _, _) =>
          warn(s"loadMetadata(), unexpected response to HEAD: $resp")
          CancellableFuture successful Left(ErrorResponse(status.status, status.msg, "unexpected"))
      }
    }

    load(uri, Map.empty)
  }
}

object OpenGraphClient {
  private implicit val tag: LogTag = logTagFor[OpenGraphClient]
  val MaxHeaderLength = 16 * 1024 // maximum amount of data to load from website
  val DesktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36"
  val CookiePattern = """([^=]+)=([^\;]+)""".r

  case class OpenGraphData(title: String, description: String, image: Option[URI], tpe: String, permanentUrl: Option[URI])

  case class HtmlHeaderConsumer(len: Long) extends StringConsumer(math.min(len, MaxHeaderLength)) {
    def isDone = data.size() >= MaxHeaderLength || data.toString("utf8").toLowerCase.contains("""</head>""")

    override def consume(bb: ByteBufferList): ConsumerState = {
      super.consume(bb)

      if (isDone) ConsumerState.Done else ConsumerState.Default
    }
  }

  object ResponseDecoder extends ResponseBodyDecoder {
    override def apply(contentType: String, contentLength: Long) =
      if (contentType.toLowerCase().contains("text/html")) HtmlHeaderConsumer(contentLength)
      else {
        verbose(s"dropping response with content type: $contentType, len: $contentLength")
        EmptyResponseConsumer
      }
  }

  object OpenGraphData extends ((String, String, Option[URI], String, Option[URI]) => OpenGraphData) {
    val Empty = OpenGraphData("", "", None, "", None)

    implicit object Decoder extends JsonDecoder[OpenGraphData] {
      import JsonDecoder._

      override def apply(implicit js: JSONObject): OpenGraphData = OpenGraphData('title, 'description, decodeOptString('image).map(URI.parse), 'tpe, decodeOptString('url).map(URI.parse))
    }

    implicit object Encoder extends JsonEncoder[OpenGraphData] {
      override def apply(v: OpenGraphData): JSONObject = JsonEncoder { o =>
        o.put("title", v.title)
        o.put("description", v.description)
        v.image foreach { uri => o.put("image", uri.toString) }
        v.permanentUrl foreach { uri => o.put("url", uri.toString) }
        o.put("tpe", v.tpe)
      }
    }
  }

  object OpenGraphDataResponse {
    val Title = "title"
    val Image = "image"
    val Type = "type"
    val Url = "url"
    val Description = "description"

    val PropertyPrefix = """^(og|twitter):(.+)""".r
    val MetaTag = """<\s*meta\s+[^>]+>""".r
    val Attribute = """(\w+)\s*=\s*("|')([^"']+)("|')""".r

    val TitlePattern = """<title[^>]*>(.*)</title>""".r

    val BaseTypes = Seq("", "article", "website", "product", "video.movie", "video.tv_show")
    val KnownSpecificTypes = Seq("instapp:photo", "ebay-objects:item", "tumblr-feed:tumblelog")
    val AcceptedTypes = BaseTypes ++ KnownSpecificTypes // will ignore other types for now

    def unapply(body: StringResponse): Option[OpenGraphData] = {

      def htmlTitle = TitlePattern.findFirstMatchIn(body.value).map(_.group(1))

      val ogMeta = MetaTag.findAllIn(body.value) .flatMap { meta =>
        val attrs = Attribute.findAllMatchIn(meta) .map { m => m.group(1).toLowerCase -> m.group(3) } .toMap
        val name = attrs.get("property").orElse(attrs.get("name"))
        val iter = PropertyPrefix.findAllMatchIn(name.getOrElse("")).map(a => a.group(2).toLowerCase -> attrs.getOrElse("content",""))
        if (iter.hasNext)
          Some(iter.next())
        else
          None
      } .toMap

      if ((ogMeta.contains(Title) || ogMeta.contains(Image)) && ogMeta.get(Type).forall { tpe => AcceptedTypes.contains(tpe.toLowerCase) }) {
        Some(OpenGraphData(ogMeta.get(Title).orElse(htmlTitle).getOrElse(""), ogMeta.getOrElse(Description, ""), ogMeta.get(Image).map(URI.parse), ogMeta.getOrElse(Type, ""), ogMeta.get(Url).map(URI.parse)))
      } else None
    }
  }
}
