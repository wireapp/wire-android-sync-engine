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
package com.waz.utils.crypto

import com.waz.model.AccountData.Password
import org.libsodium.jni.Sodium
import org.libsodium.jni.NaCl

trait Argon2 {
  def argon2i(password: Password): Option[Array[Byte]]
}

class Argon2Impl extends Argon2 {

  import Argon2Impl._

  override def argon2i(password: Password): Option[Array[Byte]] = {
    val sodium: Sodium = NaCl.sodium() //dynamically load the libsodium library

    val output: Array[Byte] = Array.ofDim[Byte](OutputBytesLength)
    val passBytes: Array[Byte] = password.str.toCharArray.map(_.toByte)
    val salt: Array[Byte] = new RandomBytes().apply(SaltBytesLength)

    val ret = Sodium.crypto_pwhash(output, OutputBytesLength, passBytes, passBytes.length, salt,
      Sodium.crypto_pwhash_opslimit_moderate(),
      Sodium.crypto_pwhash_memlimit_moderate(),
      Sodium.crypto_pwhash_alg_argon2i13())

    if (ret == 0) Some(output) else None
  }
}

object Argon2Impl {
  val OutputBytesLength = 32
  val SaltBytesLength = 16
}
