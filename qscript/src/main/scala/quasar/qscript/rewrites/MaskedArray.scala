/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.qscript.rewrites

import slamdata.Predef.{Int, None, Option, Set, Some}

import matryoshka.BirecursiveT
import matryoshka.data.free._
import matryoshka.implicits._

import quasar.common.{CPath, CPathIndex}
import quasar.contrib.iota._
import quasar.contrib.scalaz.free._
import quasar.qscript.FreeMapA
import quasar.qscript.MapFuncCore.StaticArray

import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.option._
import scalaz.syntax.equal._
import scalaz.syntax.foldable._

object MaskedArray {
  def unapply[T[_[_]]: BirecursiveT](prjd: FreeMapA[T, CPath]): Option[Set[Int]] =
    StaticArray.unapply(prjd.project).flatMap(_.zipWithIndex.foldLeftM(Set[Int]()) {
      case (s, (FreeA(CPath(CPathIndex(idx))), i)) if idx === i =>
        Some(s + idx)

      case _ => None
    })
}
