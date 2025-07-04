package com.hcbt.hcisup.common;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局推流路数管理类
 * @author llg
 * @slogan 致敬大师，致敬未来的你
 * @create 2025-06-03 16:33
 */
public class StreamLimitManager {
    private static final int MAX_STREAMS = 2; // 最多允许同时运行的总推流数
    private static final AtomicInteger runningStreams = new AtomicInteger(0);

    public static boolean tryAcquire() {
        while (true) {
            int current = runningStreams.get();
            if (current >= MAX_STREAMS) return false;
            if (runningStreams.compareAndSet(current, current + 1)) return true;
        }
    }

    public static void release() {
        runningStreams.decrementAndGet();
    }

    public static int getRunningCount() {
        return runningStreams.get();
    }
}
