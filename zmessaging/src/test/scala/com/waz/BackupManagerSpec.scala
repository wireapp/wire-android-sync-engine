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
package com.waz

import java.io._
import java.util.zip.{ZipFile, ZipOutputStream}

import com.waz.model.AccountData.Password
import com.waz.model.UserId
import com.waz.service.BackupManagerImpl
import com.waz.specs.AndroidFreeSpec
import com.waz.utils.IoUtils.withResource
import com.waz.utils.Json.syntax._
import com.waz.utils.crypto.LibSodiumUtils
import com.waz.utils.{IoUtils, returning}
import org.scalatest._

import scala.util.Try

class BackupManagerSpec extends AndroidFreeSpec with BeforeAndAfterAll with BeforeAndAfterEach {

  import com.waz.service.BackupManager._

  private val testUserId = UserId()
  private val testDirectory =
    new File(s"${System.getProperty("java.io.tmpdir")}/${getClass.getSimpleName}_${System.currentTimeMillis()}")
  private val testMetadata = BackupMetadata(testUserId, version = 20)

  private def createFakeDatabase(targetDirectory: File = testDirectory): File =
    returning(new File(targetDirectory, getDbFileName(testUserId))) { file =>
      withResource(new PrintWriter(file)) { _.write("some content") }
  }

  private def createFakeDatabaseWal(targetDirectory: File = testDirectory): File =
    returning(new File(targetDirectory, getDbWalFileName(testUserId))) { file =>
      withResource(new PrintWriter(file)) { _.write("some content") }
    }

