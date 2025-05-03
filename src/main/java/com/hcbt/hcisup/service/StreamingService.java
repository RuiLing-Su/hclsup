package com.hcbt.hcisup.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 视频流服务
 * 负责将检测帧转换为流媒体格式供VLC等播放器播放
 */
@Slf4j
@Service
public class StreamingService {

    @Autowired
    private FrameDetectionProcessor frameDetectionProcessor;
    private final String framesDirBasePath;

    private static final int DEFAULT_FRAME_RATE = 1; // 默认帧率，每秒1帧
    private static final int MJPEG_QUALITY = 90; // MJPEG质量，0-100
    private static final byte[] MJPEG_BOUNDARY = "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ".getBytes();
    private static final byte[] MJPEG_LINE_END = "\r\n\r\n".getBytes();

    public StreamingService(@Value("${app.stream.frames-dir}") String framesDirBasePath) {
        this.framesDirBasePath = framesDirBasePath;
    }

    /**
     * 以MJPEG格式流式传输检测帧
     * MJPEG格式可以被VLC和大多数现代浏览器支持
     *
     * @param luserId 用户ID
     * @param outputStream 输出流
     */
    public void streamMjpegFromDetectionFrames(Integer luserId, OutputStream outputStream) {
        String framesDirPath = framesDirBasePath + luserId + "/results";
        File framesDir = new File(framesDirPath);

        if (!framesDir.exists()) {
            log.error("检测结果目录不存在: {}", framesDirPath);
            return;
        }

        try {
            // 写入MJPEG头部
            outputStream.write(("--frame\r\n").getBytes());

            String lastProcessedFrame = null;

            // 持续循环，直到线程被中断
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 确保用户还在处理中
                    if (!frameDetectionProcessor.isProcessingUser(luserId)) {
                        log.info("用户 {} 的检测处理已停止，结束视频流", luserId);
                        break;
                    }

                    // 获取最新的检测结果路径
                    String latestResultPath = frameDetectionProcessor.getLatestResultPath(luserId);

                    // 如果没有最新结果，则尝试从目录获取
                    if (latestResultPath == null) {
                        File[] frameFiles = framesDir.listFiles((dir, name) -> name.endsWith(".jpg"));
                        if (frameFiles != null && frameFiles.length > 0) {
                            // 按修改时间排序，获取最新的文件
                            File latestFrame = Arrays.stream(frameFiles)
                                    .max(Comparator.comparing(File::lastModified))
                                    .orElse(null);

                            if (latestFrame != null) {
                                latestResultPath = latestFrame.getAbsolutePath();
                            }
                        }
                    }

                    // 如果有有效的结果路径，并且是新的帧
                    if (latestResultPath != null && !latestResultPath.equals(lastProcessedFrame)) {
                        Path path = Paths.get(latestResultPath);
                        if (Files.exists(path)) {
                            // 读取图像数据
                            byte[] imageData = Files.readAllBytes(path);

                            // 写入MJPEG帧
                            outputStream.write(MJPEG_BOUNDARY);
                            outputStream.write(String.valueOf(imageData.length).getBytes());
                            outputStream.write(MJPEG_LINE_END);
                            outputStream.write(imageData);
                            outputStream.write(("\r\n--frame\r\n").getBytes());
                            outputStream.flush();

                            lastProcessedFrame = latestResultPath;
                        }
                    }

                    // 根据帧率控制流速
                    Thread.sleep(1000 / DEFAULT_FRAME_RATE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("视频流线程被中断");
                    break;
                } catch (IOException e) {
                    log.error("处理视频流时出错", e);
                    break;
                }
            }
        } catch (IOException e) {
            log.error("初始化视频流失败", e);
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {
                log.error("关闭视频流输出流失败", e);
            }
        }
    }

    /**
     * 生成MP4视频文件从检测帧
     *
     * @param luserId 用户ID
     * @param outputPath 输出文件路径
     * @param duration 视频时长(秒)
     * @return 是否成功
     */
    public boolean generateMp4FromDetectionFrames(Integer luserId, String outputPath, int duration) {
        String framesDirPath = framesDirBasePath + luserId + "/results";
        File framesDir = new File(framesDirPath);

        if (!framesDir.exists()) {
            log.error("检测结果目录不存在: {}", framesDirPath);
            return false;
        }

        try {
            // 构建FFmpeg命令
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-framerate", String.valueOf(DEFAULT_FRAME_RATE),
                    "-pattern_type", "glob",
                    "-i", framesDirPath + "/result_*.jpg",
                    "-c:v", "libx264",
                    "-pix_fmt", "yuv420p",
                    "-t", String.valueOf(duration),  // 视频时长
                    "-y",  // 覆盖已有文件
                    outputPath
            );

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("成功生成MP4视频文件: {}", outputPath);
                return true;
            } else {
                log.error("生成MP4视频失败，FFmpeg退出码: {}", exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            log.error("生成MP4视频时出错", e);
            return false;
        }
    }
}