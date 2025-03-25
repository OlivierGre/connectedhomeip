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
import android.graphics.Typeface
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import chip.devicecontroller.AttestationInfo
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.DeviceAttestationDelegate
import chip.devicecontroller.ICDDeviceInfo
import chip.devicecontroller.ICDRegistrationInfo
import chip.devicecontroller.MatterCommissioningProgressListener
import chip.devicecontroller.NetworkCredentials
import com.google.chip.chiptool.CHIPToolActivity
import com.google.chip.chiptool.ChipClient
import chip.platform.AndroidChipPlatform.ServiceResolveListener
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
import matter.onboardingpayload.DiscoveryCapability
import java.util.Locale

@ExperimentalCoroutinesApi
class DeviceProvisioningFragment : Fragment(), ServiceResolveListener {

  private lateinit var deviceInfo: CHIPDeviceInfo

  private var gatt: BluetoothGatt? = null

  private val networkCredentialsParcelable: NetworkCredentialsParcelable?
    get() = arguments?.getParcelable(ARG_NETWORK_CREDENTIALS)

  private lateinit var deviceController: ChipDeviceController
  private var deviceId : Long = 0

  private var mImageViewInProgress: ImageView? = null

  private lateinit var scope: CoroutineScope

  private var dialog: AlertDialog? = null

  // Commissioning stages
  var kError: Int = 0
  var kReadCommissioningInfo: Int = 0
  var kSendDACCertificateRequest: Int = 0
  var kSendNOC: Int = 0
  var kThreadNetworkSetup: Int = 0
  var kWaitForDeviceInstallation: Int = 0
  var kFindOperationalForStayActive: Int = 0
  var kSendComplete: Int = 0

  private var commissioningAlertDialogView: View? = null

