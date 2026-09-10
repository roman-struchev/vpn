package com.vpn.android.util;

import android.os.Handler;
import android.os.Looper;

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
