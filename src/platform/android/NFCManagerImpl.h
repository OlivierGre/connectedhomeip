/*
 *
 *    Copyright (c) 2020-2021 Project CHIP Authors
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

/**
 *    @file
 *          Provides an implementation of the NFCManager singleton object
 *          for the Android platforms.
 */

#pragma once

#include <jni.h>

#include <nfc/NfcApplicationDelegate.h>

#include <platform/internal/NFCManager.h>

#if CHIP_DEVICE_CONFIG_ENABLE_CHIPONFC

namespace chip {
namespace DeviceLayer {
namespace Internal {

/**
 * Concrete implementation of the NFCManagerImpl singleton object for the Linux platforms.
 */
class NFCManagerImpl final : public NFCManager,
                             private Nfc::NfcApplicationDelegate
{
    // Allow the NFCManager interface class to delegate method calls to
    // the implementation methods provided by this class.
    friend NFCManager;

public:
    CHIP_ERROR ConfigureNfc(uint32_t aNodeId, bool aIsCentral);

    void InitializeWithObject(jobject managerObject);

    CHIP_ERROR OnNfcTagResponse(jbyteArray jbArray);

    // ===== Members that implement virtual methods on NfcApplicationDelegate.

    void SetNFCBase(Transport::NFCBase * nfcBase) override;

    CHIP_ERROR SendToNfcTag(System::PacketBufferHandle && msgBuf) override;

private:
    // ===== Members that implement the NFCManager internal interface.

    CHIP_ERROR _Init();

    // ===== Members for internal use by the following friends.

    friend NFCManager & NFCMgr();
    friend NFCManagerImpl & NFCMgrImpl();

    static NFCManagerImpl sInstance;

    jobject mNFCManagerObject = nullptr;
    jmethodID mInitMethod = nullptr;
    jmethodID mSendToNfcTagCallback = nullptr;

    Transport::NFCBase * mNFCBase = nullptr;
};

/**
 * Returns a reference to the public interface of the NFCManager singleton object.
 *
 * Internal components should use this to access features of the NFCManager object
 * that are common to all platforms.
 */
inline NFCManager & NFCMgr()
{
    return NFCManagerImpl::sInstance;
}

/**
 * Returns the platform-specific implementation of the NFCManager singleton object.
 *
 * Internal components can use this to gain access to features of the NFCManager
 * that are specific to the Linux platforms.
 */
inline NFCManagerImpl & NFCMgrImpl()
{
    return NFCManagerImpl::sInstance;
}

} // namespace Internal
} // namespace DeviceLayer
} // namespace chip

#endif // CHIP_DEVICE_CONFIG_ENABLE_CHIPONFC
