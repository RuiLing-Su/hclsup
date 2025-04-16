package com.hcbt.hcisup.common;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FFmpeg 流处理器
 * 用于管理 FFmpeg 进程，将 H.265 视频流转码为 H.264 并推送到 RTMP URL，支持 SRS 服务通过 HLS 分发
 */
@Slf4j
public class FFmpegStreamHandler {

    // 存储用户对应的 FFmpeg 进程
    private static final ConcurrentHashMap<Integer, Process> ffmpegProcesses = new ConcurrentHashMap<>();
    // 存储 FFmpeg 进程的标准输入流
    private static final ConcurrentHashMap<Integer, OutputStream> ffmpegOutputStreams = new ConcurrentHashMap<>();
    // 标记 FFmpeg 进程是否正在运行
    private static final ConcurrentHashMap<Integer, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    // 保存每个用户的目标 HLS 路径用于重启
    private static final ConcurrentHashMap<Integer, String> streamUrls = new ConcurrentHashMap<>();
    // 最大重启尝试次数
    private static final int MAX_RESTART_ATTEMPTS = 3;
    // 记录每个用户的重启次数
    private static final ConcurrentHashMap<Integer, Integer> restartAttempts = new ConcurrentHashMap<>();

    /**
     * 启动 FFmpeg 进程
     *
     * @param luserId 用户 ID
     * @param hlsPath 目标 HLS 文件路径（例如 /var/www/hls/stream_1.m3u8）
     * @return 是否成功启动
     */
    public static boolean startFFmpeg(Integer luserId, String hlsPath) {
        log.info("尝试为用户 ID: {} 启动 FFmpeg，目标 HLS 路径: {}", luserId, hlsPath);

        // 检查 FFmpeg 是否可用
        if (!isFFmpegInstalled()) {
            log.error("FFmpeg 未安装或无法访问");
            return false;
        }

        // 执行进程启动逻辑
        return startFFmpegProcess(luserId, hlsPath);
    }

