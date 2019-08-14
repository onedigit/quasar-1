/*
 * Copyright 2014–2019 SlamData Inc.
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

package quasar.impl.storage

import slamdata.Predef._

final case class AntiEntropyStoreConfig(
  adTimeoutMillis: Long,
  purgeTimeoutMillis: Long,
  tombstoneLiveForMillis: Long,
  updateRequestLimit: Int,
  updateLimit: Int,
  adLimit: Int)

object AntiEntropyStoreConfig {
  val default: AntiEntropyStoreConfig = AntiEntropyStoreConfig(
    adTimeoutMillis = 30L,
    purgeTimeoutMillis = 1000L,
    tombstoneLiveForMillis = 300000L,
    updateRequestLimit = 128,
    updateLimit = 128,
    adLimit = 128)
}
