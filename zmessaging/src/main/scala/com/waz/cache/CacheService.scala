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
package com.waz.cache

import java.io._
import java.lang.System._

import android.content.Context
import com.waz.Analytics
import com.waz.ZLog._
import com.waz.cache.CacheEntryData.CacheEntryDao
import com.waz.content.Database
import com.waz.model.{AESKey, Mime, Uid}
import com.waz.threading.CancellableFuture.CancelException
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.crypto.AESUtils
import com.waz.utils.{IoUtils, returning}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class CacheEntry(val data: CacheEntryData, service: CacheService) extends LocalData {
  private implicit val logTag: LogTag = logTagFor[CacheEntry]

  override def inputStream: InputStream =
    content.fold[InputStream](CacheService.inputStream(data.encKey, new FileInputStream(cacheFile)))(new ByteArrayInputStream(_))

  override def length: Int = content.map(_.length.toLong).orElse(data.length).getOrElse(cacheFile.length).toInt

  override def file = content.fold(Option(cacheFile))(_ => None)

  override def byteArray = content

  def content = data.data

  // direct access to this file is not advised, it's content will be encrypted when on external storage, it's better to use stream api
  private[waz] def cacheFile = service.entryFile(data.path.getOrElse(service.cacheDir), data.fileId)

  def outputStream = {
    cacheFile.getParentFile.mkdirs()
    CacheService.outputStream(data.encKey, new FileOutputStream(cacheFile))
  }

  def copyDataToFile() = {
    content foreach { data =>
      IoUtils.copy(new ByteArrayInputStream(data), outputStream)
    }
    cacheFile
  }

  override def delete(): Unit = service.remove(this)

  override def toString: LogTag = s"CacheEntry($data)"

  def updatedWithLength(len: Long)(implicit ec: ExecutionContext): Future[CacheEntry] = service.cacheStorage.insert(data.copy(length = Some(len))).map(d => new CacheEntry(d, service))
}

object CacheEntry {
  def unapply(entry: CacheEntry): Option[(String, Option[Array[Byte]], File)] = Some((entry.data.key, entry.content, entry.cacheFile))
}

case class Expiration(timeout: Long)

object Expiration {
  import scala.language.implicitConversions

  implicit def in(d: Duration) : Expiration = if (d.isFinite()) Expiration(d.toMillis) else Expiration(1000L * 3600L * 24L * 365L * 1000L) // 1000 years (don't use Long.MaxValue due to overflow dangers)
}

class CacheService(context: Context, storage: Database) {
  import CacheService._
  import Threading.Implicits.Background

  lazy val cacheStorage = new CacheStorage(storage, context)

  // create new cache entry for file, return the entry immediately
  def createManagedFile(key: Option[AESKey] = None)(implicit timeout: Expiration = CacheService.DefaultExpiryTime) = {
    val location = if (key.isEmpty) Some(intCacheDir) else extCacheDir.orElse(Some(intCacheDir))  // use internal storage for unencrypted files
    val entry = CacheEntryData(Uid().str, None, timeout = timeout.timeout, path = location, encKey = key)
    cacheStorage.insert(entry)
    entry.path foreach { entryFile(_, entry.fileId).getParentFile.mkdirs() }
    new CacheEntry(entry, this)
  }

  def createForFile(key: String = Uid().str,  mime: Mime = Mime.Unknown, name: Option[String] = None, cacheLocation: Option[File] = None, length: Option[Long] = None)(implicit timeout: Expiration = CacheService.DefaultExpiryTime) =
    add(CacheEntryData(key, None, timeout = timeout.timeout, mimeType = mime, fileName = name, path = cacheLocation.orElse(Some(intCacheDir)), length = length)) // use internal storage for this files as those won't be encrypted

  def addData(key: String, data: Array[Byte])(implicit timeout: Expiration = CacheService.DefaultExpiryTime) =
    add(CacheEntryData(key, Some(data), timeout = timeout.timeout))

  def addStream[A](key: String, in: => InputStream, mime: Mime = Mime.Unknown, name: Option[String] = None, cacheLocation: Option[File] = None, length: Int = -1, execution: ExecutionContext = Background)(implicit timeout: Expiration = CacheService.DefaultExpiryTime): Future[CacheEntry] =
    if (length > 0 && length <= CacheService.DataThreshold) {
      Future(IoUtils.toByteArray(in))(execution).flatMap(addData(key, _))
    } else {
      Future(addStreamToStorage(IoUtils.copy(in, _), cacheLocation))(execution) flatMap {
        case Success((fileId, path, encKey, len)) =>
          add(CacheEntryData(key, timeout = timeout.timeout, path = Some(path), fileId = fileId, encKey = encKey, fileName = name, mimeType = mime, length = Some(len)))
        case Failure(c: CancelException) =>
          Future.failed(c)
        case Failure(e) =>
          Analytics.saveException(e, s"addStream($key) failed")
          Future.failed(e)
      }
    }

  def addFile(key: String, src: File, moveFile: Boolean = false, mime: Mime = Mime.Unknown, name: Option[String] = None, cacheLocation: Option[File] = None)(implicit timeout: Expiration = CacheService.DefaultExpiryTime): Future[CacheEntry] =
    addStreamToStorage(IoUtils.copy(new FileInputStream(src), _), cacheLocation) match {
      case Success((fileId, path, encKey, len)) =>
        if (moveFile) src.delete()
        add(CacheEntryData(key, timeout = timeout.timeout, path = Some(path), fileId = fileId, encKey = encKey, fileName = name, mimeType = mime, length = Some(len)))
      case Failure(e) =>
        Analytics.saveException(e, s"addFile($key) failed")
        throw new Exception(s"addFile($key) failed", e)
    }

