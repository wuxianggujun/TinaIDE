package com.wuxianggujun.tinaide.plugin.runtime.ipc;

oneway interface IPluginRuntimeCallback {
    void onComplete(String responseJson);
}