    /**
     * 检查 FFmpeg 是否已安装
     *
     * @return FFmpeg 是否可用
     */
    private static boolean isFFmpegInstalled() {
        try {
            Process checkProcess = new ProcessBuilder("ffmpeg", "-version").start();
            int exitCode = checkProcess.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.error("检查 FFmpeg 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 启动 FFmpeg 进程的核心逻辑
     *
     * @param luserId 用户 ID
     * @param hlsPath 目标 HLS 文件路径
     * @return 是否成功启动
     */
    private static boolean startFFmpegProcess(Integer luserId, String hlsPath) {
        // 停止已有的进程
        stopExistingProcess(luserId);

        try {
            // 确保 HLS 目录存在
            File hlsDir = new File(new File(hlsPath).getParent());
            if (!hlsDir.exists()) {
                if (!hlsDir.mkdirs()) {
                    log.error("无法创建 HLS 目录: {}", hlsDir.getAbsolutePath());
                    return false;
                }
            }

            // 构建 FFmpeg 命令
            List<String> command = buildFFmpegCommand(hlsPath);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true); // 合并标准错误和标准输出
            log.info("启动 FFmpeg 进程，命令: {}", command);

            // 启动进程
            Process process = pb.start();
            ffmpegProcesses.put(luserId, process);

            // 获取标准输入流
            OutputStream outputStream = process.getOutputStream();
            ffmpegOutputStreams.put(luserId, outputStream);

            // 获取 FFmpeg 输出流
            final var inputStream = process.getInputStream();

            // 启动线程记录 FFmpeg 输出日志
            new Thread(() -> logFFmpegOutput(luserId, inputStream)).start();

            // 启动线程监控进程状态
            new Thread(() -> monitorProcessStatus(luserId, process)).start();

            // 初始化运行状态
            streamUrls.put(luserId, hlsPath);
            runningFlags.put(luserId, new AtomicBoolean(true));
            restartAttempts.put(luserId, 0);

            return true;
        } catch (IOException e) {
            log.error("启动 FFmpeg 进程失败，用户 ID: {}", luserId, e);
            return false;
        }
    }

    /**
     * 构建 FFmpeg 命令，将 H.265 转码为 H.264 并输出为 HLS
     *
     * @param hlsPath 目标 HLS 文件路径
     * @return FFmpeg 命令列表
     */
    private static List<String> buildFFmpegCommand(String hlsPath) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-f");
        command.add("hevc"); // 输入格式为 H.265 裸流
        command.add("-i");
        command.add("pipe:0"); // 从标准输入读取数据
        command.add("-c:v");
        command.add("libx264"); // 转码为 H.264
        command.add("-g");
        command.add("50"); // 设置 GOP 大小为 50 帧
        command.add("-keyint_min");
        command.add("25"); // 最小关键帧间隔
        command.add("-preset");
        command.add("veryfast"); // 使用更快的预设以降低延迟
        command.add("-tune");
        command.add("zerolatency"); // 优化实时流
        command.add("-an"); // 无音频
        command.add("-f");
        command.add("hls"); // 输出格式为 HLS
        command.add("-hls_time");
        command.add("2"); // 每个分段2秒，降低延迟
        command.add("-hls_list_size");
        command.add("3"); // 播放列表保留3个分段
        command.add("-hls_flags");
        command.add("delete_segments+append_list"); // 删除旧分段并支持动态追加
        command.add("-hls_segment_type");
        command.add("mpegts"); // 使用MPEG-TS分段
        command.add(hlsPath); // 目标 HLS 文件路径
        return command;
    }

    /**
     * 记录 FFmpeg 的输出日志
     *
     * @param luserId     用户 ID
     * @param inputStream FFmpeg 输出流
     */
    private static void logFFmpegOutput(Integer luserId, java.io.InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null && runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
                log.debug("FFmpeg 输出 [用户 ID: {}]: {}", luserId, line);
            }
        } catch (IOException e) {
            if (runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
                log.error("读取 FFmpeg 输出失败，用户 ID: {}", luserId, e);
            }
        }
    }

    /**
     * 监控 FFmpeg 进程状态并处理意外退出
     *
     * @param luserId 用户 ID
     * @param process FFmpeg 进程
     */
    private static void monitorProcessStatus(Integer luserId, Process process) {
        try {
            int exitCode = process.waitFor();
            log.info("FFmpeg 进程退出，用户 ID: {}，退出码: {}", luserId, exitCode);

            // 如果进程意外退出且仍在运行状态，尝试重启
            if (runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
                restartProcess(luserId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("进程监控被中断，用户 ID: {}", luserId);
        }
    }

    /**
     * 停止现有的 FFmpeg 进程
     *
     * @param luserId 用户 ID
     */
    private static void stopExistingProcess(Integer luserId) {
        Process oldProcess = ffmpegProcesses.remove(luserId);
        OutputStream oldOutputStream = ffmpegOutputStreams.remove(luserId);

        if (oldOutputStream != null) {
            try {
                oldOutputStream.close();
            } catch (IOException e) {
                log.error("关闭旧 FFmpeg 输出流失败，用户 ID: {}", luserId, e);
            }
        }

        if (oldProcess != null) {
            try {
                oldProcess.destroyForcibly().waitFor();
                log.info("旧 FFmpeg 进程已终止，用户 ID: {}", luserId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("等待旧进程终止时被中断，用户 ID: {}", luserId);
            }
        }
    }

    /**
     * 将视频数据写入 FFmpeg 进程的标准输入
     *
     * @param luserId 用户 ID
     * @param data    视频数据
     */
    public static void writeData(Integer luserId, byte[] data) {
        if (!isProcessAlive(luserId)) {
            log.warn("无法写入数据，用户 ID: {} 的 FFmpeg 进程未运行", luserId);
            return;
        }

        if (data.length < 10) {
            log.warn("数据太小，用户 ID: {}，可能是无效数据: {} 字节", luserId, data.length);
            return;
        }

        OutputStream outputStream = ffmpegOutputStreams.get(luserId);
        if (outputStream != null) {
            try {
                outputStream.write(data);
                outputStream.flush();
            } catch (IOException e) {
                log.error("写入数据到 FFmpeg 失败，用户 ID: {}", luserId, e);
                restartProcess(luserId);
            }
        }
    }

    /**
     * 尝试重启 FFmpeg 进程
     *
     * @param luserId 用户 ID
     */
    private static void restartProcess(Integer luserId) {
        if (!runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
            return;
        }

        int attempts = restartAttempts.getOrDefault(luserId, 0);
        if (attempts < MAX_RESTART_ATTEMPTS) {
            log.warn("尝试重启 FFmpeg，用户 ID: {} (第 {}/{} 次)", luserId, attempts + 1, MAX_RESTART_ATTEMPTS);
            restartAttempts.put(luserId, attempts + 1);
            String savedUrl = streamUrls.get(luserId);
            if (savedUrl != null) {
                startFFmpegProcess(luserId, savedUrl);
            }
        } else {
            log.error("达到最大重启尝试次数，用户 ID: {}", luserId);
            runningFlags.put(luserId, new AtomicBoolean(false));
        }
    }

    /**
     * 停止 FFmpeg 进程
     *
     * @param luserId 用户 ID
     */
    public static void stopFFmpeg(Integer luserId) {
        runningFlags.put(luserId, new AtomicBoolean(false));
        restartAttempts.remove(luserId);
        streamUrls.remove(luserId);

        Process process = ffmpegProcesses.remove(luserId);
        OutputStream outputStream = ffmpegOutputStreams.remove(luserId);

        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                log.error("关闭 FFmpeg 输出流失败，用户 ID: {}", luserId, e);
            }
        }

        if (process != null) {
            try {
                process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                process.destroyForcibly();
                log.info("FFmpeg 进程已终止，用户 ID: {}", luserId);
            }
        }
    }

    /**
     * 检查 FFmpeg 进程是否存活
     *
     * @param luserId 用户 ID
     * @return 进程是否存活
     */
    public static boolean isProcessAlive(Integer luserId) {
        Process process = ffmpegProcesses.get(luserId);
        return process != null && process.isAlive() &&
                runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get();
    }
}