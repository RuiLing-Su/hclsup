package com.hcbt.hcisup.service;

import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StreamService 类负责视频流的处理，包括管道创建、数据处理和资源清理。
 * 修改后使用 sessionID 作为键，确保一致性和健壮性。
 */
@Slf4j
@Service
public class StreamService {

    // 存储会话ID与管道输出流的映射
    private final ConcurrentHashMap<Integer, PipedOutputStream> outputStreams = new ConcurrentHashMap<>();
    // 存储会话ID与FFmpeg帧抓取器的映射
    private final ConcurrentHashMap<Integer, FFmpegFrameGrabber> grabbers = new ConcurrentHashMap<>();
    // 存储会话ID与FFmpeg帧录制器的映射
    private final ConcurrentHashMap<Integer, FFmpegFrameRecorder> recorders = new ConcurrentHashMap<>();
    // 存储会话ID与处理线程的映射
    private final ConcurrentHashMap<Integer, Thread> processingThreads = new ConcurrentHashMap<>();
    // 依赖注入的检测服务
    private final DetectionService detectionService;
    // OpenCV帧转换器
    private final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

    /**
     * 构造函数，注入 DetectionService
     * @param detectionService 检测服务实例
     */
    public StreamService(DetectionService detectionService) {
        this.detectionService = detectionService;
    }

    /**
     * 创建流管道，用于接收视频流数据
     * @param sessionId 会话ID，作为管道的唯一标识
     * @return 创建的管道输出流
     * @throws Exception 如果会话已存在或创建失败
     */
    public PipedOutputStream createStreamPipe(int sessionId) throws Exception {
        if (outputStreams.containsKey(sessionId)) {
            // 先尝试清理现有资源，而不是抛出异常
            log.warn("会话 {} 已存在，正在清理现有资源", sessionId);
            cleanupResources(sessionId);
        }
        log.info("创建管道输出流，用于接收ES数据，会话ID: {}", sessionId);

        // 使用更大的缓冲区来处理高分辨率视频
        PipedInputStream pipedInput = new PipedInputStream(65536); // 增加缓冲区大小
        PipedOutputStream pipedOutput = new PipedOutputStream(pipedInput);
        outputStreams.put(sessionId, pipedOutput);

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(pipedInput);
        grabber.setFormat("hevc");

        // 设置更多缓冲区和超时选项
        grabber.setOption("analyzeduration", "10000000"); // 增加分析时长（微秒）
        grabber.setOption("probesize", "10000000");       // 增加探测大小（字节）

        grabbers.put(sessionId, grabber);
        log.info("为会话 {} 创建了帧抓取器", sessionId);

        return pipedOutput;
    }


    /**
     * 启动视频流处理线程
     * @param sessionId 会话ID
     * @param hlsPath HLS输出路径
     */
    public void startProcessingThread(int sessionId, String hlsPath) {
        Thread thread = new Thread(() -> processStream(sessionId, hlsPath));
        thread.start();
        processingThreads.put(sessionId, thread);
    }

