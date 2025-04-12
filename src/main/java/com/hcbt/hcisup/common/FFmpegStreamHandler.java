package com.hcbt.hcisup.common;

import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FFmpegStreamHandler {

    private static ConcurrentHashMap<Integer, Process> ffmpegProcesses = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<Integer, OutputStream> ffmpegOutputStreams = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<Integer, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<Integer, String> rtmpUrls = new ConcurrentHashMap<>();
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static ConcurrentHashMap<Integer, Integer> restartAttempts = new ConcurrentHashMap<>();

    /**
     * Start an FFmpeg process for a specific user ID
     *
     * @param luserId User ID
     * @param srtUrl Target srtUrl URL to push stream to
     * @return true if started successfully
     */
    public static boolean startFFmpeg(Integer luserId, String srtUrl) {
        log.info("Attempting to start FFmpeg for user ID: {} with SRT URL: {}", luserId, srtUrl);

        // Check if FFmpeg is installed
        try {
            Process checkProcess = new ProcessBuilder("ffmpeg", "-version").start();
            int exitCode = checkProcess.waitFor();
            if (exitCode != 0) {
                log.error("FFmpeg is not properly installed or accessible");
                return false;
            }
        } catch (Exception e) {
            log.error("FFmpeg check failed: {}", e.getMessage());
            return false;
        }

        try {
            // Build FFmpeg command for RTSP with H.265
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-f", "hevc",               // 输入格式为H.265裸流
                    "-i", "pipe:0",            // 从标准输入读取数据
                    "-c:v", "copy",            // 视频流直接复制，不重新编码
                    "-an",                     // 无音频
                    "-f", "mpegts",            // 输出格式为MPEG-TS（SRT要求）
                    "-mpegts_service_type", "0x1", // 设置服务类型
                    "-mpegts_pmt_start_pid", "100", // PMT PID
                    "-mpegts_start_pid", "101",     // 流起始PID
                    srtUrl                     // 输出SRT URL，例如 srt://127.0.0.1:10080?streamid=publish/live/stream1
            );
            log.info("Starting FFmpeg process with command: " + pb.command());

            pb.redirectErrorStream(true); // 合并标准错误和标准输出

            // Start FFmpeg process
            Process process = pb.start();
            ffmpegProcesses.put(luserId, process);

            // Get the process input stream (from Java's perspective, this is the output stream)
            OutputStream outputStream = process.getOutputStream();
            ffmpegOutputStreams.put(luserId, outputStream);

            final var inputStream = process.getInputStream();

            // Start a thread to log FFmpeg output
            new Thread(() -> {
                try {
                    byte[] buffer = new byte[1024];
                    int length;
                    while (ffmpegProcesses.containsKey(luserId) &&
                            (length = inputStream.read(buffer)) != -1) {
                        log.debug("FFmpeg output: {}", new String(buffer, 0, length));
                    }
                } catch (IOException e) {
                    if (ffmpegProcesses.containsKey(luserId)) {
                        log.error("Error reading FFmpeg output", e);
                    } else {
                        log.debug("FFmpeg stream closed for user: {}", luserId);
                    }
                }
            }).start();

            // Add process status monitoring thread
            new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    log.info("FFmpeg process exited for user ID: {} with code: {}", luserId, exitCode);
                    if (ffmpegProcesses.containsKey(luserId)) {
                        stopFFmpeg(luserId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Process monitoring interrupted for user ID: {}", luserId);
                }
            }).start();

            // 记录SRT URL以便重启
            rtmpUrls.put(luserId, srtUrl);
            runningFlags.put(luserId, new AtomicBoolean(true));
            restartAttempts.put(luserId, 0);

            return true;
        } catch (IOException e) {
            log.error("Failed to start FFmpeg for user ID: " + luserId, e);
            return false;
        }
    }

    /**
     * Internal method to start the FFmpeg process
     */
    private static boolean startFFmpegProcess(Integer luserId, String rtmpUrl) {
        // Check if FFmpeg is available
        try {
            Process checkProcess = new ProcessBuilder("ffmpeg", "-version").start();
            int exitCode = checkProcess.waitFor();
            if (exitCode != 0) {
                log.error("FFmpeg is not properly installed or accessible");
                return false;
            }
        } catch (Exception e) {
            log.error("FFmpeg check failed: " + e.getMessage());
            return false;
        }

        // Stop existing process if any
        stopExistingProcess(luserId);

        try {
            // Build FFmpeg command with more robust options
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-f");
            command.add("h264"); // Try h264 instead of hevc if your streams are H.264
            command.add("-i");
            command.add("pipe:0");
            command.add("-c:v");
            command.add("copy");
            command.add("-f");
            command.add("flv"); // Using flv format for RTMP is more common
            command.add("-flvflags");
            command.add("no_duration_filesize");
            command.add("-fflags");
            command.add("nobuffer");
            command.add("-flags");
            command.add("low_delay");
            command.add(rtmpUrl);

            ProcessBuilder pb = new ProcessBuilder(command);
            log.info("Starting FFmpeg process with command: {}", pb.command());

            pb.redirectErrorStream(true);

            Process process = pb.start();
            ffmpegProcesses.put(luserId, process);

            OutputStream outputStream = process.getOutputStream();
            ffmpegOutputStreams.put(luserId, outputStream);

            final var inputStream = process.getInputStream();

            // Log FFmpeg output for debugging
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                    String line;
                    while ((line = reader.readLine()) != null && runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
                        log.debug("FFmpeg output [{}]: {}", luserId, line);
                    }
                } catch (IOException e) {
                    if (runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
                        log.error("Error reading FFmpeg output", e);
                    }
                }
            }).start();

            // Monitor process status
            new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    log.info("FFmpeg process exited for user ID: {} with code: {}", luserId, exitCode);

                    if (runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
                        // Process terminated unexpectedly, try to restart if allowed
                        int attempts = restartAttempts.getOrDefault(luserId, 0);
                        if (attempts < MAX_RESTART_ATTEMPTS) {
                            log.warn("Attempting to restart FFmpeg for user ID: {} (attempt {}/{})",
                                    luserId, attempts + 1, MAX_RESTART_ATTEMPTS);
                            restartAttempts.put(luserId, attempts + 1);
                            String savedUrl = rtmpUrls.get(luserId);
                            if (savedUrl != null) {
                                startFFmpegProcess(luserId, savedUrl);
                            }
                        } else {
                            log.error("Maximum restart attempts reached for user ID: {}", luserId);
                            runningFlags.put(luserId, new AtomicBoolean(false));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Process monitoring interrupted for user ID: {}", luserId);
                }
            }).start();

            return true;
        } catch (IOException e) {
            log.error("Failed to start FFmpeg for user ID: " + luserId, e);
            return false;
        }
    }

    /**
     * Stop existing FFmpeg process if running
     */
    private static void stopExistingProcess(Integer luserId) {
        Process oldProcess = ffmpegProcesses.remove(luserId);
        OutputStream oldOutputStream = ffmpegOutputStreams.remove(luserId);

        if (oldOutputStream != null) {
            try {
                oldOutputStream.close();
            } catch (IOException e) {
                log.error("Failed to close old FFmpeg output stream for user ID: {}", luserId, e);
            }
        }

        if (oldProcess != null) {
            try {
                oldProcess.destroyForcibly().waitFor();
                log.info("Old FFmpeg process terminated for user ID: {}", luserId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for old process termination: {}", luserId);
            }
        }
    }

    /**
     * Write video data to FFmpeg stdin
     *
     * @param luserId User ID
     * @param data Video data
     */
    public static void writeData(Integer luserId, byte[] data) {
        if (!isProcessAlive(luserId)) {
            log.warn("Cannot write data - FFmpeg process for user {} is not running", luserId);
            return;
        }

        if (data.length < 10) {
            log.warn("Data too small for user {}, possibly invalid: {} bytes", luserId, data.length);
            return;
        }

        OutputStream outputStream = ffmpegOutputStreams.get(luserId);
        if (outputStream != null) {
            try {
                outputStream.write(data);
                outputStream.flush();
            } catch (IOException e) {
                log.error("Failed to write data to FFmpeg for user ID: " + luserId, e);
                // Consider restarting the process here
                restartProcess(luserId);
            }
        }
    }

    /**
     * Attempt to restart a failed process
     */
    private static void restartProcess(Integer luserId) {
        if (!runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get()) {
            return;
        }

        int attempts = restartAttempts.getOrDefault(luserId, 0);
        if (attempts < MAX_RESTART_ATTEMPTS) {
            log.warn("Attempting to restart FFmpeg after write error for user ID: {} (attempt {}/{})",
                    luserId, attempts + 1, MAX_RESTART_ATTEMPTS);
            restartAttempts.put(luserId, attempts + 1);
            String savedUrl = rtmpUrls.get(luserId);
            if (savedUrl != null) {
                startFFmpegProcess(luserId, savedUrl);
            }
        } else {
            log.error("Maximum restart attempts reached for user ID: {}", luserId);
            runningFlags.put(luserId, new AtomicBoolean(false));
        }
    }

    /**
     * Stop FFmpeg process for a specific user ID
     *
     * @param luserId User ID
     */
    public static void stopFFmpeg(Integer luserId) {
        // Mark as not running to prevent restart attempts
        runningFlags.put(luserId, new AtomicBoolean(false));
        restartAttempts.remove(luserId);
        rtmpUrls.remove(luserId);

        Process process = ffmpegProcesses.remove(luserId);
        OutputStream outputStream = ffmpegOutputStreams.remove(luserId);

        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                log.error("Failed to close FFmpeg output stream for user ID: {}", luserId, e);
            }
        }

        if (process != null) {
            try {
                // Try graceful shutdown first
                process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                process.destroyForcibly();
                log.info("FFmpeg process terminated for user ID: {}", luserId);
            }
        }
    }

    public static boolean isProcessAlive(Integer luserId) {
        Process process = ffmpegProcesses.get(luserId);
        return process != null && process.isAlive() &&
                runningFlags.getOrDefault(luserId, new AtomicBoolean(false)).get();
    }
}