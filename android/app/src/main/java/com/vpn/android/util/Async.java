package com.vpn.android.util;

import android.os.Handler;
import android.os.Looper;

import androidx.fragment.app.Fragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal background-work helper so UI code isn't full of raw Thread/Executor boilerplate. */
public final class Async {

    public interface Task<T> {
        T call() throws Exception;
    }

    public interface OnSuccess<T> {
        void accept(T result);
    }

    public interface OnError {
        void accept(Exception error);
    }

    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {
    }

    /**
     * The same thing, but the callbacks are dropped if {@code fragment}'s view
     * is gone by the time the work finishes.
     *
     * This is the overload UI code should use. Switching tabs while a request
     * is in flight destroys the fragment's view, and the plain {@link #run}
     * below posts the result regardless — straight into a callback that reads
     * a now-null binding, or calls requireContext() on a detached fragment.
     * Reported as "приложение вылетает" when switching tabs during loading,
     * and it was every screen, not one: the crash belongs to the helper, not
     * to any single callback that forgot a null check.
     */
    public static <T> void run(Fragment fragment, Task<T> task, OnSuccess<T> onSuccess, OnError onError) {
        run(task,
                result -> {
                    if (isViewAlive(fragment)) onSuccess.accept(result);
                },
                error -> {
                    if (isViewAlive(fragment)) onError.accept(error);
                });
    }

    /** Has a view and is still attached — i.e. its binding and its context are safe to touch. */
    private static boolean isViewAlive(Fragment fragment) {
        return fragment != null && fragment.isAdded() && fragment.getView() != null;
    }

    public static <T> void run(Task<T> task, OnSuccess<T> onSuccess, OnError onError) {
        IO.execute(() -> {
            try {
                T result = task.call();
                MAIN.post(() -> onSuccess.accept(result));
            } catch (Exception e) {
                MAIN.post(() -> onError.accept(e));
            }
        });
    }
}
