/*
 *   Copyright (c) 2020 Project CHIP Authors
 *   All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package com.google.chip.chiptool.provisioning

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.DialogInterface
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import chip.devicecontroller.AttestationInfo
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.DeviceAttestationDelegate
import chip.devicecontroller.ICDDeviceInfo
import chip.devicecontroller.ICDRegistrationInfo
import chip.devicecontroller.NetworkCredentials
import com.google.chip.chiptool.CHIPToolActivity
import com.google.chip.chiptool.ChipClient
import com.google.chip.chiptool.GenericChipDeviceListener
import com.google.chip.chiptool.NetworkCredentialsParcelable
import com.google.chip.chiptool.R
import com.google.chip.chiptool.bluetooth.BluetoothManager
import com.google.chip.chiptool.setuppayloadscanner.CHIPDeviceInfo
import com.google.chip.chiptool.util.DeviceIdUtil
import com.google.chip.chiptool.util.FragmentUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import java.util.Locale

@ExperimentalCoroutinesApi
class DeviceProvisioningFragment : Fragment() {

  private lateinit var deviceInfo: CHIPDeviceInfo

  private var gatt: BluetoothGatt? = null

  private val networkCredentialsParcelable: NetworkCredentialsParcelable?
    get() = arguments?.getParcelable(ARG_NETWORK_CREDENTIALS)

  private lateinit var deviceController: ChipDeviceController

  private lateinit var scope: CoroutineScope

  private var dialog: AlertDialog? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    deviceController = ChipClient.getDeviceController(requireContext())
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    scope = viewLifecycleOwner.lifecycleScope
    deviceInfo = checkNotNull(requireArguments().getParcelable(ARG_DEVICE_INFO))

    return inflater.inflate(R.layout.barcode_fragment, container, false).apply {
      if (savedInstanceState == null) {
        if (deviceInfo.ipAddress != null) {
          pairDeviceWithAddress()
        } else {
          val doCommissioningOverNFC = true

          var androidNfcTag = CHIPToolActivity.getAndroidNfcTag();
          if ((androidNfcTag != null) && (doCommissioningOverNFC)) {
            startConnectingToDeviceViaNfc(androidNfcTag)
          } else {
            startConnectingToDevice()
          }
        }
      }
    }
  }

  override fun onStop() {
    super.onStop()
    gatt = null
    dialog = null
  }

  override fun onDestroy() {
    super.onDestroy()
    deviceController.close()
    deviceController.setDeviceAttestationDelegate(0, EmptyAttestationDelegate())
  }

  private class EmptyAttestationDelegate : DeviceAttestationDelegate {
    override fun onDeviceAttestationCompleted(
      devicePtr: Long,
      attestationInfo: AttestationInfo,
      errorCode: Long
    ) {}
  }

  private fun setAttestationDelegate() {
    deviceController.setDeviceAttestationDelegate(DEVICE_ATTESTATION_FAILED_TIMEOUT) {
      devicePtr,
      _,
      errorCode ->
      Log.i(
        TAG,
        "Device attestation errorCode: $errorCode, " +
          "Look at 'src/credentials/attestation_verifier/DeviceAttestationVerifier.h' " +
          "AttestationVerificationResult enum to understand the errors"
      )

      val activity = requireActivity()

      if (errorCode == STATUS_PAIRING_SUCCESS) {
        activity.runOnUiThread(Runnable { deviceController.continueCommissioning(devicePtr, true) })

        return@setDeviceAttestationDelegate
      }

      activity.runOnUiThread(
        Runnable {
          if (dialog != null && dialog?.isShowing == true) {
            Log.d(TAG, "dialog is already showing")
            return@Runnable
          }
          dialog =
            AlertDialog.Builder(activity)
              .setPositiveButton(
                "Continue",
                DialogInterface.OnClickListener { dialog, id ->
                  deviceController.continueCommissioning(devicePtr, true)
                }
              )
              .setNegativeButton(
                "No",
                DialogInterface.OnClickListener { dialog, id ->
                  deviceController.continueCommissioning(devicePtr, false)
                }
              )
              .setTitle("Device Attestation")
              .setMessage(
                "Device Attestation failed for device under commissioning. Do you wish to continue pairing?"
              )
              .show()
        }
      )
    }
  }

  private fun pairDeviceWithAddress() {
    // IANA CHIP port
    val id = DeviceIdUtil.getNextAvailableId(requireContext())

    DeviceIdUtil.setNextAvailableId(requireContext(), id + 1)
    deviceController.setCompletionListener(ConnectionCallback())

    setAttestationDelegate()

    deviceController.pairDeviceWithAddress(
      id,
      deviceInfo.ipAddress,
      deviceInfo.port,
      deviceInfo.discriminator,
      deviceInfo.setupPinCode,
      null
    )
  }

  override fun getContext(): Context? {
    return requireActivity() as Context
  }

  private fun startConnectingToDevice() {

    if (gatt != null) {
      return
    }
    scope.launch {
      val bluetoothManager = BluetoothManager()

      showMessage(R.string.rendezvous_over_ble_scanning_text, deviceInfo.discriminator.toString())
      val device =
        bluetoothManager.getBluetoothDevice(
          requireContext(),
          deviceInfo.discriminator,
          deviceInfo.isShortDiscriminator
        )
          ?: run {
            showMessage(R.string.rendezvous_over_ble_scanning_failed_text)
            return@launch
          }

      showMessage(
        R.string.rendezvous_over_ble_connecting_text,
        device.name ?: device.address.toString()
      )

      gatt = bluetoothManager.connect(requireContext(), device)

      showMessage(R.string.rendezvous_over_ble_pairing_text)
      deviceController.setCompletionListener(ConnectionCallback())

      val deviceId = DeviceIdUtil.getNextAvailableId(requireContext())
      val connId = bluetoothManager.connectionId
      var network: NetworkCredentials? = null
      var networkParcelable = checkNotNull(networkCredentialsParcelable)

      val wifi = networkParcelable.wiFiCredentials
      if (wifi != null) {
        network =
          NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(wifi.ssid, wifi.password))
      }

      val thread = networkParcelable.threadCredentials
      if (thread != null) {
        network =
          NetworkCredentials.forThread(
            NetworkCredentials.ThreadCredentials(thread.operationalDataset)
          )
      }

      setAttestationDelegate()

      deviceController.pairDevice(gatt, connId, deviceId, deviceInfo.setupPinCode, network)
      DeviceIdUtil.setNextAvailableId(requireContext(), deviceId + 1)
    }
  }

  private fun startConnectingToDeviceViaNfc(nfcTag : Tag) {

    Log.d(TAG, "startConnectingToDeviceViaNfc. nfcTag: " + nfcTag.toString())

    // Set Matter Progress Callback
    Log.d(TAG, "setMatterProgressCallback")
    ChipClient.getDeviceController(requireActivity()).setMatterProgressCallback(ChipMatterProgressCallback())

    ChipClient.getAndroidNfcManager().setNFCTag(nfcTag)

    scope.launch {

      displayCommissioningProgress();

      deviceController.setCompletionListener(ConnectionCallback())

      val deviceId = DeviceIdUtil.getNextAvailableId(requireContext())
      var network: NetworkCredentials? = null
      var networkParcelable = checkNotNull(networkCredentialsParcelable)

      val wifi = networkParcelable.wiFiCredentials
      if (wifi != null) {
        network = NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(wifi.ssid, wifi.password))
      }

      val thread = networkParcelable.threadCredentials
      if (thread != null) {
        network = NetworkCredentials.forThread(NetworkCredentials.ThreadCredentials(thread.operationalDataset))
      }

      setAttestationDelegate()

      deviceController.pairDeviceViaNfc(deviceId, deviceInfo.setupPinCode, network)
      DeviceIdUtil.setNextAvailableId(requireContext(), deviceId + 1)
    }
  }

  private fun showMessage(msgResId: Int, stringArgs: String? = null) {
    requireActivity().runOnUiThread {
      val context = requireContext()
      val msg = context.getString(msgResId, stringArgs)
      Log.i(TAG, "showMessage:$msg")
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
  }

  inner class ConnectionCallback : GenericChipDeviceListener() {
    override fun onConnectDeviceComplete() {
      Log.d(TAG, "onConnectDeviceComplete")
    }

    override fun onStatusUpdate(status: Int) {
      Log.d(TAG, "Pairing status update: $status")
    }

    override fun onCommissioningComplete(nodeId: Long, errorCode: Long) {
      if (errorCode == STATUS_PAIRING_SUCCESS) {
        FragmentUtil.getHost(this@DeviceProvisioningFragment, Callback::class.java)
          ?.onCommissioningComplete(0L, nodeId)
      } else {
        showMessage(R.string.rendezvous_over_ble_pairing_failure_text)
        FragmentUtil.getHost(this@DeviceProvisioningFragment, Callback::class.java)
          ?.onCommissioningComplete(errorCode)
      }
    }

    override fun onPairingComplete(code: Long) {
      Log.d(TAG, "onPairingComplete: $code")

      if (code != STATUS_PAIRING_SUCCESS) {
        showMessage(R.string.rendezvous_over_ble_pairing_failure_text)
        FragmentUtil.getHost(this@DeviceProvisioningFragment, Callback::class.java)
          ?.onCommissioningComplete(code)
      }
    }

    override fun onOpCSRGenerationComplete(csr: ByteArray) {
      Log.d(TAG, String(csr))
    }

    override fun onPairingDeleted(code: Long) {
      Log.d(TAG, "onPairingDeleted: $code")
    }

    override fun onCloseBleComplete() {
      Log.d(TAG, "onCloseBleComplete")
    }

    override fun onError(error: Throwable?) {
      Log.d(TAG, "onError: $error")
    }

    override fun onICDRegistrationInfoRequired() {
      Log.d(TAG, "onICDRegistrationInfoRequired")
      deviceController.updateCommissioningICDRegistrationInfo(
        ICDRegistrationInfo.newBuilder().build()
      )
    }

    override fun onICDRegistrationComplete(errorCode: Long, icdDeviceInfo: ICDDeviceInfo) {
      Log.d(
        TAG,
        "onICDRegistrationComplete - errorCode: $errorCode, symmetricKey : ${icdDeviceInfo.symmetricKey.toHex()}, icdDeviceInfo : $icdDeviceInfo"
      )
      requireActivity().runOnUiThread {
        Toast.makeText(
            requireActivity(),
            getString(
              R.string.icd_registration_completed,
              icdDeviceInfo.userActiveModeTriggerHint.toString(),
              icdDeviceInfo.userActiveModeTriggerInstruction,
              icdDeviceInfo.idleModeDuration.toString(),
              icdDeviceInfo.activeModeDuration.toString(),
              icdDeviceInfo.activeModeThreshold.toString()
            ),
            Toast.LENGTH_LONG
          )
          .show()
      }
    }
  }

  private fun ByteArray.toHex(): String =
    joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

  /** Callback from [DeviceProvisioningFragment] notifying any registered listeners. */
  interface Callback {
    /** Notifies that commissioning has been completed. */
    fun onCommissioningComplete(code: Long, nodeId: Long = 0L)
  }

  fun showToast(resource_id: Int, vararg formatArgs: Any?) {
    requireActivity().runOnUiThread { // This function can be called from a background thread so it may happen after the
      // destruction of the activity. In such case, getResmessageources() may be null.
      val resources = resources
      if (resources != null) {
        val message = resources.getString(resource_id, *formatArgs)
        Log.d(TAG, message)
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
      }
    }
  }

  companion object {
    private const val TAG = "DeviceProvisioningFragment"
    private const val ARG_DEVICE_INFO = "device_info"
    private const val ARG_NETWORK_CREDENTIALS = "network_credentials"
    private const val STATUS_PAIRING_SUCCESS = 0L

    /**
     * Set for the fail-safe timer before onDeviceAttestationFailed is invoked.
     *
     * This time depends on the Commissioning timeout of your app.
     */
    private const val DEVICE_ATTESTATION_FAILED_TIMEOUT = 600

    protected const val MY_PREFS_NAME = "TEMP_KOTLIN_Prefs"
    private const val HRM_SAMPLES_COUNT = 60
    const val TYPE4_EXTENDED_ADPU_MAX_SIZE = 63 * 1024

    // Max length for a Type4 R-APDU (as defined in the NFC Forum Type4 Tag TS)
    const val TYPE4_MAX_RADPU_SIZE = 246

    // Max length for a Type4 C-APDU (as defined in the NFC Forum Type4 Tag TS)
    const val TYPE4_MAX_CAPDU_SIZE = 246
    const val TYPE4_HEADER_SIZE = 4
    const val TYPE4_CMD_SELECT = 0xA4.toByte()
    const val TYPE4_CMD_SELECT_BY_NAME = 0x04.toByte()
    const val TYPE4_CMD_SELECT_BY_FILE_ID = 0x00.toByte()
    const val TYPE4_CMD_FIRST_OR_ONLY_OCCURENCE = 0x0C.toByte()
    const val TYPE4_CMD_READ_BINARY = 0xB0.toByte()
    @Throws(Exception::class)
    fun convertIntTo2BytesHexaFormat(numberToConvert: Int): ByteArray {
      return if (numberToConvert >= 0 && numberToConvert <= 65535) {
        byteArrayOf(
          (numberToConvert and '\uff00'.code shr 8).toByte(),
          (numberToConvert and 255).toByte()
        )
      } else {
        throw Exception()
      }
    }

    /**
     * Allocate a buffer of a requested size containing 01234567890123...etc
     * @param dataLength
     * @return
     */
    fun allocateAndInitData(dataLength: Int): ByteArray {
      val alphabet = "0123456789".toCharArray()
      val data = ByteArray(dataLength)
      for (i in 0 until dataLength) {
        data[i] = alphabet[i % 10].code.toByte()
      }
      return data
    }

    fun convertHexByteArrayToString(`in`: ByteArray?): String {
      val builder = StringBuilder()
      for (b in `in`!!) {
        builder.append(String.format("%02x ", b))
      }
      return builder.toString()
    }

    fun convertByteToUnsignedInt(byteToConvert: Byte): Int {
      return byteToConvert.toInt() and 255
    }

    fun convertIntToHexFormatString(numberToConvert: Int): String {
      return String.format("%04x", numberToConvert).uppercase(Locale.getDefault())
    }

    /**
     * Return a new instance of [DeviceProvisioningFragment]. [networkCredentialsParcelable] can be
     * null for IP commissioning.
     */
    fun newInstance(
      deviceInfo: CHIPDeviceInfo,
      networkCredentialsParcelable: NetworkCredentialsParcelable?,
    ): DeviceProvisioningFragment {
      return DeviceProvisioningFragment().apply {
        arguments =
          Bundle(2).apply {
            putParcelable(ARG_DEVICE_INFO, deviceInfo)
            putParcelable(ARG_NETWORK_CREDENTIALS, networkCredentialsParcelable)
          }
      }
    }
  }
}
