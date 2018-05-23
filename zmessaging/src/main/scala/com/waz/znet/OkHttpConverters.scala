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

import com.waz.znet.ContentEncoder.{ByteArrayRequestContent, EmptyRequestContent, RequestContent}
import okhttp3.RequestBody
import okhttp3.internal.Util
import okhttp3.{MediaType => OkMediaType, Headers => OkHeaders, Request => OkRequest, RequestBody => OkRequestBody, Response => OkResponse, WebSocket => OkWebSocket}

import scala.collection.JavaConverters._

object OkHttpConverters {

  def convertHttpMethod(method: HttpRequest2.Method): String = {
    import HttpRequest2.Method._
    method match {
      case Get      => "GET"
      case Post     => "POST"
      case Put      => "PUT"
      case Patch    => "PATCH"
      case Delete   => "DELETE"
      case Head     => "HEAD"
    }
  }

  def convertMediaType(mediatype: String): OkMediaType = {
    OkMediaType.parse(mediatype)
  }

  def convertHttpRequestBody(requestContent: RequestContent): Option[OkRequestBody] = {
    requestContent match {
      case EmptyRequestContent => None
      case content: ByteArrayRequestContent =>
        Some(OkRequestBody.create(convertMediaType(content.contentType), content.data))
      case _ => None
    }
  }

  def convertHttpRequest(request: HttpRequest2): OkRequest = {
    new OkRequest.Builder()
      .url(request.url)
      .method(convertHttpMethod(request.httpMethod), convertHttpRequestBody(request.getBody).orNull)
      .headers(OkHeaders.of(request.headers.asJava))
      .build()
  }

}
