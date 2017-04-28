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
package com.waz.content

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.model.Id
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.znet.AuthenticationManager.Token
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.{ExecutionContext, Future}

trait Preference[A] {
  protected implicit def dispatcher: ExecutionContext
  def default: A
  def apply(): Future[A]
  def update(value: A): Future[Unit]

  def :=(value: A): Future[Unit] = update(value)
  def mutate(f: A => A): Future[Unit] = apply().flatMap(cur => update(f(cur)))

  lazy val signal: SourceSignal[A] = {
    val s = Signal[A]()
    apply().onSuccess { case v => s.publish(v, Threading.Background) }(Threading.Background)
    s
  }
}

object Preference {
  def empty[A] = new Preference[Option[A]] {
    override protected implicit val dispatcher = null
    def default = None
    def apply() = Future.successful(None)
    override def update(value: Option[A]) = Future.successful({})
  }

  def inMemory[A](defaultValue: A, key: Option[String] = None): Preference[A] = new Preference[A] {
    override protected implicit val dispatcher = new SerialDispatchQueue()

    private lazy val hash = s"@${Integer.toHexString(hashCode)}"

    private var value = defaultValue

    override def default = defaultValue

    override def update(v: A) = Future {
      key.foreach(k => verbose(s"updating $k$hash: $value to $v"))
      value = v
      signal ! v
    }

    override def apply() = Future {
      key.foreach(k => verbose(s"getting $k$hash: $value"))
      value
    }
  }

  def apply[A](defaultValue: A, load: => Future[A], save: A => Future[Any]): Preference[A] = new Preference[A] {
    override protected implicit val dispatcher = Threading.Background
    override def default = defaultValue
    override def update(v: A) = save(v) map { _ => signal ! v }
    override def apply() = load
  }

  trait PrefCodec[A] {
    def encode(v: A): String
    def decode(str: String): A
  }

  object PrefCodec {
    def apply[A](enc: A => String, dec: String => A): PrefCodec[A] = new PrefCodec[A] {
      override def encode(v: A): String = enc(v)
      override def decode(str: String): A = dec(str)
    }

    implicit val StrCodec = apply[String](identity, identity)
    implicit val IntCodec = apply[Int](String.valueOf, java.lang.Integer.parseInt)
    implicit val LongCodec = apply[Long](String.valueOf, java.lang.Long.parseLong)
    implicit val BooleanCodec = apply[Boolean](String.valueOf, java.lang.Boolean.parseBoolean)
    implicit def idCodec[A: Id]: PrefCodec[A] = apply[A](implicitly[Id[A]].encode, implicitly[Id[A]].decode)
    implicit def optCodec[A: PrefCodec]: PrefCodec[Option[A]] = apply[Option[A]](_.fold("")(implicitly[PrefCodec[A]].encode), { str => if (str == "") None else Some(implicitly[PrefCodec[A]].decode(str)) })
    implicit val InstantCodec = apply[Instant](d => String.valueOf(d.toEpochMilli), s => Instant.ofEpochMilli(java.lang.Long.parseLong(s)))
    implicit val TokenCodec = apply[Option[Token]] (
      { t => optCodec[String].encode(t map Token.Encoder.apply map (_.toString)) },
      { s => optCodec[String].decode(s) map (new JSONObject(_)) map (Token.Decoder.apply(_)) })
  }
}