  private def createFakeBackup(metadata: Option[Array[Byte]] = Some(testMetadata.toJsonString.getBytes("utf8")),
                               database: Option[File] = Some(createFakeDatabase()),
                               databaseWal: Option[File] = Some(createFakeDatabaseWal()),
                               targetDirectory: File = testDirectory): File = {
    returning(new File(targetDirectory, "fake_backup.zip")) { zipFile =>
      withResource(new ZipOutputStream(new FileOutputStream(zipFile))) { zip =>
        metadata foreach { md =>
          withResource(new ByteArrayInputStream(md)) {
            IoUtils.writeZipEntry(_, zip, backupMetadataFileName)
          }
        }

        database foreach { db =>
          withResource(new BufferedInputStream(new FileInputStream(db))) {
            IoUtils.writeZipEntry(_, zip, getDbFileName(testUserId))
          }
        }

        databaseWal foreach { wal =>
          withResource(new BufferedInputStream(new FileInputStream(wal))) {
            IoUtils.writeZipEntry(_, zip, getDbWalFileName(testUserId))
          }
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    if (!testDirectory.mkdir()) throw new RuntimeException("Cannot create directory for tests.")
  }

  override protected def afterEach(): Unit = {
    IoUtils.deleteRecursively(testDirectory)
  }

  private def getZipFileEntryNames(zipFile: ZipFile): Set[String] = {
    val iterator = zipFile.entries()
    Stream.continually(Try(iterator.nextElement())).takeWhile(_.isSuccess).map(_.get.getName).toSet
  }

  private def getAllFileNames(directory: File): Set[String] = {
    directory.listFiles().map(_.getName).toSet
  }

  private val libSodiumUtils = mock[LibSodiumUtils]
  private def getService() = new BackupManagerImpl(libSodiumUtils)

  feature("Exporting database") {

    scenario("create an export zip file with metadata and all database related files.") {
      val fakeDatabase = createFakeDatabase()
      createFakeDatabaseWal()
      val zipFile = getService().exportDatabase(testUserId, userHandle = "TEST", databaseDir = fakeDatabase.getParentFile, targetDir = testDirectory, None).get

      withClue("Zip file should exist.") { zipFile.exists() shouldEqual true }
      withResource(new ZipFile(zipFile)) { zip =>
        withClue("Files inside test directory: " + getAllFileNames(testDirectory)) {
          getZipFileEntryNames(zip) shouldEqual Set(
            backupMetadataFileName,
            getDbFileName(testUserId),
            getDbWalFileName(testUserId)
          )
        }
      }
    }

    scenario("create an encrypted export zip file with metadata and all database related files.") {
      val fakeDatabase = createFakeDatabase()
      createFakeDatabaseWal()

      val saltLength = 32
      val password = Password("12345678")
      (libSodiumUtils.generateSalt _).expects().anyNumberOfTimes().returning(Array.ofDim[Byte](saltLength))
      (libSodiumUtils.encrypt _).expects(*, *, *).anyNumberOfTimes().returning(Some(Array.ofDim[Byte](fakeDatabase.length().toInt)))
      (libSodiumUtils.hash _).expects(*, *).anyNumberOfTimes().returning(Some(Array.ofDim[Byte](saltLength)))

      val backup = getService().exportDatabase(testUserId, userHandle = "TEST", databaseDir = fakeDatabase.getParentFile, targetDir = testDirectory, password = Some(password)).get

      withClue("Zip file should exist.") { backup.exists() shouldEqual true }
      withResource(new FileInputStream(backup)) { b =>

        /**
        since we can't test the hashing because we can't load libsodium dynamically in tests yet, the
        next best thing is to check the size of the header manually.
        **/
        val metadataHeaderBytes = Array.ofDim[Byte](71)
        withClue("encrypted backup should have right header length") {
          backup.length() shouldEqual (metadataHeaderBytes.length + fakeDatabase.length())
        }

        b.read(metadataHeaderBytes)
        val magicNumber = metadataHeaderBytes.take(4).map(_.toChar).mkString
        val androidMagicNumber = "WBUA"
        val metaNullByte = metadataHeaderBytes.slice(4, 5)
        val version = metadataHeaderBytes.slice(5, 7)
        val expectedNullByte = Array[Byte](0)
        expectedNullByte.update(0, 0)

        withClue(s"magic number should match $androidMagicNumber") {
          magicNumber shouldEqual androidMagicNumber
        }
        withClue("null byte should be present") {
          (metaNullByte sameElements expectedNullByte) shouldEqual true
        }
        withClue("version should match latest version") {
          version.sum shouldEqual 1
        }
      }
    }

  }

  feature("Importing database") {

    scenario("unzip backup file and fail if metadata file and db file not found.") {
      val fakeBackup = createFakeBackup(metadata = None, database = None)

      an [InvalidBackup] should be thrownBy getService().importDatabase(testUserId, fakeBackup, testDirectory).get
    }

    scenario("unzip backup file and fail if metadata file not found.") {
      val fakeBackup = createFakeBackup(metadata = None)
      an [InvalidBackup.MetadataEntryNotFound.type] should be thrownBy getService().importDatabase(testUserId, fakeBackup, testDirectory).get
    }

    scenario("unzip backup file and fail if db file not found.") {
      val fakeBackup = createFakeBackup(database = None)
      an [InvalidBackup.DbEntryNotFound.type] should be thrownBy getService().importDatabase(testUserId, fakeBackup, testDirectory).get
    }

    scenario("unzip backup file and fail if metadata format is invalid.") {
      val fakeBackup = createFakeBackup(metadata = Some(Array(1,2,3,4,5)))
      an [InvalidMetadata.WrongFormat] should be thrownBy getService().importDatabase(testUserId, fakeBackup, testDirectory).get
    }

    scenario("unzip backup file and fail if user ids are not the same.") {
      val metadataWithRandomUserId = BackupMetadata(UserId())
      val fakeBackup = createFakeBackup(metadata = Some(metadataWithRandomUserId.toJsonString.getBytes("utf-8")))

      an [InvalidMetadata.UserId.type] should be thrownBy getService().importDatabase(testUserId, fakeBackup, testDirectory).get
    }

    scenario("unzip backup file and fail if current database version is less then from metadata.") {
      val metadataWithDbVersionGreaterThenCurrent = BackupMetadata(testUserId, version = BackupMetadata.currentDbVersion + 1)
      val fakeBackup = createFakeBackup(metadata = Some(metadataWithDbVersionGreaterThenCurrent.toJsonString.getBytes("utf-8")))

      an [InvalidMetadata.DbVersion.type] should be thrownBy getService().importDatabase(testUserId, fakeBackup, testDirectory).get
    }

    scenario("unzip backup file successfully if all needed files are present and metadata is valid.") {
      val fakeBackup = createFakeBackup()
      val targetDirectory = new File(testDirectory, "test_target_dir")
      if (!targetDirectory.mkdir()) throw new RuntimeException("Cannot create target directory for test.")

      getService().importDatabase(testUserId, fakeBackup, targetDirectory).get
      withClue("Files inside target directory: " + getAllFileNames(targetDirectory)) {
        getAllFileNames(targetDirectory) shouldEqual Set(
          getDbFileName(testUserId),
          getDbWalFileName(testUserId)
        )
      }
    }

    scenario("unzip backup file successfully if all needed files (except wal file) are present and metadata is valid.") {
      val fakeBackup = createFakeBackup(databaseWal = None)
      val targetDirectory = new File(testDirectory, "test_target_dir")
      if (!targetDirectory.mkdir()) throw new RuntimeException("Cannot create target directory for test.")

      getService().importDatabase(testUserId, fakeBackup, targetDirectory).get
      withClue("Files inside target directory: " + getAllFileNames(targetDirectory)) {
        getAllFileNames(targetDirectory) shouldEqual Set(getDbFileName(testUserId))
      }
    }

    scenario("unzip backup file successfully if all needed files are present and metadata is valid (when current db version greater then from metadata).") {
      val metadataWithDbVersionLessThenCurrent = BackupMetadata(testUserId, version = BackupMetadata.currentDbVersion - 1)
      val fakeBackup = createFakeBackup(metadata = Some(metadataWithDbVersionLessThenCurrent.toJsonString.getBytes("utf-8")))
      val targetDirectory = new File(testDirectory, "test_target_dir")
      if (!targetDirectory.mkdir()) throw new RuntimeException("Cannot create target directory for test.")

      getService().importDatabase(testUserId, fakeBackup, targetDirectory).get
      withClue("Files inside target directory: " + getAllFileNames(targetDirectory)) {
        getAllFileNames(targetDirectory) shouldEqual Set(
          getDbFileName(testUserId),
          getDbWalFileName(testUserId)
        )
      }
    }

  }




}
