package com.hcbt.hcisup.common;

import com.hcbt.hcisup.utils.WebSocket;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles H265 to H264 conversion and WebSocket streaming
 */
@Slf4j
public class HandleStreamV2 {

    private byte[] allEsBytes = null;
    private final WebSocket webSocket = new WebSocket();
    private final Integer luserId;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Process ffmpegProcess;
    private ExecutorService executorService;
    private OutputStream ffmpegInput;
    private BufferedInputStream ffmpegOutput;

    public HandleStreamV2(Integer luserId) {
        this.luserId = luserId;
        initializeFFmpegPipeline();
    }

    /**
     * Initializes the FFmpeg process for video conversion
     */
    private void initializeFFmpegPipeline() {
        try {
            // Start FFmpeg process with pipe input and output
            // -i pipe:0 = input from stdin
            // -f h264 = output format h264
            // -c:v libx264 = use libx264 codec for conversion
            // -preset ultrafast = fastest encoding setting (prioritizes speed over quality)
            // -tune zerolatency = reduce latency
            // -f rawvideo = output raw video
            // pipe:1 = output to stdout
            String[] ffmpegCommand = {
                    "ffmpeg",
                    "-i", "pipe:0",      // Input from stdin
                    "-c:v", "libx264",   // Convert using H264 codec
                    "-preset", "ultrafast",
                    "-tune", "zerolatency",
                    "-f", "rawvideo",
                    "pipe:1"             // Output to stdout
            };

            ProcessBuilder pb = new ProcessBuilder(ffmpegCommand);
            pb.redirectErrorStream(true);

            ffmpegProcess = pb.start();
            ffmpegInput = ffmpegProcess.getOutputStream();
            ffmpegOutput = new BufferedInputStream(ffmpegProcess.getInputStream());

            // Start thread to read FFmpeg output and send to WebSocket
            executorService = Executors.newSingleThreadExecutor();
            isRunning.set(true);

            executorService.submit(() -> {
                try {
                    byte[] buffer = new byte[65536]; // 64KB buffer
                    int bytesRead;

                    while (isRunning.get() && (bytesRead = ffmpegOutput.read(buffer)) != -1) {
                        if (bytesRead > 0) {
                            byte[] data = new byte[bytesRead];
                            System.arraycopy(buffer, 0, data, 0, bytesRead);
                            webSocket.sendMessageForOne(data, luserId);
                        }
                    }
                } catch (IOException e) {
                    if (isRunning.get()) {
                        log.error("Error reading from FFmpeg: {}", e.getMessage());
                    }
                }
            });

            log.info("FFmpeg pipeline initialized for user ID: {}", luserId);
        } catch (IOException e) {
            log.error("Failed to initialize FFmpeg: {}", e.getMessage());
        }
    }

    /**
     * Process incoming video data
     */
    public void startProcessing(final byte[] outputData) {
        if (outputData.length <= 0 || !isRunning.get()) {
            return;
        }

        try {
            // Check if we're at the start of a new frame
            if ((outputData[0] & 0xff) == 0x00
                    && (outputData[1] & 0xff) == 0x00
                    && (outputData[2] & 0xff) == 0x01
                    && (outputData[3] & 0xff) == 0xBA) { // RTP packet header

                // Send any accumulated data to FFmpeg
                if (allEsBytes != null && allEsBytes.length > 0) {
                    ffmpegInput.write(allEsBytes);
                    ffmpegInput.flush();
                }
                allEsBytes = null;
            }

            // Process video PES packet (starts with 00 00 01 E0)
            if ((outputData[0] & 0xff) == 0x00
                    && (outputData[1] & 0xff) == 0x00
                    && (outputData[2] & 0xff) == 0x01
                    && (outputData[3] & 0xff) == 0xE0) {

                // Extract ES stream data (skip PES header)
                int from = 9 + (outputData[8] & 0xff);
                int len = outputData.length - from;

                // Extract the ES payload
                byte[] esBytes = new byte[len];
                System.arraycopy(outputData, from, esBytes, 0, len);

                // Accumulate ES data
                if (allEsBytes == null) {
                    allEsBytes = esBytes;
                } else {
                    byte[] newEsBytes = new byte[allEsBytes.length + esBytes.length];
                    System.arraycopy(allEsBytes, 0, newEsBytes, 0, allEsBytes.length);
                    System.arraycopy(esBytes, 0, newEsBytes, allEsBytes.length, esBytes.length);
                    allEsBytes = newEsBytes;
                }
            }
        } catch (IOException e) {
            log.error("Error processing video data: {}", e.getMessage());
        }
    }

    /**
     * Stop processing and clean up resources
     */
    public void stopProcessing() {
        isRunning.set(false);

        try {
            if (ffmpegInput != null) {
                ffmpegInput.close();
            }

            if (ffmpegProcess != null) {
                ffmpegProcess.destroy();
                // Wait briefly for process to terminate
                ffmpegProcess.waitFor();
            }

            if (executorService != null) {
                executorService.shutdownNow();
            }

            log.info("FFmpeg pipeline stopped for user ID: {}", luserId);
        } catch (Exception e) {
            log.error("Error stopping FFmpeg pipeline: {}", e.getMessage());
        }
    }
}