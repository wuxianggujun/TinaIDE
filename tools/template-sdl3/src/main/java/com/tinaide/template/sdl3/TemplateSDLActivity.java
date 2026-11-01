package com.tinaide.template.sdl3;

import android.os.Bundle;
import android.os.Process;
import android.view.Window;

import com.tinaide.template.common.TemplatePermissionFlow;
import com.tinaide.template.common.TemplateRuntimeExitGuard;

import org.libsdl.app.SDLActivity;

/**
 * 模板专用 SDL Activity。
 * SDL 上游默认会在启动时调用 setWindowStyle(false)，导致状态栏/顶栏重新出现。
 * 这里在关键生命周期里强制恢复全屏沉浸式样式；权限 flow 统一由 {@link TemplatePermissionFlow} 驱动。
 */
public class TemplateSDLActivity extends SDLActivity {

    private TemplatePermissionFlow permissionFlow;
    private final TemplateRuntimeExitGuard exitGuard = new TemplateRuntimeExitGuard(
        () -> Process.killProcess(Process.myPid())
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setWindowStyle(true);
        permissionFlow = new TemplatePermissionFlow(this);
        permissionFlow.advance("onCreate");
    }

    @Override
    protected void onResume() {
        super.onResume();
        setWindowStyle(true);
        permissionFlow.advance("onResume");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setWindowStyle(true);
        }
    }

    @Override
    protected void performSystemBack() {
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
        try {
            super.onDestroy();
        } finally {
            if (exiting) {
                exitGuard.onDestroyed();
            }
        }
    }
}
