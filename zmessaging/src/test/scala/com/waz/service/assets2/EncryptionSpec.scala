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
package com.waz.service.assets2

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import com.waz.TestData
import com.waz.specs.ZSpec
import com.waz.utils.IoUtils
import com.waz.utils.crypto.AESUtils

class EncryptionSpec extends ZSpec {

  feature("AESUtils") {

    scenario("should encrypt and decrypt input stream properly") {
      val unencrypted = TestData.bytes(200)

      val key          = AESUtils.randomBytes(16)
      val outputStream = new ByteArrayOutputStream()

      IoUtils.copy(
        in = AESUtils.encryptInputStream(key, new ByteArrayInputStream(unencrypted)),
        out = outputStream
      )

      val encrypted = outputStream.toByteArray
      outputStream.reset()

      encrypted shouldNot contain(unencrypted)

      IoUtils.copy(
        in = AESUtils.decryptInputStream(key, new ByteArrayInputStream(encrypted)),
        out = outputStream
      )

      val decrypted = outputStream.toByteArray

      decrypted shouldBe unencrypted
    }

    scenario("should calculate after encryption size properly") {
      val unencrypted = TestData.bytes(1024)

      val key          = AESUtils.randomBytes(16)
      val outputStream = new ByteArrayOutputStream()

      IoUtils.copy(
        in = AESUtils.encryptInputStream(key, new ByteArrayInputStream(unencrypted)),
        out = outputStream
      )
      val encrypted = outputStream.toByteArray

      AESUtils.sizeAfterEncryption(key, unencrypted.length) shouldBe encrypted.length
    }

  }

}
