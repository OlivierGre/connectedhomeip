/*
 *
 *    Copyright (c) 2020-2021 Project CHIP Authors
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
 *          Provides an implementation of the NFCManager singleton object
 *          for Android platforms.
 */
#include <platform/internal/CHIPDeviceLayerInternal.h>

#include <lib/support/CHIPJNIError.h>
#include <lib/support/CodeUtils.h>
#include <lib/support/JniReferences.h>
#include <lib/support/SafeInt.h>
#include <platform/internal/NFCManager.h>

#if CHIP_DEVICE_CONFIG_ENABLE_CHIPONFC

using namespace chip;
using namespace ::nl;
using namespace ::chip::Nfc;

namespace chip {
namespace DeviceLayer {
namespace Internal {

namespace {

} // namespace

NFCManagerImpl NFCManagerImpl::sInstance;

void NFCManagerImpl::InitializeWithObject(jobject manager)
{
    ChipLogProgress(DeviceLayer, "NFCManagerImpl::InitializeWithObject()");

   JNIEnv * env = JniReferences::GetInstance().GetEnvForCurrentThread();
    VerifyOrReturn(env != nullptr, ChipLogError(DeviceLayer, "Failed to GetEnvForCurrentThread for NFCManager"));

    mNFCManagerObject = env->NewGlobalRef(manager);
    VerifyOrReturn(mNFCManagerObject != nullptr, ChipLogError(DeviceLayer, "Failed to NewGlobalRef NFCManager"));

    jclass NFCManagerClass = env->GetObjectClass(manager);
    VerifyOrReturn(NFCManagerClass != nullptr, ChipLogError(DeviceLayer, "Failed to get NFCManagerClass Java class"));

    mInitMethod = env->GetMethodID(NFCManagerClass, "init", "()I");
    if (mInitMethod == nullptr)
    {
        ChipLogError(DeviceLayer, "Failed to access NFCManager 'init' method");
        env->ExceptionClear();
    }

    mSendToNfcTagCallback = env->GetMethodID(NFCManagerClass, "sendToNfcTag", "([B)V");
    if (mSendToNfcTagCallback == nullptr)
    {
        ChipLogError(Controller, "Failed to access callback 'sendToNfcTag' method");
        env->ExceptionClear();
    }

}

// ===== start impl of NFCManager internal interface, ref NFCManager.h

CHIP_ERROR NFCManagerImpl::_Init()
{
    ChipLogProgress(DeviceLayer, "NFCManagerImpl::_Init()");

    return CHIP_NO_ERROR;
}

// ===== start implement virtual methods on NfcApplicationDelegate.

void NFCManagerImpl::SetNFCBase(Transport::NFCBase * nfcBase)
{
    ChipLogProgress(DeviceLayer, "NFCManagerImpl::SetNFCBase()");
    mNFCBase = nfcBase;
}

CHIP_ERROR NFCManagerImpl::SendToNfcTag(System::PacketBufferHandle && msgBuf)
{
    ChipLogProgress(DeviceLayer, "NFCManagerImpl::SendToNfcTag()");

    const uint8_t * buffer = msgBuf->Start();
    uint32_t len = msgBuf->DataLength();

    JNIEnv * env  = JniReferences::GetInstance().GetEnvForCurrentThread();
    jbyteArray jbArray = env->NewByteArray((int) len);
    env->SetByteArrayRegion(jbArray, 0, (int) len, (jbyte*) buffer);
    env->CallVoidMethod(mNFCManagerObject, mSendToNfcTagCallback, jbArray);

    return CHIP_NO_ERROR;
}

CHIP_ERROR NFCManagerImpl::OnNfcTagResponse(jbyteArray jbArray)
{
    ChipLogProgress(DeviceLayer, "NFCManagerImpl::OnNfcTagResponse()");

    if (mNFCBase == NULL)
    {
        ChipLogError(DeviceLayer, "Error! mNFCBase is null!");
        return CHIP_ERROR_INCORRECT_STATE;
    }

    JNIEnv * env  = JniReferences::GetInstance().GetEnvForCurrentThread();

    jbyte * data = env->GetByteArrayElements(jbArray, nullptr);
    jsize length = env->GetArrayLength(jbArray);

    System::PacketBufferHandle buffer =  System::PacketBufferHandle::NewWithData(reinterpret_cast<const uint8_t *>(data), static_cast<size_t>(length));

    mNFCBase->OnNfcTagResponse(std::move(buffer));

    return CHIP_NO_ERROR;
}

} // namespace Internal
} // namespace DeviceLayer
} // namespace chip

#endif // CHIP_DEVICE_CONFIG_ENABLE_CHIPONFC
