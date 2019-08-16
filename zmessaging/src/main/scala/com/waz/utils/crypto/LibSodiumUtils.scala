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

trait LibSodiumUtils {
  def encrypt(msg: Array[Byte], password: Password, salt: Array[Byte]): Option[Array[Byte]]
  def decrypt(ciphertext: Array[Byte], password: Password, salt: Array[Byte]): Option[Array[Byte]]
  def hash(input: String, salt: Array[Byte]): Option[Array[Byte]]
  def generateSalt(): Array[Byte]
}

class LibSodiumUtilsImpl() extends LibSodiumUtils with DerivedLogTag {

  import com.waz.log.LogSE._

  private val sodium = NaCl.sodium() // dynamically load the libsodium library

  override def encrypt(msg: Array[Byte], password: Password, salt: Array[Byte]): Option[Array[Byte]] = {
    val cipherText = Array.ofDim[Byte](msg.length + Sodium.crypto_aead_chacha20poly1305_abytes())
    val cipherTextLength = Array.ofDim[Int](1)

    hash(password.str, salt) match {
      case Some(key) =>
        if (key.length != Sodium.crypto_aead_chacha20poly1305_keybytes) {
          verbose(l"Key length invalid: ${key.length} did not match ${Sodium.crypto_aead_chacha20poly1305_keybytes}")
        }
        val ret = Sodium.crypto_aead_chacha20poly1305_encrypt(cipherText, cipherTextLength, msg, msg.length, Array.emptyByteArray, 0, Array.emptyByteArray, salt, key)

        if (ret == 0) Some(cipherText)
        else {
          error(l"Failed to hash backup")
          None
        }
      case _ =>
        error(l"Couldn't derive key from password")
        None
    }
  }

  override def decrypt(ciphertext: Array[Byte], password: Password, salt: Array[Byte]): Option[Array[Byte]] = {
    val decrypted = Array.ofDim[Byte](ciphertext.length)
    val decryptedLen = Array.ofDim[Int](1)

    hash(password.str, salt) match {
      case Some(key) =>
        if (key.length != Sodium.crypto_aead_chacha20poly1305_keybytes) {
          verbose(l"Key length invalid: ${key.length} did not match ${Sodium.crypto_aead_chacha20poly1305_keybytes}")
        }
        val ret: Int = Sodium.crypto_aead_chacha20poly1305_decrypt(decrypted, decryptedLen, Array.emptyByteArray, ciphertext, ciphertext.length, Array.emptyByteArray, 0, salt, key)

        if (ret == 0) Some(decrypted.take(decryptedLen.head))
        else {
          error(l"Failed to decrypt backup")
          None
        }
      case _ =>
        error(l"Couldn't derive key from password")
        None
    }
  }

  override def hash(input: String, salt: Array[Byte]): Option[Array[Byte]] = {
    val outputLength = Sodium.crypto_aead_chacha20poly1305_keybytes()
    val output: Array[Byte] = Array.ofDim[Byte](outputLength)
    val passBytes: Array[Byte] = input.getBytes
    val ret: Int = Sodium.crypto_pwhash(output, output.length, passBytes, passBytes.length, salt,
      Sodium.crypto_pwhash_opslimit_moderate(),
      Sodium.crypto_pwhash_memlimit_min(),
      Sodium.crypto_pwhash_alg_default())

    if (ret == 0) Some(output) else None
  }

  override def generateSalt(): Array[Byte] = new RandomBytes().apply(Sodium.crypto_pwhash_saltbytes())
}
