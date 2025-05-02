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
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StreamService 类负责视频流的处理，包括管道创建、数据处理和资源清理。
 * 优化版本改进了多线程安全性、异常处理和资源管理。
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
    // 存储会话ID与运行状态的映射
    private final ConcurrentHashMap<Integer, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    // 存储会话ID与写入锁的映射
    private final ConcurrentHashMap<Integer, ReentrantLock> writeLocks = new ConcurrentHashMap<>();
    // 定时执行器，用于监控流状态
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    // 依赖注入的检测服务
    private final DetectionService detectionService;
    // OpenCV帧转换器
    private final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
    // 管道缓冲区大小（增加到128KB）
    private static final int PIPE_BUFFER_SIZE = 131072;
    // 帧数据写入分块大小
    private static final int WRITE_CHUNK_SIZE = 8192;
    // 最大连续错误次数
    private static final int MAX_ERROR_COUNT = 20;

    /**
     * 构造函数，注入 DetectionService
     * @param detectionService 检测服务实例
     */
    public StreamService(DetectionService detectionService) {
        this.detectionService = detectionService;

        // 启动定期检查任务，清理僵尸会话
        scheduler.scheduleAtFixedRate(this::cleanupZombieSessions, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 清理僵尸会话，处理可能的资源泄漏
     */
    private void cleanupZombieSessions() {
        for (Integer sessionId : processingThreads.keySet()) {
            Thread thread = processingThreads.get(sessionId);
            if (thread != null && !thread.isAlive()) {
                log.warn("检测到僵尸会话 {}，线程已死亡但资源未清理", sessionId);
                cleanupResources(sessionId);
            }
        }
    }

    /**
     * 创建流管道，用于接收视频流数据
     * @param sessionId 会话ID，作为管道的唯一标识
     * @return 创建的管道输出流
     * @throws Exception 如果创建失败
     */
    public PipedOutputStream createStreamPipe(int sessionId) throws Exception {
        if (outputStreams.containsKey(sessionId)) {
            log.warn("会话 {} 已存在，正在清理现有资源", sessionId);
            cleanupResources(sessionId);
        }

        // 创建运行标志和写入锁
        runningFlags.put(sessionId, new AtomicBoolean(true));
        writeLocks.put(sessionId, new ReentrantLock());

        log.info("创建管道输出流，用于接收ES数据，会话ID: {}", sessionId);

        try {
            // 使用更大的缓冲区来处理高分辨率视频
            PipedInputStream pipedInput = new PipedInputStream(PIPE_BUFFER_SIZE);
            PipedOutputStream pipedOutput = new PipedOutputStream(pipedInput);
            outputStreams.put(sessionId, pipedOutput);

            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(pipedInput);

            // 明确指定H.265格式和相关参数
            grabber.setFormat("hevc");
            grabber.setVideoCodec(avcodec.AV_CODEC_ID_HEVC);

            // 设置更多缓冲区和超时选项
            grabber.setOption("analyzeduration", "10000000");  // 增加分析时长（微秒）
            grabber.setOption("probesize", "10000000");        // 增加探测大小（字节）
            grabber.setOption("err_detect", "ignore_err");     // 忽略错误
            grabber.setOption("reconnect", "1");               // 启用重连
            grabber.setOption("reconnect_at_eof", "1");        // 在EOF时重连
            grabber.setOption("reconnect_streamed", "1");      // 流式重连
            grabber.setOption("reconnect_delay_max", "5");     // 最大重连延迟

            grabbers.put(sessionId, grabber);
            log.info("为会话 {} 创建了帧抓取器", sessionId);

            return pipedOutput;
        } catch (Exception e) {
            log.error("创建流管道失败: {}", e.getMessage(), e);
            cleanupResources(sessionId);
            throw e;
        }
    }

    /**
     * 启动视频流处理线程
     * @param sessionId 会话ID
     * @param hlsPath HLS输出路径
     */
    public void startProcessingThread(int sessionId, String hlsPath) {
        if (!runningFlags.containsKey(sessionId) || !runningFlags.get(sessionId).get()) {
            log.warn("会话 {} 未初始化或已停止，无法启动处理线程", sessionId);
            return;
        }

        Thread thread = new Thread(() -> processStream(sessionId, hlsPath));
        thread.setName("stream-processor-" + sessionId);
        thread.setDaemon(true); // 设置为守护线程，避免应用退出时阻塞
        thread.start();
        processingThreads.put(sessionId, thread);
        log.info("会话 {} 的视频处理线程已启动", sessionId);
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

            // 尝试启动grabber
            log.debug("正在启动帧抓取器...");
            try {
                grabber.start();
            } catch (Exception e) {
                log.error("启动帧抓取器失败: {}", e.getMessage(), e);
                return;
            }

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
            recorder = createRecorder(hlsPath, width, height);

            try {
                recorder.start();
                recorders.put(sessionId, recorder);

                // 处理视频帧
                log.info("开始处理视频帧...");
                processVideoFrames(sessionId, grabber, recorder);
            } catch (Exception e) {
                log.error("启动录制器或处理帧失败: {}", e.getMessage(), e);
            }

        } catch (Exception e) {
            log.error("处理视频流失败，会话ID: {}", sessionId, e);
        } finally {
            if (recorder != null) {
                recorders.put(sessionId, recorder);
            }
            cleanupResources(sessionId);
        }
    }

    /**
     * 创建HLS录制器
     * @param hlsPath HLS输出路径
     * @param width 视频宽度
     * @param height 视频高度
     * @return 配置好的录制器
     * @throws IOException 如果创建失败
     */
    private FFmpegFrameRecorder createRecorder(String hlsPath, int width, int height) throws IOException {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(hlsPath, width, height);

        // HLS设置
        recorder.setFormat("hls");
        recorder.setOption("hls_time", "2");                // 每段2秒
        recorder.setOption("hls_list_size", "3");           // 播放列表保留3个分段
        recorder.setOption("hls_flags", "delete_segments+append_list"); // 删除旧分段并支持动态追加
        recorder.setOption("hls_segment_type", "mpegts");   // 使用MPEG-TS分段格式

        // 视频编码设置
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFrameRate(25);
        recorder.setVideoBitrate(400000);
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

        // 使用兼容性较高的编码设置
        recorder.setOption("preset", "ultrafast");
        recorder.setOption("tune", "zerolatency");
        recorder.setOption("profile", "baseline");
        recorder.setOption("level", "3.0");

        return recorder;
    }

    /**
     * 处理视频帧，包括检测和录制
     * @param sessionId 会话ID
     * @param grabber 帧抓取器
     * @param recorder 帧录制器
     */
    private void processVideoFrames(int sessionId, FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder) {
        int frameCount = 0;
        int errorCount = 0;
        Frame frame;

        // 引入重试机制
        while (runningFlags.getOrDefault(sessionId, new AtomicBoolean(false)).get()) {
            try {
                frame = grabber.grab();
                if (frame == null) {
                    errorCount++;
                    if (errorCount % 5 == 0) {
                        log.warn("获取到空帧，重试次数: {}", errorCount);
                    }

                    // 短暂等待后重试
                    Thread.sleep(100);

                    if (errorCount > MAX_ERROR_COUNT) {
                        log.error("连续获取到{}个空帧，停止处理", MAX_ERROR_COUNT);
                        break;
                    }
                    continue;
                }

                // 重置错误计数
                errorCount = 0;
                frameCount++;

                if (frame.image != null) {
                    processImageFrame(frame, recorder, frameCount);

                    if (frameCount % 100 == 0) {
                        log.info("会话 {} 已处理 {} 帧", sessionId, frameCount);
                    }
                }
            } catch (Exception e) {
                log.error("处理帧时出错: {}", e.getMessage());
                errorCount++;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (errorCount > MAX_ERROR_COUNT) {
                    log.error("连续出现{}个错误，停止处理", MAX_ERROR_COUNT);
                    break;
                }
            }
        }

        log.info("视频处理完成，共处理 {} 帧", frameCount);
    }

    /**
     * 处理单个图像帧
     * @param frame 原始帧
     * @param recorder 帧录制器
     * @param frameCount 当前帧计数
     * @throws Exception 如果处理失败
     */
    private void processImageFrame(Frame frame, FFmpegFrameRecorder recorder, int frameCount) throws Exception {
        Mat mat = null;
        try {
            mat = converter.convert(frame);
            if (mat != null && !mat.empty()) {
                // 进行对象检测
                List<Detection> detections = detectionService.runInference(mat);
                if (detections != null && !detections.isEmpty()) {
                    detectionService.drawDetections(mat, detections);
                }

                // 转换回Frame并记录
                Frame processedFrame = converter.convert(mat);
                if (processedFrame != null) {
                    recorder.record(processedFrame);
                }
            }
        } finally {
            // 确保Mat资源被释放
            if (mat != null && !mat.isNull()) {
                mat.close();
            }
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

        // 检查会话是否活跃
        if (!isSessionActive(sessionId)) {
            log.warn("会话 {} 不活跃，无法处理数据", sessionId);
            return;
        }

        ReentrantLock writeLock = writeLocks.get(sessionId);
        if (writeLock == null) {
            log.warn("会话 {} 的写入锁不存在", sessionId);
            return;
        }

        PipedOutputStream outputStream = outputStreams.get(sessionId);
        if (outputStream == null) {
            log.warn("会话 {} 的输出流不存在", sessionId);
            return;
        }

        boolean locked = false;
        try {
            // 尝试获取锁，避免长时间阻塞
            locked = writeLock.tryLock(500, TimeUnit.MILLISECONDS);
            if (!locked) {
                log.warn("无法获取会话 {} 的写入锁，跳过数据包", sessionId);
                return;
            }

            // 分批写入数据，避免一次写入过多
            for (int offset = 0; offset < esData.length; offset += WRITE_CHUNK_SIZE) {
                if (!isSessionActive(sessionId)) {
                    log.debug("会话 {} 在写入过程中已停止", sessionId);
                    return;
                }

                log.info("正在写入数据到会话 {}，偏移量: {}, 长度: {}", sessionId, offset, WRITE_CHUNK_SIZE);

                int length = Math.min(WRITE_CHUNK_SIZE, esData.length - offset);
                outputStream.write(esData, offset, length);
                outputStream.flush();

                // 给读取端一些时间处理数据
                if (offset + WRITE_CHUNK_SIZE < esData.length) {
                    Thread.sleep(1);
                }
            }
        } catch (IOException e) {
            if (isSessionActive(sessionId)) {
                log.error("写入 ES 数据失败: {}", e.getMessage());
                cleanupResources(sessionId);
            } else {
                log.debug("会话 {} 的处理已停止", sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("数据写入被中断", e);
        } finally {
            if (locked) {
                writeLock.unlock();
            }
        }
    }

    /**
     * 检查会话是否活跃
     * @param sessionId 会话ID
     * @return 会话是否活跃
     */
    private boolean isSessionActive(int sessionId) {
        AtomicBoolean running = runningFlags.get(sessionId);
        Thread thread = processingThreads.get(sessionId);
        return running != null && running.get() && thread != null && thread.isAlive();
    }

    /**
     * 停止指定会话的视频流处理
     * @param sessionId 会话ID
     */
    public void stopStream(int sessionId) {
        log.info("正在停止会话 {} 的视频流", sessionId);
        AtomicBoolean running = runningFlags.get(sessionId);
        if (running != null) {
            running.set(false);
        }
        cleanupResources(sessionId);
    }

    /**
     * 清理指定会话的资源
     * @param sessionId 会话ID
     */
    private void cleanupResources(int sessionId) {
        log.info("开始清理会话 {} 的资源", sessionId);

        // 确保线程管理先被更新，防止新数据处理
        AtomicBoolean running = runningFlags.remove(sessionId);
        if (running != null) {
            running.set(false);
        }

        Thread thread = processingThreads.remove(sessionId);
        if (thread != null && thread.isAlive()) {
            try {
                // 给线程一些时间自然终止
                thread.join(2000);
                if (thread.isAlive()) {
                    log.warn("会话 {} 的线程未在2秒内终止，继续清理其他资源", sessionId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待线程终止被中断", e);
            }
        }

        // 获取写入锁，确保没有数据正在写入
        ReentrantLock writeLock = writeLocks.remove(sessionId);
        if (writeLock != null) {
            try {
                if (writeLock.tryLock(1000, TimeUnit.MILLISECONDS)) {
                    try {
                        cleanupIoResources(sessionId);
                    } finally {
                        writeLock.unlock();
                    }
                } else {
                    log.warn("无法获取会话 {} 的写入锁，强制清理资源", sessionId);
                    cleanupIoResources(sessionId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待获取写入锁被中断", e);
                cleanupIoResources(sessionId);
            }
        } else {
            cleanupIoResources(sessionId);
        }

        log.info("会话 {} 的资源清理完成", sessionId);
    }

    /**
     * 清理IO相关资源
     * @param sessionId 会话ID
     */
    private void cleanupIoResources(int sessionId) {
        // 关闭管道输出流
        PipedOutputStream outputStream = outputStreams.remove(sessionId);
        if (outputStream != null) {
            try {
                outputStream.close();
                log.debug("已关闭会话 {} 的输出流", sessionId);
            } catch (Exception e) {
                log.error("关闭输出流失败: {}", e.getMessage());
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
                log.error("停止抓取器失败: {}", e.getMessage());
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
                log.error("停止录制器失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 关闭服务，清理所有资源
     */
    public void shutdown() {
        log.info("关闭StreamService，清理所有资源");

        // 停止调度器
        scheduler.shutdownNow();

        // 停止所有流
        for (Integer sessionId : new HashSet<>(processingThreads.keySet())) {
            stopStream(sessionId);
        }
    }
}