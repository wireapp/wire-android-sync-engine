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

import android.net.Uri
import com.waz.ZLog._
import com.waz.api.MediaProvider
import com.waz.api.impl.ErrorResponse
import com.waz.model.AssetData
import com.waz.model.messages.media.MediaAssetData.{MediaWithImages, Thumbnail}
import com.waz.model.messages.media.{ArtistData, MediaAssetData, PlaylistData, TrackData}
import com.waz.service.media.SpotifyMediaService.{AlbumId, PlaylistId, SpotifyId, TrackId}
import com.waz.sync.client.OAuth2Client._
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.utils.wrappers.URI
import com.waz.znet.JsonObjectResponse
import com.waz.znet.Response.SuccessHttpStatus
import com.waz.znet._
import com.waz.znet.ZNetClient.ErrorOr
import org.json.JSONObject
import org.threeten.bp.Duration

import scala.concurrent.Future
import scala.concurrent.duration._

case class SpotifyClientId(id: String) extends AnyVal

// TODO at some point, we probably should support paging of long playlists, too, but for now, let's just work on supporting playlists at all

class SpotifyClient(netClient: ZNetClient, val clientId: SpotifyClientId) {
  import SpotifyClient._

  import Threading.Implicits.Background

  private val oauth2 = new OAuth2Client(netClient)(AppInfo(ClientId(clientId.id), TokenPath, RedirectUri))

  def connectWithCode(code: AuthorizationCode): ErrorOr[RefreshToken] = oauth2.getTokensFromCode(code)

  def clearAccessToken(): Unit = oauth2.clearAccessToken()

  def refreshAccessToken(refresh: Option[RefreshToken]): Future[Option[AccessToken]] = oauth2.freshAccessToken(refresh) map (_.right.toOption)

  def isPremiumAccount(refresh: RefreshToken): ErrorOr[Boolean] = oauth2.withFreshToken(Some(refresh)) { token =>
    netClient(get(url = MeUri, headers = oauth2.bearerHeader(token))).future map {
      case Response(SuccessHttpStatus(), IsPremiumResponse(premium), _) => Right(premium)
      case other => Left(ErrorResponse(other.status.status, s"Unexpected response when retrieving user profile: $other", "unexpected-spotify-response"))
    }
  }

  def getMedia(id: SpotifyId, refresh: Option[RefreshToken]): ErrorOr[MediaWithImages[MediaAssetData]] = oauth2.withFreshToken(refresh) { token =>
    netClient(get(url = mediaUri(id, authenticated = refresh.isDefined), headers = oauth2.bearerHeader(token))).future .map (response => responseHandler(id) .andThen (Right(_)) .orElse[Response, Either[ErrorResponse, MediaWithImages[MediaAssetData]]] {
      case other => Left(ErrorResponse(other.status.status, s"Unexpected response when retrieving media $id: $other", "unexpected-spotify-response"))
    } apply response)
  }
}

object SpotifyClient {
  import JsonDecoder._

  val domainNames = Set("open.spotify.com", "play.spotify.com")

  implicit val logTag = logTagFor[SpotifyClient]

  private val Base = "https://api.spotify.com/v1"
  val MeUri = uri(Base)(_ / "me")

  val TokenPath = "/proxy/spotify/api/token"
  val RedirectUri = Uri.parse("wire://spotify")

  private def mediaUri(id: SpotifyId, authenticated: Boolean): URI = withMarket(id match {
    case TrackId(track)       => uri(Base)(_ / "tracks" / track)
    case PlaylistId(user, pl) => uri(Base)(_ / "users" / user / "playlists" / pl)
    case AlbumId(album)       => uri(Base)(_ / "albums" / album)
  }, authenticated)

  private def responseHandler(id: SpotifyId): PartialFunction[Response, MediaWithImages[MediaAssetData]] = id match {
    case TrackId(_)       => { case Response(SuccessHttpStatus(), TrackResponse(track), _) => track }
    case PlaylistId(_, _) => { case Response(SuccessHttpStatus(), PlaylistResponse(pl), _) => pl }
    case AlbumId(_)       => { case Response(SuccessHttpStatus(), AlbumResponse(album), _) => album }
  }

  private def withMarket(u: URI, authenticated: Boolean): URI = if (authenticated) uri(u)(_ :? ("market", "from_token")) else u

  private def get(url: URI, headers: Map[String, String]) = Request[Unit](httpMethod = Request.GetMethod, baseUri = Option(url), requiresAuthentication = false, headers = headers)

  object IsPremiumResponse extends SpotifyJsonObjectResponse[Boolean] {
    override def fromJson(implicit js: JSONObject): Option[Boolean] = Some(js.has("product") && js.getString("product") == "premium")
  }

  lazy val TrackResponse: SpotifyJsonObjectResponse[MediaWithImages[TrackData]] = new SpotifyJsonObjectResponse[MediaWithImages[TrackData]] {
    override def fromJson(implicit js: JSONObject): Option[MediaWithImages[TrackData]] = Some(TrackDecoder(js))
  }

