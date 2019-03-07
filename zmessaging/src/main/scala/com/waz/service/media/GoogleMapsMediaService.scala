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
import com.waz.model.AssetMetaData.Image.Tag.Medium
import com.waz.model._
import com.waz.service.media.RichMediaContentParser.GoogleMapsLocation
import com.waz.sync.client.GoogleMapsClient

object GoogleMapsMediaService extends DerivedLogTag {

  val MaxImageWidth = 640 // free maps api limitation
  val ImageDimensions = Dim2(MaxImageWidth, MaxImageWidth * 3 / 4)
  val PreviewWidth = 64

  def mapImageAsset(id: AssetId, loc: com.waz.api.MessageContent.Location, dimensions: Dim2 = ImageDimensions): AssetData =
    mapImageAsset(id, GoogleMapsLocation(loc.getLatitude.toString, loc.getLongitude.toString, loc.getZoom.toString), dimensions)

  def mapImageAsset(id: AssetId, location: GoogleMapsLocation, dimensions: Dim2): AssetData = {

    val mapWidth = math.min(MaxImageWidth, dimensions.width)
    val mapHeight = mapWidth * dimensions.height / dimensions.width

    val mediumPath = GoogleMapsClient.getStaticMapPath(location, mapWidth, mapHeight)

    //TODO Dean see if preview is still needed?
    AssetData(mime = Mime.Image.Png, metaData = Some(AssetMetaData.Image(Dim2(mapWidth, mapHeight), Medium)), proxyPath = Some(mediumPath))
  }
}
