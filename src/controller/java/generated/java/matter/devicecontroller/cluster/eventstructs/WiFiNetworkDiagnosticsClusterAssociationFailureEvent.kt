/*
 *
 *    Copyright (c) 2023 Project CHIP Authors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package matter.devicecontroller.cluster.eventstructs

import matter.devicecontroller.cluster.*
import matter.tlv.ContextSpecificTag
import matter.tlv.Tag
import matter.tlv.TlvReader
import matter.tlv.TlvWriter

class WiFiNetworkDiagnosticsClusterAssociationFailureEvent(
  val associationFailure: UInt,
  val status: UShort
) {
  override fun toString(): String = buildString {
    append("WiFiNetworkDiagnosticsClusterAssociationFailureEvent {\n")
    append("\tassociationFailure : $associationFailure\n")
    append("\tstatus : $status\n")
    append("}\n")
  }

  fun toTlv(tlvTag: Tag, tlvWriter: TlvWriter) {
    tlvWriter.apply {
      startStructure(tlvTag)
      put(ContextSpecificTag(TAG_ASSOCIATION_FAILURE), associationFailure)
      put(ContextSpecificTag(TAG_STATUS), status)
      endStructure()
    }
  }

  companion object {
    private const val TAG_ASSOCIATION_FAILURE = 0
    private const val TAG_STATUS = 1

    fun fromTlv(
      tlvTag: Tag,
      tlvReader: TlvReader
    ): WiFiNetworkDiagnosticsClusterAssociationFailureEvent {
      tlvReader.enterStructure(tlvTag)
      val associationFailure = tlvReader.getUInt(ContextSpecificTag(TAG_ASSOCIATION_FAILURE))
      val status = tlvReader.getUShort(ContextSpecificTag(TAG_STATUS))

      tlvReader.exitContainer()

      return WiFiNetworkDiagnosticsClusterAssociationFailureEvent(associationFailure, status)
    }
  }
}