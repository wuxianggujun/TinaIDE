package com.tinaide.template.nativeactivity;

import android.app.NativeActivity;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.tinaide.template.common.TemplatePermissionFlow;
import com.tinaide.template.common.TemplateRuntimeExitGuard;

/**
 * 模板专用 NativeActivity。权限 flow 统一由 {@link TemplatePermissionFlow} 驱动。
 */
public class TemplateNativeActivity extends NativeActivity {

    private TemplatePermissionFlow permissionFlow;
    private final TemplateRuntimeExitGuard exitGuard = new TemplateRuntimeExitGuard(
        () -> Process.killProcess(Process.myPid())
    );
    private OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionFlow = new TemplatePermissionFlow(this);
        permissionFlow.advance("onCreate");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isFinishing()) {
            // A launcher root's default Back can only background the task on Android 12+.
            // Explicitly finish so the old native loop cannot overlap the next launch.
            backCallback = this::finish;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        permissionFlow.advance("onResume");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finish();
    }

    @Override
    public void finish() {
        exitGuard.requestExit();
        super.finish();
    }

    @Override
    protected void onDestroy() {
        boolean exiting = isFinishing() && !isChangingConfigurations();
        if (exiting) {
            exitGuard.requestExit();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        try {
            super.onDestroy();
        } finally {
            if (exiting) {
                exitGuard.onDestroyed();
            }
        }
    }
}
