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

import java.net.URLEncoder

import com.waz.service.media.RichMediaContentParser.GoogleMapsLocation
import com.waz.utils.wrappers.URI

object GoogleMapsClient {
  val StaticMapsPathBase = "https://maps.googleapis.com/maps/api/staticmap"

  def getStaticMapPath(location: GoogleMapsLocation, width: Int, height: Int): URI = {
    val center = URLEncoder.encode(s"${location.x},${location.y}", "utf8")
    val zoom = URLEncoder.encode(location.zoom, "utf8")
    URI.parse(s"$StaticMapsPathBase?center=$center&zoom=$zoom&size=${width}x$height")
  }
}