  private var mCurrentCommissioningStage : Int = kReadCommissioningInfo
  private var operationalDiscoveryDone = false
  private var commissioningCompleted = false

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
          val isNFCCommissioningSupported = deviceInfo.discoveryCapabilities.contains(DiscoveryCapability.NFC)
          var androidNfcTag = CHIPToolActivity.getAndroidNfcTag();
          if ((androidNfcTag != null) && (isNFCCommissioningSupported)) {
            // Commissioning over NFC
            startConnectingToDeviceViaNfc(androidNfcTag)
          } else {
            // Commissioning over BLE
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

  inner class ChipMatterCommissioningProgressListener : MatterCommissioningProgressListener {
    override fun onNewCommissioningStage(stage: Int) {
      Log.d("DeviceProvisioning", "New commissioning stage: " + stage)
      displayProgress(stage)
    }
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

  private fun getCommissioningStageValues() {
    var chipDeviceController = ChipClient.getDeviceController(requireActivity())

    // Retrieve some values of CommissioningStage enum (as defined in CommissioningDelegate.h)
    // MatterCommissioningProgressListener() can then be used to know the current CommissioningStage.
    // The current CommissioningStage is compared to the values returned by the functions below to
    // know at which stage of the commissioning we are.
    kError = chipDeviceController.getCommissioningStagekErrorValue()
    kReadCommissioningInfo = chipDeviceController.getCommissioningStagekReadCommissioningInfoValue()
    kSendDACCertificateRequest = chipDeviceController.getCommissioningStagekSendDACCertificateRequestValue()
    kSendNOC = chipDeviceController.getCommissioningStagekSendNOCValue()
    kThreadNetworkSetup = chipDeviceController.getCommissioningStagekThreadNetworkSetupValue()
    kWaitForDeviceInstallation = chipDeviceController.getCommissioningStagekWaitForDeviceInstallationValue()
    kFindOperationalForStayActive = chipDeviceController.getCommissioningStagekFindOperationalForStayActiveValue()
    kSendComplete = chipDeviceController.getCommissioningStagekSendCompleteValue()

  }

  private fun startConnectingToDeviceViaNfc(nfcTag : Tag) {

    Log.d(TAG, "startConnectingToDeviceViaNfc. nfcTag: " + nfcTag.toString())

    getCommissioningStageValues()

    Log.d(TAG, "setMatterCommissioningProgressListener")
    ChipClient.getDeviceController(requireActivity()).setMatterCommissioningProgressListener(ChipMatterCommissioningProgressListener())

    ChipClient.getAndroidNfcCommissioningManager().setNFCTag(nfcTag)

    scope.launch {

      displayCommissioningProgress();

      deviceController.setCompletionListener(ConnectionCallback())

      deviceId = DeviceIdUtil.getNextAvailableId(requireContext())
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

      deviceController.pairDeviceViaNfc(deviceId, deviceInfo.setupPinCode, null, network)
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
        ICDRegistrationInfo.newBuilder().setICDStayActiveDurationMsec(30000L).build()
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

  fun displayCommissioningProgress() {

    requireActivity().runOnUiThread(java.lang.Runnable {
      val alertDialogBuilder = AlertDialog.Builder(requireActivity())

      // inflate XML content
      commissioningAlertDialogView =
        layoutInflater.inflate(R.layout.fragment_commissioning_progress, null)
      alertDialogBuilder
        .setTitle(getString(R.string.matter_commissioning_in_progress))
        .setCancelable(false)
        .setNegativeButton(
          "Close"
        ) { dialog, id ->
          if (deviceController != null) {
            try {
              if (!commissioningCompleted) {
                Log.d(TAG, "Last commissioning was not fully completed. Cancel it")
                deviceController.stopDevicePairing(deviceId)
              }
            } catch (e: Error) {
              e.printStackTrace()
            } catch (e: java.lang.Exception) {
              e.printStackTrace()
            }
          }
          commissioningAlertDialogView = null
          dialog.cancel()
        }
      alertDialogBuilder.setView(commissioningAlertDialogView)

      // create alert dialog
      val alertDialog = alertDialogBuilder.create()

      displayProgress(kReadCommissioningInfo)

      ChipClient.setServiceResolveListener(this)

      // show it
      alertDialog.show()
      alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
        resources.getColor(R.color.light_blue)
      )
    })
  }

  private fun setAnimatedImageView(imageView: ImageView?) {

    // If an animation is currently running, stop it
    if (mImageViewInProgress != null) {
      mImageViewInProgress!!.clearAnimation()
      mImageViewInProgress!!.setImageResource(R.drawable.ic_check_green_24dp)
      mImageViewInProgress = null
    }

    if (imageView == null) {
      return
    }

    imageView!!.setImageResource(R.drawable.circular_arrow_green)
    imageView!!.visibility = View.VISIBLE

    val anim = RotateAnimation(
      0.0f,
      360.0f,
      Animation.RELATIVE_TO_SELF,
      .5f,
      Animation.RELATIVE_TO_SELF,
      .5f
    )
    anim.interpolator = LinearInterpolator()
    anim.repeatCount = Animation.INFINITE
    anim.duration = 3000
    imageView.animation = anim
    imageView.startAnimation(anim)

    mImageViewInProgress = imageView
  }

  private fun displayProgress(stage: Int) {
    if (commissioningAlertDialogView != null) {
      requireActivity().runOnUiThread(java.lang.Runnable {
        if (commissioningAlertDialogView != null) {
          val imageViewInProgress: ImageView? = null
          val nfcImageView = commissioningAlertDialogView!!.findViewById<ImageView>(R.id.nfcImageView)
          val threadImageView =
            commissioningAlertDialogView!!.findViewById<ImageView>(R.id.threadImageView)
          val paseImageView = commissioningAlertDialogView!!.findViewById<ImageView>(R.id.paseImageView)
          val paseTextView = commissioningAlertDialogView!!.findViewById<TextView>(R.id.paseTextView)
          val deviceAttestationImageView =
            commissioningAlertDialogView!!.findViewById<ImageView>(R.id.deviceAttestationImageView)
          val deviceAttestationTextView =
            commissioningAlertDialogView!!.findViewById<TextView>(R.id.deviceAttestationTextView)
          val nocImageView = commissioningAlertDialogView!!.findViewById<ImageView>(R.id.nocImageView)
          val nocTextView = commissioningAlertDialogView!!.findViewById<TextView>(R.id.nocTextView)
          val operationalNetworkImageView =
            commissioningAlertDialogView!!.findViewById<ImageView>(R.id.operationalNetworkImageView)
          val operationalNetworkTextView =
            commissioningAlertDialogView!!.findViewById<TextView>(R.id.operationalNetworkTextView)
          val pleaseSwitchOnTheDeviceTextView =
            commissioningAlertDialogView!!.findViewById<TextView>(R.id.pleaseSwitchOnTheDeviceTextView)
          val clickHereWhenDoneTextView =
            commissioningAlertDialogView!!.findViewById<TextView>(R.id.clickHereWhenDoneTextView)
          val performServiceDiscoveryImageView =
            commissioningAlertDialogView!!.findViewById<ImageView>(R.id.performServiceDiscoveryImageView)
          val performServiceDiscoveryTextView =
            commissioningAlertDialogView!!.findViewById<TextView>(R.id.performServiceDiscoveryTextView)
          val caseImageView = commissioningAlertDialogView!!.findViewById<ImageView>(R.id.caseImageView)
          val caseTextView = commissioningAlertDialogView!!.findViewById<TextView>(R.id.caseTextView)
          val commissioningDoneTextView =
            commissioningAlertDialogView!!.findViewById<TextView>(R.id.commissioningDoneTextView)

          Log.d(TAG, "displayProgress $stage")
          mCurrentCommissioningStage = stage;

          when (stage) {
            kReadCommissioningInfo -> {
              commissioningCompleted = false
              setAnimatedImageView(paseImageView);
              nfcImageView.setImageResource(R.drawable.new_nfc_logo_black)
              threadImageView.setImageResource(R.drawable.thread_logo_grey)
              deviceAttestationImageView.visibility = View.INVISIBLE
              nocImageView.visibility = View.INVISIBLE
              operationalNetworkImageView.visibility = View.INVISIBLE
              performServiceDiscoveryImageView.visibility = View.INVISIBLE
              caseImageView.visibility = View.INVISIBLE
              paseTextView.setTextColor(resources.getColor(R.color.dark_blue))
              deviceAttestationTextView.setTextColor(resources.getColor(R.color.light_grey))
              nocTextView.setTextColor(resources.getColor(R.color.light_grey))
              operationalNetworkTextView.setTextColor(resources.getColor(R.color.light_grey))
              pleaseSwitchOnTheDeviceTextView.setTextColor(resources.getColor(R.color.light_grey))
              clickHereWhenDoneTextView.setTextColor(resources.getColor(R.color.light_grey))
              clickHereWhenDoneTextView.setClickable(false);
              performServiceDiscoveryTextView.setTextColor(resources.getColor(R.color.light_grey))
              caseTextView.setTextColor(resources.getColor(R.color.light_grey))
              commissioningDoneTextView.setTextColor(resources.getColor(R.color.light_grey))

              pleaseSwitchOnTheDeviceTextView.setTypeface(Typeface.DEFAULT)
              clickHereWhenDoneTextView.setTypeface(Typeface.DEFAULT)
              commissioningDoneTextView.setTypeface(Typeface.DEFAULT)
            }

            kSendDACCertificateRequest -> {
              setAnimatedImageView(deviceAttestationImageView);
              deviceAttestationTextView.setTextColor(resources.getColor(R.color.dark_blue))
            }

            kSendNOC -> {
              setAnimatedImageView(nocImageView);
              nocTextView.setTextColor(resources.getColor(R.color.dark_blue))
            }

            kThreadNetworkSetup -> {
              setAnimatedImageView(operationalNetworkImageView);
              operationalNetworkTextView.setTextColor(resources.getColor(R.color.dark_blue))
            }

            kWaitForDeviceInstallation -> {
              setAnimatedImageView(null);
              pleaseSwitchOnTheDeviceTextView.setTextColor(resources.getColor(R.color.dark_blue))
              pleaseSwitchOnTheDeviceTextView.setTypeface(Typeface.DEFAULT_BOLD)
              clickHereWhenDoneTextView.setTextColor(resources.getColor(R.color.light_blue))
              clickHereWhenDoneTextView.setTypeface(Typeface.DEFAULT_BOLD)
              clickHereWhenDoneTextView.setClickable(true)
              clickHereWhenDoneTextView.setOnClickListener(View.OnClickListener{
                operationalDiscoveryDone = false
                ChipClient.getDeviceController(requireActivity()).continueCommissioningOverOperationalNetwork()
              })
            }

            kFindOperationalForStayActive -> {
              if (operationalDiscoveryDone) {
                // Operational Discovery done so CASE is now starting
                setAnimatedImageView(caseImageView);
                caseTextView.setTextColor(resources.getColor(R.color.dark_blue))
              } else {
                setAnimatedImageView(performServiceDiscoveryImageView);
                nfcImageView.setImageResource(R.drawable.new_nfc_logo_grey)

                pleaseSwitchOnTheDeviceTextView.setTextColor(resources.getColor(R.color.light_grey))
                pleaseSwitchOnTheDeviceTextView.setTypeface(Typeface.DEFAULT)
                clickHereWhenDoneTextView.setTextColor(resources.getColor(R.color.light_grey))
                clickHereWhenDoneTextView.setTypeface(Typeface.DEFAULT)
                clickHereWhenDoneTextView.setClickable(false)

                threadImageView.setImageResource(R.drawable.thread_logo_black)
                performServiceDiscoveryTextView.setTextColor(resources.getColor(R.color.dark_blue))
              }
            }

            kSendComplete -> {
              setAnimatedImageView(null);
              commissioningDoneTextView.setTextColor(resources.getColor(R.color.dark_blue))
              commissioningDoneTextView.setTypeface(Typeface.DEFAULT_BOLD)
              commissioningCompleted = true
            }

            else -> Log.d(
              TAG,
              "No action for stage: ${stage}"
            )
          }
        }
      })
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
    const val TYPE4_EXTENDED_APDU_MAX_SIZE = 63 * 1024

    // Max length for a Type4 R-APDU (as defined in the NFC Forum Type4 Tag TS)
    const val TYPE4_MAX_RAPDU_SIZE = 246

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

  override fun onServiceResolve(instanceName : String, serviceType : String) {
    Log.d(TAG, "DeviceProvisioning: onServiceResolve: " + instanceName + "." + serviceType)
    if (mCurrentCommissioningStage == kFindOperationalForStayActive) {
      operationalDiscoveryDone = true
      displayProgress(mCurrentCommissioningStage);
    }
  }

}
