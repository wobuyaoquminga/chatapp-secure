package com.example.chatandroid;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Starts the QA Activity as shell because this device blocks background instrumentation launches. */
final class QaActivityLauncher {
    private static final long LAUNCH_TIMEOUT_MS = 8_000;

    private QaActivityLauncher() { }

    static MainActivity launch(Instrumentation instrumentation) throws Exception {
        String packageName = instrumentation.getTargetContext().getPackageName();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        String output;
        try {
            // UiAutomation runs am as the shell user, matching the successful manual device launch.
            String command = "am start -f 0x10008000 -n " + packageName + "/" + MainActivity.class.getName() + " 2>&1";
            try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         new ParcelFileDescriptor.AutoCloseInputStream(descriptor), StandardCharsets.UTF_8))) {
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line).append('\n');
                output = result.toString();
            }
            if (output.contains("Error:") || output.contains("Exception:"))
                throw new AssertionError("Shell could not start QA Activity: " + output);
            Activity activity = monitor.waitForActivityWithTimeout(LAUNCH_TIMEOUT_MS);
            if (!(activity instanceof MainActivity))
                throw new AssertionError("QA Activity did not resume within " + LAUNCH_TIMEOUT_MS
                        + " ms after shell launch: " + output);
            return (MainActivity) activity;
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    static void settleInitialState(Instrumentation instrumentation, MainActivity activity) throws Exception {
        Field controllerField = MainActivity.class.getDeclaredField("controller");
        controllerField.setAccessible(true);
        ChatController controller = (ChatController) controllerField.get(activity);
        Field workerField = ChatController.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        ExecutorService worker = (ExecutorService) workerField.get(controller);
        instrumentation.runOnMainSync(controller::close);
        if (!worker.awaitTermination(5, TimeUnit.SECONDS))
            throw new AssertionError("Initial QA controller did not stop");
        // Its final callback enters MainActivity.onState, which prepares and applies on two queues.
        instrumentation.waitForIdleSync();
        Field preparationField = MainActivity.class.getDeclaredField("uiPreparation");
        preparationField.setAccessible(true);
        ExecutorService preparation = (ExecutorService) preparationField.get(activity);
        preparation.submit(() -> { }).get(5, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
    }
}
