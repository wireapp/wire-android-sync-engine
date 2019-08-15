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

import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.AccountData.Password
import org.libsodium.jni.Sodium
import org.libsodium.jni.NaCl

trait ChaCha20 {
  def chacha20Poly1305Orig(msg: Array[Byte], password: Password): Option[Array[Byte]]
}

class ChaCha20Impl(argon2: Argon2) extends ChaCha20 with DerivedLogTag {

  import com.waz.log.LogSE._

  override def chacha20Poly1305Orig(msg: Array[Byte], password: Password): Option[Array[Byte]] = {
    val sodium: Sodium = NaCl.sodium() //dynamically load the libsodium library

    val cipherText = Array.ofDim[Byte](msg.length + Sodium.crypto_aead_chacha20poly1305_abytes())
    val cipherTextLength = Array.ofDim[Int](1)
    val nonce: Array[Byte] = new RandomBytes().apply(Sodium.crypto_aead_chacha20poly1305_npubbytes())

    argon2.argon2i(password) match {
      case Some(key) =>
        if(key.length != Sodium.crypto_aead_chacha20poly1305_keybytes) {
          verbose(l"Key length invalid: ${key.length} did not match ${Sodium.crypto_aead_chacha20poly1305_keybytes}")
        }
        val ret = Sodium.crypto_aead_chacha20poly1305_encrypt(cipherText, cipherTextLength, msg, msg.length, Array.emptyByteArray, 0, Array.emptyByteArray, nonce, key)

        if (ret == 0) Some(cipherText) else None
      case _ => None
    }
  }
}
