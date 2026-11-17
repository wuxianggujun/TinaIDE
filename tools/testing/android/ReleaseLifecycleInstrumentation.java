package com.wuxianggujun.tinaide.testing;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** SDK-only lifecycle control; never loads a copy of the application's business code. */
public final class ReleaseLifecycleInstrumentation extends Instrumentation {
    private static final String APP = "com.wuxianggujun.tinaide";
    private final AtomicReference<Activity> resumed = new AtomicReference<>();
    private final AtomicInteger savedStateCreations = new AtomicInteger();
    private final AtomicInteger recreationRequests = new AtomicInteger();
    private final CountDownLatch completed = new CountDownLatch(1);
    private Bundle arguments;
    private volatile boolean finishRequested;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Application application = null;
        String runId = arguments.getString("regressionRunId", "");
        String component = arguments.getString("activity", APP + ".ui.MainPortalActivity");
        String action = APP + ".qa.lifecycle." + runId;
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                String orientation = arguments.getString("orientation", "portrait");
                activity.setRequestedOrientation("landscape".equals(orientation)
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                if (state != null && recreationRequests.get() > 0) {
                    savedStateCreations.incrementAndGet();
                    Bundle proof = new Bundle();
                    proof.putString("stream", "TINA_ACTIVITY_RECREATED:" + runId + "\n");
                    sendStatus(0, proof);
                }
            }
            @Override public void onActivityResumed(Activity activity) { resumed.set(activity); }
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        };
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!action.equals(intent.getAction())) return;
                if ("finish".equals(intent.getStringExtra("command"))) {
                    finishRequested = true;
                    completed.countDown();
                } else if ("recreate".equals(intent.getStringExtra("command"))) {
                    Activity activity = resumed.get();
                    if (activity == null || activity.isFinishing()) return;
                    recreationRequests.incrementAndGet();
                    activity.recreate();
                } else if ("hide-ime".equals(intent.getStringExtra("command"))) {
                    Activity activity = resumed.get();
                    if (activity != null) {
                        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        keyboard.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
                    }
                }
            }
        };
        boolean registered = false;
        String failure = null;
        try {
            if (!runId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid run ID");
            if (!component.equals(APP + ".ui.MainPortalActivity")
                    && !component.equals(APP + ".settings.SettingsActivity")) {
                throw new IllegalArgumentException("Unsupported lifecycle target");
            }
            waitForIdleSync();
            application = (Application) getTargetContext().getApplicationContext();
            if (application == null) throw new IllegalStateException("Target application is not ready");
            application.registerActivityLifecycleCallbacks(callbacks);
            if (Build.VERSION.SDK_INT >= 33) {
                getTargetContext().registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_EXPORTED);
            } else {
                getTargetContext().registerReceiver(receiver, new IntentFilter(action));
            }
            registered = true;
            Intent activity = new Intent().setClassName(APP, component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (component.equals(APP + ".settings.SettingsActivity")) {
                activity.putExtra("extra_initial_route", "about");
            }
            getTargetContext().startActivity(activity);
            if (!completed.await(50, TimeUnit.SECONDS)) failure = "LIFECYCLE_TIMEOUT_50_SECONDS";
        } catch (Throwable error) {
            failure = error.getClass().getName();
        } finally {
            if (registered) getTargetContext().unregisterReceiver(receiver);
            if (application != null) application.unregisterActivityLifecycleCallbacks(callbacks);
        }
        Bundle result = new Bundle();
        try {
            JSONObject report = new JSONObject()
                    .put("runId", runId).put("activity", component)
                    .put("recreationRequests", recreationRequests.get())
                    .put("savedStateCreations", savedStateCreations.get())
                    .put("passed", failure == null && finishRequested);
            if (failure != null) report.put("failure", failure);
            result.putString("stream", "TINA_LIFECYCLE_REPORT:" + report + "\n");
        } catch (Exception error) {
            result.putString("stream", "LIFECYCLE_REPORT_SERIALIZATION_FAILED\n");
        }
        finish(failure == null && finishRequested ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
