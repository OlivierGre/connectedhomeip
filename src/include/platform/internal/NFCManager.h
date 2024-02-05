/*
 *
 *    Copyright (c) 2020 Project CHIP Authors
 *    Copyright (c) 2018 Nest Labs, Inc.
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
 *          Defines the abstract interface for the Device Layer's
 *          internal NFCManager object.
 */

#pragma once

#include <lib/support/CodeUtils.h>
#include <platform/ConnectivityManager.h>

#if CHIP_DEVICE_CONFIG_ENABLE_CHIPONFC

namespace chip {
namespace DeviceLayer {
namespace Internal {

class NFCManagerImpl;

/**
 * Provides control over CHIPoNFC services and connectivity for a chip device.
 *
 * NFCManager defines the abstract interface of a singleton object that provides
 * control over CHIPoNFC services and connectivity for a chip device.  NFCManager
 * is an internal object that is used by other components with the chip Device
 * Layer, but is not directly accessible to the application.
 */
class NFCManager
{
    using ImplClass = NFCManagerImpl;

public:
    // ===== Members that define the internal interface of the NFCManager

    using CHIPoNFCServiceMode = ConnectivityManager::CHIPoNFCServiceMode;

    CHIP_ERROR Init();

protected:
    // Construction/destruction limited to subclasses.
    NFCManager()  = default;
    ~NFCManager() = default;

    // No copy, move or assignment.
    NFCManager(const NFCManager &)  = delete;
    NFCManager(const NFCManager &&) = delete;
    NFCManager & operator=(const NFCManager &) = delete;
};

/**
 * Returns a reference to the public interface of the NFCManager singleton object.
 *
 * Internal components should use this to access features of the NFCManager object
 * that are common to all platforms.
 */
extern NFCManager & NFCMgr();

/**
 * Returns the platform-specific implementation of the NFCManager singleton object.
 *
 * chip applications can use this to gain access to features of the NFCManager
 * that are specific to the selected platform.
 */
extern NFCManagerImpl & NFCMgrImpl();

} // namespace Internal
} // namespace DeviceLayer
} // namespace chip

/* Include a header file containing the implementation of the NFCManager
 * object for the selected platform.
 */
#ifdef EXTERNAL_NFCMANAGERIMPL_HEADER
#include EXTERNAL_NFCMANAGERIMPL_HEADER
#elif defined(CHIP_DEVICE_LAYER_TARGET)
#define NFCMANAGERIMPL_HEADER <platform/CHIP_DEVICE_LAYER_TARGET/NFCManagerImpl.h>
#include NFCMANAGERIMPL_HEADER
#endif // defined(CHIP_DEVICE_LAYER_TARGET)


namespace chip {
namespace DeviceLayer {
namespace Internal {

inline CHIP_ERROR NFCManager::Init()
{
    return static_cast<ImplClass *>(this)->_Init();
}


} // namespace Internal
} // namespace DeviceLayer
} // namespace chip

#endif // CHIP_DEVICE_CONFIG_ENABLE_CHIPONFC
