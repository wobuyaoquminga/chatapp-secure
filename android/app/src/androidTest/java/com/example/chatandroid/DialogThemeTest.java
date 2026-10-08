package com.example.chatandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DialogThemeTest {
    @Test public void ordinaryDialogsUseWindowAnimationsAndAccessibleActions() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity = QaActivityLauncher.launch(instrumentation);
        QaActivityLauncher.settleInitialState(instrumentation, activity);
        try {
            instrumentation.runOnMainSync(() -> {
                AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("测试弹框")
                        .setNegativeButton("取消", null).setPositiveButton("确定", null).create();
                try {
                    dialog.show();
                    assertEquals(R.style.ChatDialogAnimation, dialog.getWindow().getAttributes().windowAnimations);
                    int min = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                    Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                    assertTrue(positive.getMinimumHeight() >= min);
                    assertTrue(negative.getMinimumHeight() >= min);
                } finally {
                    dialog.dismiss();
                }
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
