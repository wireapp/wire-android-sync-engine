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

import java.io.{File, InputStream}

import com.waz.model.AESKey
import com.waz.utils.crypto.AESUtils

trait Encryption {
  def decrypt(is: InputStream): InputStream
  def encrypt(os: InputStream): InputStream
}

case object NoEncryption extends Encryption {
  override def decrypt(is: InputStream): InputStream = is
  override def encrypt(is: InputStream): InputStream = is
}

case class AES_CBC_Encryption(key: AESKey) extends Encryption {
  override def decrypt(is: InputStream): InputStream = AESUtils.decryptInputStream(key.bytes, is)
  override def encrypt(is: InputStream): InputStream = AESUtils.encryptInputStream(key.bytes, is)
}

case class EncryptedFile(file: File, encryption: Encryption)