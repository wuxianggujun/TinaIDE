package com.wuxianggujun.tinaide.plugin.runtime.ipc;

import android.os.ParcelFileDescriptor;
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginHostBridge;
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginRuntimeCallback;

interface IPluginRuntimeService {
    int getProcessId();
    void load(
        String requestJson,
        in ParcelFileDescriptor mainSource,
        IPluginHostBridge hostBridge,
        IPluginRuntimeCallback callback
    );
    void invoke(String requestJson, IPluginRuntimeCallback callback);
    void unload(String requestJson, IPluginRuntimeCallback callback);
    oneway void terminate();
}
