package com.hcbt.hcisup.common;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FFmpeg 流处理器
 */
@Slf4j
public class FFmpegStreamHandler {

    // 存储用户对应的 FFmpeg 进程
    private static final ConcurrentHashMap<Integer, Process> ffmpegProcesses = new ConcurrentHashMap<>();
    // 存储 FFmpeg 进程的标准输入流
    private static final ConcurrentHashMap<Integer, OutputStream> ffmpegOutputStreams = new ConcurrentHashMap<>();
    // 标记 FFmpeg 进程是否正在运行
    private static final ConcurrentHashMap<Integer, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    // 保存每个用户的当前通道号
    private static final ConcurrentHashMap<Integer, Integer> userChannels = new ConcurrentHashMap<>();
    // 最大重启尝试次数
    private static final int MAX_RESTART_ATTEMPTS = 3;
    // 记录每个用户的重启次数
    private static final ConcurrentHashMap<Integer, Integer> restartAttempts = new ConcurrentHashMap<>();
    // 用户操作锁，确保每个用户的操作是线程安全的
    private static final ConcurrentHashMap<Integer, ReentrantLock> userLocks = new ConcurrentHashMap<>();
    // HLS 根目录
    private static final String HLS_ROOT_DIR = "/home/elitedatai/hclsup_java/yolo123/hls/";
    // 帧图片根目录
    private static final String FRAMES_ROOT_DIR = "/home/elitedatai/hclsup_java/yolo123/hls/image/";

    /**
     * 获取用户锁（如果不存在则创建）
     */
    private static ReentrantLock getUserLock(Integer luserId) {
        return userLocks.computeIfAbsent(luserId, k -> new ReentrantLock());
    }

