/*
 *
 *    Copyright (c) 2022 Texas Instruments Incorporated
 *    All rights reserved.
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

#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "FreeRTOS.h"
#include "semphr.h"
#include "task.h"

#include <platform/CHIPDeviceLayer.h>

#ifdef CC13XX_26XX_FACTORY_DATA
#include <platform/cc13xx_26xx/FactoryDataProvider.h>
#endif

class AppTask
{
public:
    CHIP_ERROR StartAppTask();
    static void AppTaskMain(void * pvParameter);

private:
    friend AppTask & GetAppTask(void);

    CHIP_ERROR Init();

    static AppTask sAppTask;

#ifdef CC13XX_26XX_FACTORY_DATA
    chip::DeviceLayer::FactoryDataProvider mFactoryDataProvider;
#endif
};

inline AppTask & GetAppTask(void)
{
    return AppTask::sAppTask;
}
