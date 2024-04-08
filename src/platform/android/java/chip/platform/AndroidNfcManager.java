/*
 *   Copyright (c) 2021 Project CHIP Authors
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
package chip.platform;

import android.os.Build;
import android.util.Log;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AndroidNfcManager implements NfcManager {

  private static final String TAG = AndroidNfcManager.class.getSimpleName();
  private AndroidChipPlatform mPlatform;
  private NfcCallback mNfcCallback;
  private IsoDep mIsoDep = null;

  private static final int TYPE4_EXTENDED_ADPU_MAX_SIZE  = 63*1024;
  // Max length for a Type4 R-APDU (as defined in the NFC Forum Type4 Tag TS)
  private static final int TYPE4_MAX_RADPU_SIZE  = 246;
  // Max length for a Type4 C-APDU (as defined in the NFC Forum Type4 Tag TS)
  private static final int TYPE4_MAX_CAPDU_SIZE  = 246;
  private static final int TYPE4_HEADER_SIZE = 4;
  private static final byte TYPE4_CMD_SELECT = (byte) 0xA4;
  private static final byte TYPE4_CMD_SELECT_BY_NAME    = (byte) 0x04;
  private static final byte TYPE4_CMD_SELECT_BY_FILE_ID = (byte) 0x00;
  private static final byte TYPE4_CMD_FIRST_OR_ONLY_OCCURENCE = (byte) 0x0C;
  private static final byte TYPE4_CMD_READ_BINARY = (byte) 0xB0;


  @Override
  public void setNfcCallback(NfcCallback nfcCallback) {
    mNfcCallback = nfcCallback;
  }

  @Override
  public NfcCallback getCallback() {
    return mNfcCallback;
  }

  @Override
  public int init() {
    return 0;
  }

  @Override
  public void setAndroidChipPlatform(AndroidChipPlatform platform) {
    mPlatform = platform;
  }

  @Override
  public void sendToNfcTag(byte[] buf) {
    Log.d(TAG, "AndroidNfcManager sendToNfcTag");

    new Thread() {
        @Override
        public void run() {
          try {
            byte[] response = sendExtendedAPDU(buf);
            if (response != null) {
                //Log.d(TAG,"Transmit response: " + convertHexByteArrayToString(response) );
                mPlatform.onNfcTagResponse(response);
            }
          } catch (Exception e) {
              e.printStackTrace();
              mPlatform.onNfcTagError();
          }
        }
    }.start();
  }

  public void setNFCTag(Tag androidTag) {
    mIsoDep = IsoDep.get(androidTag);
    if (mIsoDep == null) {
        Log.e(TAG, "mIsoDep is null");
        return;
    }

    boolean isExtendedLengthApduSupported = mIsoDep.isExtendedLengthApduSupported();
    Log.d(TAG, "isExtendedLengthApduSupported: " + isExtendedLengthApduSupported);
    if (!isExtendedLengthApduSupported) {
      // For the moment, no alternative to extended APDU is implemented
      Log.e(TAG, "Error! Extended APDU are not supported by this tag!");
      mIsoDep = null;
      return;
    }

    new Thread() {
        @Override
        public void run() {
          try {
            selectMatterApplication();
          } catch (IOException e) {
              e.printStackTrace();
              mPlatform.onNfcTagError();
          }
        }
    }.start();

  }

  public byte[] transceive(String commandName, byte[] data) throws IOException {
      byte[] response;

    if (mIsoDep == null) {
        Log.e(TAG, "Error! mIsoDep is null!");
        return null;
    }

      if (!mIsoDep.isConnected()) {
          mIsoDep.close();
          mIsoDep.connect();
      }

      Log.d(TAG, "==> Send " + commandName + " command: " + convertHexByteArrayToString(data));

      response = mIsoDep.transceive(data);

      String frame = String.format("Response: %s", convertHexByteArrayToString(response));
      Log.d(TAG, frame);

      int len = response.length;
      if ((response == null) || (response.length < 2)) {
          Log.e(TAG, "Error! Invalid Type4 response");
          return null;
      }

      // Check the last 2 bytes containing the status
      if ((response[len-2] == ((byte) 0x90)) && (response[len-1] == 0x00)) {
          if (len == 2) {
              // There are only the 2 status bytes
              return null;
          } else {
              // Discard the 2 status bytes
              byte[] result = new byte[len-2];
              System.arraycopy(response, 0, result, 0, result.length);
              return result;
          }
      } else {
          Log.e(TAG, "Command failed");
          return null;
      }

  }

  public byte[] selectMatterApplication() throws IOException {
      byte[] response;
      byte[] frame = new byte[TYPE4_HEADER_SIZE + 13];

      frame[0] = 0x00;
      frame[1] = TYPE4_CMD_SELECT;
      frame[2] = TYPE4_CMD_SELECT_BY_NAME;
      frame[3] = 0x00;

      frame[4] = (byte) 0x0B;  //length
      frame[5] = (byte) 0xA0;
      frame[6] = (byte) 0x00;
      frame[7] = (byte) 0x00;
      frame[8] = (byte) 0x07;
      frame[9] = (byte) 0x08;
      frame[10]= (byte) 0x01;
      frame[11]= (byte) 0x00;
      frame[12]= (byte) 0x12;
      frame[13]= (byte) 0x01;
      frame[14]= (byte) 0x00;
      frame[15]= (byte) 0x01;
      frame[16]= (byte) 0x00;

      response = transceive("selectMatterApplication", frame);

      return response;
  }

  public byte[] sendExtendedAPDU(byte[] data) throws Exception {
      if(data.length > TYPE4_EXTENDED_ADPU_MAX_SIZE) {
          throw new Exception();
      }
      byte[] response;
      byte[] frame = new byte[7 + data.length];
      byte[] length = convertIntTo2BytesHexaFormat(data.length);

      frame[0] = (byte) 0x80;
      frame[1] = 0x20;
      frame[2] = 0x00;
      frame[3] = 0x00;
      frame[4] = 0x00;            // LC
      frame[5] = length[0];       // LC
      frame[6] = length[1];       // LC

      System.arraycopy(data, 0, frame, 7, data.length);

      response = transceive("extendedAPDU", frame);

      return response;
  }

    public static byte[] convertIntTo2BytesHexaFormat(int numberToConvert) throws Exception {
      if (numberToConvert >= 0 && numberToConvert <= 65535) {
          byte[] convertedNumber = new byte[]{(byte)((numberToConvert & '\uff00') >> 8), (byte)(numberToConvert & 255)};
          return convertedNumber;
      } else {
          throw new Exception();
      }
  }

  public static String convertHexByteArrayToString(byte[] in) {
      final StringBuilder builder = new StringBuilder();
      for(byte b : in) {
          builder.append(String.format("%02x ", b));
      }
      return builder.toString();
  }

}