    /**
     * 处理视频流，将其转换为HLS格式并进行检测
     * @param sessionId 会话ID
     * @param hlsPath HLS输出路径
     */
    private void processStream(int sessionId, String hlsPath) {
        log.info("StreamService 开始处理视频流，会话ID: {}", sessionId);
        FFmpegFrameGrabber grabber = null;
        FFmpegFrameRecorder recorder = null;

        try {
            grabber = grabbers.get(sessionId);
            if (grabber == null) {
                throw new IllegalStateException("会话 " + sessionId + " 的帧抓取器为空");
            }

            // 增加视频流分析参数
            grabber.setOption("analyzeduration", "10000000");
            grabber.setOption("probesize", "10000000");

            // 设置错误处理选项
            grabber.setOption("err_detect", "ignore_err");
            grabber.setOption("flags2", "+export_mvs");

            // 明确指定编解码器和格式
            grabber.setFormat("hevc");
            grabber.setVideoCodec(avcodec.AV_CODEC_ID_HEVC);

            // 尝试启动grabber
            grabber.start();

            // 获取视频尺寸，如果获取不到则使用默认值
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();

            if (width <= 0 || height <= 0) {
                log.warn("无法从视频流获取尺寸，使用默认值 2560x1440");
                width = 2560;  // 默认宽度
                height = 1440; // 默认高度
            }

            log.info("视频尺寸: {}x{}", width, height);

            // 创建录制器
            log.info("创建FFmpeg帧录制器，用于输出HLS流");
            recorder = new FFmpegFrameRecorder(hlsPath, width, height);

            // HLS设置
            recorder.setFormat("hls");
            recorder.setOption("hls_time", "2");
            recorder.setOption("hls_list_size", "3");
            recorder.setOption("hls_flags", "delete_segments+append_list");
            recorder.setOption("hls_segment_type", "mpegts");

            // 明确指定输出编解码器和格式
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFrameRate(25); // 使用固定帧率，避免从grabber获取可能的无效值
            recorder.setVideoBitrate(400000);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

            // 使用兼容性较高的编码设置
            recorder.setOption("preset", "ultrafast");
            recorder.setOption("tune", "zerolatency");
            recorder.setOption("profile", "baseline");
            recorder.setOption("level", "3.0");

            // 启用跳帧以控制比特率
            recorder.setOption("skip_frame", "1");

            try {
                recorder.start();
                recorders.put(sessionId, recorder);

                // 处理视频帧
                log.info("开始处理视频帧...");
                int frameCount = 0;
                int errorCount = 0;
                Frame frame;

                // 引入重试机制
                while (processingThreads.containsKey(sessionId)) {
                    try {
                        frame = grabber.grab();
                        if (frame == null) {
                            log.warn("获取到空帧，重试...");
                            // 短暂等待后重试
                            Thread.sleep(100);
                            errorCount++;
                            if (errorCount > 50) { // 5秒后停止尝试
                                log.error("连续获取到50个空帧，停止处理");
                                break;
                            }
                            continue;
                        }

                        // 重置错误计数
                        errorCount = 0;
                        frameCount++;

                        if (frame.image != null) {
                            Mat mat = null;
                            try {
                                mat = converter.convert(frame);
                                if (mat != null && !mat.empty()) {
                                    // 进行对象检测
                                    List<Detection> detections = detectionService.runInference(mat);
                                    if (detections != null) {
                                        detectionService.drawDetections(mat, detections);
                                    }

                                    // 转换回Frame并记录
                                    Frame processedFrame = converter.convert(mat);
                                    if (processedFrame != null) {
                                        recorder.record(processedFrame);
                                        if (frameCount % 100 == 0) {
                                            log.info("会话 {} 已处理 {} 帧", sessionId, frameCount);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.error("处理帧 {} 时出错: {}", frameCount, e.getMessage());
                            } finally {
                                if (mat != null && !mat.isNull()) {
                                    mat.close();
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("获取帧时出错: {}", e.getMessage());
                        errorCount++;
                        if (errorCount > 10) {
                            log.error("连续出现10个错误，停止处理");
                            break;
                        }
                        // 短暂等待后重试
                        Thread.sleep(100);
                    }
                }

                log.info("视频处理完成，共处理 {} 帧", frameCount);

            } catch (Exception e) {
                log.error("启动录制器失败: {}", e.getMessage(), e);
            }

        } catch (Exception e) {
            log.error("处理视频流失败，会话ID: {}，错误: {}", sessionId, e.getMessage(), e);
        } finally {
            if (recorder != null) {
                recorders.put(sessionId, recorder);
            }
            cleanupResources(sessionId);
        }
    }

    /**
     * 处理接收到的视频流数据并写入管道
     * @param sessionId 会话ID
     * @param esData ES数据字节数组
     */
    public void processStreamData(int sessionId, byte[] esData) {
        if (esData == null || esData.length == 0) {
            log.warn("收到无效的ES数据，会话ID: {}", sessionId);
            return;
        }
        PipedOutputStream outputStream = outputStreams.get(sessionId);
        if (outputStream == null || !processingThreads.containsKey(sessionId)) {
            log.warn("会话 {} 的输出流不存在或处理已停止，无法写入数据", sessionId);
            return;
        }

        try {
            // 使用同步块确保线程安全写入
            synchronized (outputStream) {
                // 分批写入数据，避免一次写入过多
                int chunkSize = 4096; // 4KB chunks
                for (int offset = 0; offset < esData.length; offset += chunkSize) {
                    int length = Math.min(chunkSize, esData.length - offset);
                    outputStream.write(esData, offset, length);
                    outputStream.flush();

                    // 给读取端一些时间处理数据
                    Thread.sleep(1);
                }
            }
        } catch (IOException e) {
            if (processingThreads.containsKey(sessionId)) {
                log.error("写入 ES 数据失败，会话ID: {}", sessionId, e);
                cleanupResources(sessionId);
            } else {
                log.debug("会话 {} 的处理已停止，写入操作被中断", sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("数据写入被中断", e);
        }
    }

    /**
     * 停止指定会话的视频流处理
     * @param sessionId 会话ID
     */
    public void stopStream(int sessionId) {
        processingThreads.remove(sessionId);
        cleanupResources(sessionId);
    }

    /**
     * 清理指定会话的资源
     * @param sessionId 会话ID
     */

    private void cleanupResources(int sessionId) {
        log.info("开始清理会话 {} 的资源", sessionId);

        // 确保线程管理先被更新，防止新数据处理
        processingThreads.remove(sessionId);

        // 使用synchronized块确保资源清理的同步性
        synchronized (this) {
            // 关闭管道输出流
            PipedOutputStream outputStream = outputStreams.remove(sessionId);
            if (outputStream != null) {
                try {
                    outputStream.close();
                    log.debug("已关闭会话 {} 的输出流", sessionId);
                } catch (Exception e) {
                    log.error("关闭输出流失败，会话ID: {}", sessionId, e);
                }
            }

            // 停止并释放帧抓取器
            FFmpegFrameGrabber grabber = grabbers.remove(sessionId);
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.release();
                    log.debug("已停止并释放会话 {} 的帧抓取器", sessionId);
                } catch (Exception e) {
                    log.error("停止抓取器失败，会话ID: {}", sessionId, e);
                }
            }

            // 停止并释放帧录制器
            FFmpegFrameRecorder recorder = recorders.remove(sessionId);
            if (recorder != null) {
                try {
                    recorder.stop();
                    recorder.release();
                    log.debug("已停止并释放会话 {} 的帧录制器", sessionId);
                } catch (Exception e) {
                    log.error("停止录制器失败，会话ID: {}", sessionId, e);
                }
            }
        }
        log.info("会话 {} 的资源清理完成", sessionId);
    }
}