    /**
     * 启动 FFmpeg 进程（支持频道切换）- 主要接口
     *
     * @param luserId 用户 ID
     * @param channel 通道号
     * @return 是否成功启动
     */
    public static boolean startFFmpegForChannel(Integer luserId, int channel) {
        ReentrantLock lock = getUserLock(luserId);
        try {
            // 获取锁，确保用户操作的线程安全性
            if (!lock.tryLock(3, TimeUnit.SECONDS)) {
                log.warn("获取用户锁超时，用户 ID: {}", luserId);
                return false;
            }

            try {
                String hlsPath = "/home/elitedatai/hclsup_java/yolo123/hls/stream_" + channel + ".m3u8";
                log.info("用户 ID: {} 切换到通道: {}", luserId, channel);

                // 检查 FFmpeg 是否可用
                if (!isFFmpegInstalled()) {
                    log.error("FFmpeg 未安装或无法访问");
                    return false;
                }

                // 获取用户当前通道
                Integer currentChannel = userChannels.get(luserId);

                // 如果是同一通道，直接返回成功
                if (currentChannel != null && currentChannel.equals(channel) && isProcessAlive(luserId)) {
                    log.info("用户 ID: {} 已在通道 {} 上，无需切换", luserId, channel);
                    return true;
                }

                // 在启动新进程前，先彻底清理所有相关资源
                forceStopAndCleanup(luserId);

                // 清理目标频道的所有文件
                cleanupChannelFiles(channel);

                // 等待资源完全释放
                waitForResourceCleanup();

                // 更新通道映射
                userChannels.put(luserId, channel);

                // 启动新的 FFmpeg 进程
                return startFFmpegProcess(luserId, hlsPath, channel);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取用户锁被中断，用户 ID: {}", luserId);
            return false;
        } catch (Exception e) {
            log.error("启动 FFmpeg 进程异常，用户 ID: {}", luserId, e);
            return false;
        }
    }

    /**
     * 兼容原接口
     */
    public static boolean startFFmpeg(Integer luserId, String hlsPath) {
        int channel = extractChannelFromPath(hlsPath);
        if (channel > 0) {
            return startFFmpegForChannel(luserId, channel);
        }
        log.warn("无法从路径 {} 提取通道号，使用默认处理", hlsPath);
        return startFFmpegProcess(luserId, hlsPath, 0);
    }

    /**
     * 强制停止进程并清理所有资源
     */
    private static void forceStopAndCleanup(Integer luserId) {
        log.info("强制停止并清理资源，用户 ID: {}", luserId);

        // 停止运行标志
        AtomicBoolean flag = runningFlags.get(luserId);
        if (flag != null) {
            flag.set(false);
        }

        // 关闭输出流
        OutputStream outputStream = ffmpegOutputStreams.remove(luserId);
        if (outputStream != null) {
            try {
                outputStream.close();
                log.debug("已关闭输出流，用户 ID: {}", luserId);
            } catch (IOException e) {
                log.warn("关闭输出流失败，用户 ID: {}: {}", luserId, e.getMessage());
            }
        }

        // 强制终止进程
        Process process = ffmpegProcesses.remove(luserId);
        if (process != null) {
            try {
                // 先尝试正常终止
                process.destroy();
                if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                    // 如果1秒内没有终止，强制杀死
                    process.destroyForcibly();
                    process.waitFor(2000, TimeUnit.MILLISECONDS);
                }
                log.info("FFmpeg 进程已终止，用户 ID: {}", luserId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                log.warn("等待进程终止被中断，用户 ID: {}", luserId);
            }
        }

        // 清理用户关联的通道信息
        Integer channel = userChannels.get(luserId);
        if (channel != null) {
            // 清理该通道的文件，确保新的进程启动时不会受到干扰
            cleanupChannelFiles(channel);
        }

        // 重置重启计数
        restartAttempts.remove(luserId);
    }

    /**
     * 等待资源清理完成
     */
    private static void waitForResourceCleanup() {
        try {
            Thread.sleep(500); // 增加等待时间到500ms确保资源完全释放
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("资源清理等待被中断");
        }
    }

    /**
     * 彻底清理指定频道的所有相关文件
     */
    private static void cleanupChannelFiles(int channel) {
        log.info("清理频道 {} 的所有文件", channel);
        try {
            String channelPrefix = "stream_" + channel;

            // 删除所有相关 HLS 文件
            cleanupHlsFiles(HLS_ROOT_DIR, channelPrefix);

            // 清理播放列表所在目录
            cleanupHlsFiles("/home/elitedatai/hclsup_java/yolo123/hls/", channelPrefix);

            // 清理帧图片文件夹
            String framesDirPath = FRAMES_ROOT_DIR + "channel_" + channel;
            File framesDir = new File(framesDirPath);
            if (framesDir.exists()) {
                deleteDirectoryContents(framesDir);
                log.info("已清理频道 {} 的帧图片文件夹", channel);
            }
        } catch (Exception e) {
            log.error("清理频道 {} 文件时发生错误: {}", channel, e.getMessage());
        }
    }

    /**
     * 清理 HLS 文件（m3u8 和 ts 文件）
     */
    private static void cleanupHlsFiles(String directory, String prefix) {
        File dirFile = new File(directory);
        if (dirFile.exists() && dirFile.isDirectory()) {
            File[] files = dirFile.listFiles((dir, name) ->
                    name.startsWith(prefix) && (name.endsWith(".m3u8") || name.endsWith(".ts")));

            if (files != null) {
                for (File file : files) {
                    if (file.delete()) {
                        log.debug("已删除文件: {}", file.getAbsolutePath());
                    } else {
                        log.warn("删除文件失败: {}", file.getAbsolutePath());
                        // 如果删除失败，可能是文件被锁定，尝试将文件长度设为0
                        try {
                            new RandomAccessFile(file, "rw").setLength(0);
                            log.debug("已清空文件内容: {}", file.getAbsolutePath());
                        } catch (IOException e) {
                            log.warn("清空文件内容失败: {}", file.getAbsolutePath());
                        }
                    }
                }
            }
        }
    }

    /**
     * 删除目录内容但保留目录
     */
    private static void deleteDirectoryContents(File directory) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectoryContents(file);
                        if (!file.delete()) {
                            log.warn("无法删除目录: {}", file.getAbsolutePath());
                        }
                    } else {
                        if (!file.delete()) {
                            log.warn("无法删除文件: {}", file.getAbsolutePath());
                        }
                    }
                }
            }
        }
    }

    /**
     * 启动 FFmpeg 进程的核心逻辑
     */
    private static boolean startFFmpegProcess(Integer luserId, String hlsPath, int channel) {
        try {
            // 确保目录存在
            if (!ensureDirectoriesExist(hlsPath, channel)) {
                log.error("创建必要目录失败，用户 ID: {}", luserId);
                return false;
            }

            // 构建并启动 FFmpeg 命令
            List<String> command = buildOptimizedFFmpegCommand(hlsPath);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            log.info("启动 FFmpeg 进程，用户 ID: {}, 通道: {}", luserId, channel);

            Process process = pb.start();
            ffmpegProcesses.put(luserId, process);
            ffmpegOutputStreams.put(luserId, process.getOutputStream());
            runningFlags.put(luserId, new AtomicBoolean(true));
            restartAttempts.put(luserId, 0);

            // 启动监控线程
            startMonitoringThreads(luserId, process);

            return true;
        } catch (Exception e) {
            log.error("启动 FFmpeg 进程失败，用户 ID: {}", luserId, e);
            return false;
        }
    }

    /**
     * 确保必要的目录存在
     */
    private static boolean ensureDirectoriesExist(String hlsPath, int channel) {
        try {
            // 创建 HLS 目录
            File hlsDir = new File(new File(hlsPath).getParent());
            if (!hlsDir.exists() && !hlsDir.mkdirs()) {
                log.error("无法创建 HLS 目录: {}", hlsDir.getAbsolutePath());
                return false;
            }

            // 创建公共 HLS 目录
            File publicHlsDir = new File(HLS_ROOT_DIR);
            if (!publicHlsDir.exists() && !publicHlsDir.mkdirs()) {
                log.error("无法创建公共 HLS 目录: {}", publicHlsDir.getAbsolutePath());
                return false;
            }

            // 创建帧保存目录
            String framesDirPath = FRAMES_ROOT_DIR + "channel_" + channel;
            File framesDir = new File(framesDirPath);
            if (!framesDir.exists() && !framesDir.mkdirs()) {
                log.error("无法创建 frames 目录: {}", framesDirPath);
                return false;
            }

            // 设置目录权限
            try {
                Files.setPosixFilePermissions(hlsDir.toPath(),
                        PosixFilePermissions.fromString("rwxr-xr-x"));
                Files.setPosixFilePermissions(publicHlsDir.toPath(),
                        PosixFilePermissions.fromString("rwxr-xr-x"));
                Files.setPosixFilePermissions(framesDir.toPath(),
                        PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (IOException e) {
                log.warn("设置目录权限失败: {}", e.getMessage());
                // 继续执行，不因权限设置失败而中断整个流程
            }

            return true;
        } catch (Exception e) {
            log.error("创建目录失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建优化的 FFmpeg 命令
     */
    private static List<String> buildOptimizedFFmpegCommand(String hlsPath) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-f"); command.add("hevc");
        command.add("-i"); command.add("pipe:0");

        // 视频处理参数
        command.add("-vf"); command.add("scale=640:360,fps=15");
        command.add("-c:v"); command.add("libx264");
        command.add("-preset"); command.add("ultrafast"); // 使用最快预设
        command.add("-tune"); command.add("zerolatency");
        command.add("-g"); command.add("30");
        command.add("-b:v"); command.add("512k");

        // HLS 参数 - 优化切换
        command.add("-f"); command.add("hls");
        command.add("-hls_time"); command.add("2");
        command.add("-hls_list_size"); command.add("3"); // 只保留3个分段，减少延迟
        command.add("-hls_flags"); command.add("delete_segments+independent_segments+discont_start");
        command.add("-hls_segment_type"); command.add("mpegts");
        command.add("-hls_playlist_type"); command.add("event");
        command.add("-hls_allow_cache"); command.add("0"); // 禁止缓存，减少切换时的问题

        command.add("-an"); // 无音频
        command.add("-strict"); command.add("-2");
        command.add(hlsPath);

        return command;
    }

    /**
     * 启动监控线程
     */
    private static void startMonitoringThreads(Integer luserId, Process process) {
        // 输出日志线程
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && isProcessRunning(luserId)) {
                    log.debug("FFmpeg [{}]: {}", luserId, line);
                }
            } catch (IOException e) {
                if (isProcessRunning(luserId)) {
                    log.error("读取 FFmpeg 输出失败: {}", e.getMessage());
                }
            }
        }, "FFmpeg-Output-" + luserId);
        logThread.setDaemon(true); // 设置为守护线程，避免进程退出时线程仍在运行
        logThread.start();

        // 进程监控线程
        Thread monitorThread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                log.info("FFmpeg 进程退出，用户 ID: {}, 退出码: {}", luserId, exitCode);

                if (isProcessRunning(luserId)) {
                    handleProcessRestart(luserId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("进程监控线程被中断，用户 ID: {}", luserId);
            }
        }, "FFmpeg-Monitor-" + luserId);
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * 处理进程重启
     */
    private static void handleProcessRestart(Integer luserId) {
        ReentrantLock lock = getUserLock(luserId);
        if (lock.tryLock()) {
            try {
                int attempts = restartAttempts.getOrDefault(luserId, 0);
                if (attempts < MAX_RESTART_ATTEMPTS) {
                    log.warn("尝试重启 FFmpeg，用户 ID: {} (第 {}/{} 次)", luserId, attempts + 1, MAX_RESTART_ATTEMPTS);
                    restartAttempts.put(luserId, attempts + 1);

                    Integer channel = userChannels.get(luserId);
                    if (channel != null) {
                        // 确保清理完成后再重启
                        cleanupChannelFiles(channel);
                        waitForResourceCleanup();

                        String hlsPath = "/home/elitedatai/hclsup_java/yolo123/hls/stream_" + channel + ".m3u8";
                        startFFmpegProcess(luserId, hlsPath, channel);
                    }
                } else {
                    log.error("达到最大重启尝试次数，用户 ID: {}", luserId);
                    runningFlags.put(luserId, new AtomicBoolean(false));
                }
            } finally {
                lock.unlock();
            }
        } else {
            log.warn("无法获取用户锁进行重启，用户 ID: {}", luserId);
        }
    }

    /**
     * 写入数据到 FFmpeg
     */
    public static void writeData(Integer luserId, byte[] data) {
        if (data == null || data.length < 10) {
            return;
        }

        if (!isProcessAlive(luserId)) {
            log.warn("FFmpeg 进程未运行，用户 ID: {}", luserId);
            return;
        }

        OutputStream outputStream = ffmpegOutputStreams.get(luserId);
        if (outputStream != null) {
            synchronized (outputStream) { // 同步写入，避免并发问题
                try {
                    outputStream.write(data);
                    outputStream.flush();
                } catch (IOException e) {
                    log.error("写入数据失败，用户 ID: {}", luserId, e);
                    // 如果写入失败，可能是进程已经挂了，尝试重启
                    ReentrantLock lock = getUserLock(luserId);
                    if (lock.tryLock()) {
                        try {
                            handleProcessRestart(luserId);
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            }
        }
    }

    /**
     * 停止 FFmpeg 进程
     */
    public static synchronized void stopFFmpeg(Integer luserId) {
        ReentrantLock lock = getUserLock(luserId);
        if (lock.tryLock()) {
            try {
                log.info("停止 FFmpeg 进程，用户 ID: {}", luserId);
                forceStopAndCleanup(luserId);
            } finally {
                lock.unlock();
            }
        } else {
            log.warn("无法获取用户锁停止 FFmpeg，用户 ID: {}", luserId);
        }
    }

    /**
     * 检查进程是否运行
     */
    private static boolean isProcessRunning(Integer luserId) {
        return runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get();
    }

    /**
     * 检查 FFmpeg 进程是否存活
     */
    public static boolean isProcessAlive(Integer luserId) {
        Process process = ffmpegProcesses.get(luserId);
        return process != null && process.isAlive() && isProcessRunning(luserId);
    }

    /**
     * 获取用户当前的通道号
     */
    public static Integer getCurrentChannel(Integer luserId) {
        return userChannels.get(luserId);
    }

    /**
     * 从 HLS 路径中提取通道号
     */
    private static int extractChannelFromPath(String hlsPath) {
        try {
            String fileName = Paths.get(hlsPath).getFileName().toString();
            if (fileName.startsWith("stream_") && fileName.endsWith(".m3u8")) {
                String channelStr = fileName.substring(7, fileName.length() - 5);
                return Integer.parseInt(channelStr);
            }
        } catch (Exception e) {
            log.warn("无法从路径 {} 中提取通道号", hlsPath);
        }
        return -1;
    }

    /**
     * 检查 FFmpeg 是否已安装
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
}