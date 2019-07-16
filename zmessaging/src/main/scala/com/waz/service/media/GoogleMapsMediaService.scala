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
package com.waz.service.media

import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model._
import com.waz.service.media.RichMediaContentParser.GoogleMapsLocation
import com.waz.sync.client.GoogleMapsClient
import com.waz.utils.wrappers.URI

object GoogleMapsMediaService extends DerivedLogTag {

  val MaxImageWidth = 640 // free maps api limitation
  val ImageDimensions = Dim2(MaxImageWidth, MaxImageWidth * 3 / 4)
  val PreviewWidth = 64

  def getImagePath(loc: com.waz.api.MessageContent.Location, dimensions: Dim2 = ImageDimensions): URI =
    getImagePath(GoogleMapsLocation(loc.getLatitude.toString, loc.getLongitude.toString, loc.getZoom.toString), dimensions)

  def getImagePath(location: GoogleMapsLocation, dimensions: Dim2): URI = {
    val mapWidth = math.min(MaxImageWidth, dimensions.width)
    val mapHeight = mapWidth * dimensions.height / dimensions.width
    GoogleMapsClient.getStaticMapPath(location, mapWidth, mapHeight)
  }
}