  implicit lazy val TrackDecoder: JsonDecoder[MediaWithImages[TrackData]] = new JsonDecoder[MediaWithImages[TrackData]] {
    override def apply(implicit js: JSONObject): MediaWithImages[TrackData] = {
      val artwork = decodeArtwork(js)

      MediaWithImages(TrackData(
        provider = MediaProvider.SPOTIFY,
        title = 'name,
        artist = Some(decodeArtist(js)),
        linkUrl = js.getJSONObject("external_urls").getString("spotify"),
        artwork = artwork map (_.id),
        duration = Some(Duration.ofMillis('duration_ms)),
        streamable = 'is_playable,
        streamUrl = 'uri,
        previewUrl = 'preview_url,
        expires = MediaAssetData.expiryAfter(1.day)), artwork.toSet) // artwork urls may expire after a day according to spotify api docs
    }
  }

  lazy val PlaylistResponse: SpotifyJsonObjectResponse[MediaWithImages[PlaylistData]] = new SpotifyJsonObjectResponse[MediaWithImages[PlaylistData]] {
    override def fromJson(implicit js: JSONObject): Option[MediaWithImages[PlaylistData]] = {
      val (tracks, trackImages) = MediaAssetData.extractImageAssets(arrayColl[Option[Item], Vector](js.getJSONObject("tracks").getJSONArray("items")).flatten map (_.track))
      val duration = tracks.flatMap(_.duration).foldLeft(Duration.ZERO) { case (a, b) => a plus b }
      val user = Option(js.optJSONObject("owner")) map (decodeUser(_))
      val artwork = decodeThumbnails(js)

      Some(MediaWithImages(PlaylistData(
        provider = MediaProvider.SPOTIFY,
        title = 'name,
        artist = user map (_._1),
        linkUrl = js.getJSONObject("external_urls").getString("spotify"),
        artwork = artwork map (_.id),
        duration = Some(duration),
        tracks = tracks,
        expires = MediaAssetData.expiryAfter(1.day)), trackImages ++ user.flatMap(_._2).toSet ++ artwork.toSet)) // artwork urls may expire after a day according to spotify api docs
    }
  }

  lazy val AlbumResponse: SpotifyJsonObjectResponse[MediaWithImages[PlaylistData]] = new SpotifyJsonObjectResponse[MediaWithImages[PlaylistData]] {
    override def fromJson(implicit js: JSONObject): Option[MediaWithImages[PlaylistData]] = {
      val (tracks, trackImages) = MediaAssetData.extractImageAssets(arrayColl[MediaWithImages[TrackData], Vector](js.getJSONObject("tracks").getJSONArray("items")))
      val duration = tracks.flatMap(_.duration).foldLeft(Duration.ZERO) { case (a, b) => a plus b }
      val artwork = decodeThumbnails(js)

      Some(MediaWithImages(PlaylistData(
        provider = MediaProvider.SPOTIFY,
        title = 'name,
        artist = Some(decodeArtist(js)),
        linkUrl = js.getJSONObject("external_urls").getString("spotify"),
        artwork = artwork map (_.id),
        duration = Some(duration),
        tracks = tracks,
        expires = MediaAssetData.expiryAfter(1.day)), trackImages ++ artwork.toSet)) // artwork urls may expire after a day according to spotify api docs
    }
  }

  case class Item(track: MediaWithImages[TrackData]) extends AnyVal
  case class Artist(name: String) extends AnyVal
  case class Image(width: Int, height: Int, url: String)

  implicit lazy val ItemDecoder: JsonDecoder[Option[Item]] = new JsonDecoder[Option[Item]] {
    override def apply(implicit js: JSONObject): Option[Item] = Option(js.optJSONObject("track")) map { t => Item(track = TrackDecoder(t)) }
  }

  implicit lazy val SpotifyArtistDecoder: JsonDecoder[Artist] = new JsonDecoder[Artist] {
    override def apply(implicit js: JSONObject): Artist = Artist(name = 'name)
  }

  implicit lazy val SpotifyImageDecoder: JsonDecoder[Image] = new JsonDecoder[Image] {
    override def apply(implicit js: JSONObject): Image = Image(width = 'width, height = 'height, url = 'url)
  }

  private def decodeArtist(js: JSONObject): ArtistData = ArtistData(name = array[Artist](js.getJSONArray("artists")) map (_.name) mkString ", ", avatar = None)

  private def decodeUser(implicit js: JSONObject): (ArtistData, Option[AssetData]) = {
    val img = decodeThumbnails(js)
    (ArtistData(name = decodeOptString('display_name) getOrElse 'id, avatar = img map (_.id)), img)
  }

  private def decodeArtwork(js: JSONObject): Option[AssetData] = Option(js.optJSONObject("album")) flatMap decodeThumbnails

  private def decodeThumbnails(js: JSONObject): Option[AssetData] = {
    val thumbs = Option(js.optJSONArray("images")) .map (arrayColl[Image, Vector](_)) .getOrElse (Vector.empty) .sortBy(_.width) .zipWithIndex .map { case (img, idx) =>
      Thumbnail(tag = idx.toString, url = img.url, width = img.width, height = img.height)
    }
    if (thumbs.isEmpty) None else Some(MediaAssetData.imageAsset(thumbs))
  }

  trait SpotifyJsonObjectResponse[A] {
    def fromJson(implicit js: JSONObject): Option[A]

    def unapply(resp: ResponseContent): Option[A] = resp match {
      case JsonObjectResponse(js) => fromJson(js)
      case other =>
        warn(s"unknown response content: $resp")
        None
    }
  }
}