  private def addStreamToStorage(writer: OutputStream => Long, location: Option[File]): Try[(Uid, File, Option[AESKey], Long)] = {
    def write(dir: File, enc: Option[AESKey]) = {
      val id = Uid()
      def entry(d: File) = returning(entryFile(d, id))(_.getParentFile.mkdirs())

      Try(writer(outputStream(enc, new FileOutputStream(entry(dir))))).recoverWith {
        case c: CancelException => Failure(c)
        case t: Throwable =>
          if (enc.isDefined) Try(writer(outputStream(None, new FileOutputStream(entry(intCacheDir)))))
          else Failure(t)
      } map (len => (id, dir, enc, len))
    }

    location match {
      case Some(dir) => write(dir, None)
      case None =>
        extCacheDir match {
          case Some(dir) => write(dir, Some(AESUtils.randomKey128()))
          case None => write(intCacheDir, None)
        }
    }
  }

  def move(key: String, entry: LocalData, mime: Mime = Mime.Unknown, name: Option[String] = None, cacheLocation: Option[File] = None)(implicit timeout: Expiration = CacheService.DefaultExpiryTime) = {
    verbose(s"move($key, $entry)")

    def copy() = addStream(key, entry.inputStream, mime, name, cacheLocation, entry.length)

    val location = cacheLocation.getOrElse(cacheDir)
    (entry match {
      case ce: CacheEntry if ce.data.path.contains(location) =>
        // move file to avoid copying, this should be much faster, and is safe when moving entries in the same cache location
        val prev = ce.data
        val moved = new CacheEntryData(key, prev.data, timeout = timeout.timeout, path = Some(location), encKey = prev.encKey, mimeType = mime, fileName = name)
        val prevFile = entryFile(location, prev.fileId)
        if (!prevFile.exists() || prevFile.renameTo(entryFile(location, moved.fileId))) {
          cacheStorage.insert(moved) map { e => new CacheEntry(e, this) }
        } else {
          copy()
        }
      case _ =>
        copy()
    }) map { current =>
      verbose(s"moved $current, file exists: ${current.cacheFile.exists()}, deleting entry: $entry")
      entry.delete()
      current
    }
  }

  private def add(entry: CacheEntryData) =
    cacheStorage.insert(entry) map { e =>
      e.path foreach { entryFile(_, e.fileId).getParentFile.mkdirs() }
      new CacheEntry(e, this)
    }

  def extCacheDir = Option(context.getExternalCacheDir).filter(_.isDirectory)
  def intCacheDir: File = context.getCacheDir
  def cacheDir: File = extCacheDir.getOrElse(intCacheDir)

  def getEntry(key: String): Future[Option[CacheEntry]] = cacheStorage.get(key) map {
    case Some(e) => Some(new CacheEntry(e, this))
    case None => None
  }

  def getDecryptedEntry(key: String)(implicit timeout: Expiration = CacheService.TemDataExpiryTime) =
    getEntry(key) flatMap {
      case Some(entry) if entry.data.encKey.isDefined =>
        getOrElse(key + "#_decr_", addStream(Uid().str, entry.inputStream, entry.data.mimeType, entry.data.fileName, Some(intCacheDir))) map (Some(_))
      case res => Future successful res
    }

  def getOrElse(key: String, default: => Future[CacheEntry]) = getEntry(key) flatMap {
    case Some(entry) => Future successful entry
    case _ => default
  }

  def remove(key: String): Future[Unit] = cacheStorage.remove(key)

  def remove(entry: CacheEntry): Future[Unit] = {
    verbose(s"remove($entry)")
    cacheStorage.remove(entry.data)
  }

  def deleteExpired(): CancellableFuture[Unit] = {
    val currentTime = currentTimeMillis()
    storage { implicit db =>
      val entries = CacheEntryDao.findAllExpired(currentTime)
      CacheEntryDao.deleteExpired(currentTime)
      entries
    }.map { entries =>
      entries foreach cacheStorage.remove
      entries
    }.map { _ foreach (entry => entry.path foreach { path => entryFile(path, entry.fileId).delete() }) }
  }

  private[cache] def entryFile(path: File, fileId: Uid) = CacheStorage.entryFile(path, fileId)

  // util method to perform local data processing with caching
  def processWithCaching(cacheKey: String, process: (InputStream, OutputStream) => Unit, data: LocalData): Future[LocalData] = {
    def processedByteData = Future {
      val bos = new ByteArrayOutputStream()
      process(data.inputStream, bos)
      bos.toByteArray
    }

    def processedFile = Future {
      val file = File.createTempFile("temp", ".enc", context.getCacheDir)
      process(data.inputStream, new BufferedOutputStream(new FileOutputStream(file)))
      file
    }

    def saveToCache =
      if (data.byteArray.isDefined) processedByteData.flatMap { addData(cacheKey, _) }
      else processedFile.flatMap { addFile(cacheKey, _, moveFile = true) }

    getOrElse(cacheKey, saveToCache)
  }
}

object CacheService {
  private implicit val logTag: LogTag = logTagFor[CacheService]

  val DataThreshold = 4 * 1024 // amount of data stored in db instead of a file
  val TemDataExpiryTime = 12.hours
  val DefaultExpiryTime = 7.days

  def outputStream(key: Option[AESKey], os: OutputStream) = key.fold(os) { AESUtils.outputStream(_, os) }

  def inputStream(key: Option[AESKey], is: InputStream) = key.fold(is) { AESUtils.inputStream(_, is) }
}
