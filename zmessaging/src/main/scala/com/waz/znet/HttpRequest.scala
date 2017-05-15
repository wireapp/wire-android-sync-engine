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
package com.waz.znet

import com.koushikdutta.async.http._
import com.waz.utils.wrappers.{AndroidURI, URI}
import com.waz.znet.ContentEncoder.{EmptyRequestContent, RequestContent}

import scala.concurrent.duration._
import scala.collection.JavaConverters._

trait HttpRequest {
  def absoluteUri: Option[URI]
  def httpMethod: String
  def getBody: RequestContent
  def headers: Map[String, String]
  def followRedirect: Boolean
  def timeout: FiniteDuration
}

class KoushiHttpRequest(val req: AsyncHttpRequest) extends HttpRequest {
  override val absoluteUri: Option[URI] = Some(new AndroidURI(req.getUri()))
  override val httpMethod: String = req.getMethod()
  override val getBody: RequestContent = EmptyRequestContent // TODO
  override val headers: Map[String, String] = {
    val m = req.getHeaders().getMultiMap()
    m.keySet().asScala.toSet[String].map(k => (k -> m.getString(k))).toMap
  }
  override val followRedirect: Boolean = req.getFollowRedirect()
  override val timeout: FiniteDuration = req.getTimeout().millis
}

object HttpRequest {
  import scala.language.implicitConversions

  def apply(req: AsyncHttpRequest): HttpRequest = new KoushiHttpRequest(req)

  implicit def fromKoushi(req: AsyncHttpRequest): HttpRequest = apply(req)
  implicit def toKoushi(req: HttpRequest): AsyncHttpRequest = req match {
    case wrapper: KoushiHttpRequest => wrapper.req
    case _ => throw new IllegalArgumentException(s"Expected Koushikdutta's AsyncHttpRequest, but tried to unwrap: $req")
  }
}

trait RequestWorker {
  def processRequest(uri: URI, method: String, body: RequestContent, headers: Map[String, String], followRedirect: Boolean, timeout: FiniteDuration): HttpRequest

  def processRequest(uri: URI, req: HttpRequest): HttpRequest = processRequest(uri, req.httpMethod, req.getBody, req.headers, req.followRedirect, req.timeout)
  def processRequest(req: HttpRequest): HttpRequest =
    processRequest(req.absoluteUri.getOrElse(throw new IllegalArgumentException("URI not specified")), req)
}

class KoushiRequestWorker extends RequestWorker {
  override def processRequest(uri: URI, method: String, body: RequestContent, headers: Map[String, String], followRedirect: Boolean, timeout: FiniteDuration): HttpRequest = {
    val r = new AsyncHttpRequest(URI.unwrap(uri.normalizeScheme), method)
    r.setTimeout(timeout.toMillis.toInt)
    r.setFollowRedirect(followRedirect)
    r.getHeaders.set(AsyncClient.UserAgentHeader, AsyncClient.userAgent())
    headers.foreach(p => r.getHeaders.set(p._1, p._2.trim))
    new KoushiHttpRequest(body(r))
  }
}