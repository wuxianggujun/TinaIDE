package com.wuxianggujun.tinaide.plugin.runtime.ipc;

import android.os.ParcelFileDescriptor;

interface IPluginHostBridge {
    String call(String requestJson);
    ParcelFileDescriptor openLuaModule(String requestJson);
    ParcelFileDescriptor openPayload(String token);
}
