package com.hcbt.hcisup.service;

import com.hcbt.hcisup.common.Inference;
import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 检测服务
 */
@Slf4j
@Service
public class DetectionService {
    // 定义安全背心和行人的推理模型
    private final Inference inferenceVest;
    private final Inference inferencePedestrian;
    // 定义结果保存路径
    private final Path resultDir;

    // 构造函数，初始化推理模型和结果保存路径
    public DetectionService(
            @Value("${app.models.model-path-vest}") String modelPathVest,
            @Value("${app.models.model-path-pedestrian}") String modelPathPedestrian,
            @Value("${app.result-dir}") String resultDirPath) {
        this.resultDir = Paths.get(resultDirPath);
        log.info("Loading vest model from: " + modelPathVest);
        log.info("Loading pedestrian model from: " + modelPathPedestrian);
        // 加载安全背心和行人的类别
        List<String> classesVest = loadClasses(modelPathVest.replace(".onnx", ".txt"));
        List<String> classesPedestrian = loadClasses(modelPathPedestrian.replace(".onnx", ".txt"));
        // 初始化推理模型
        this.inferenceVest = new Inference(modelPathVest, new Size(640, 640), false, classesVest);
        this.inferencePedestrian = new Inference(modelPathPedestrian, new Size(640, 640), false, classesPedestrian);
    }

    // 加载类别
    private List<String> loadClasses(String filePath) {
        try {
            // 记录日志
            log.info("Loading classes from: " + filePath);
            // 读取文件中的所有行
            return Files.readAllLines(Paths.get(filePath));
        } catch (IOException e) {
            // 抛出运行时异常
            throw new RuntimeException("无法读取类名文件: " + filePath, e);
        }
    }

    /**
     * 检测图像中的对象并返回处理后的图像文件名
     * @param imagePath 图像文件路径
     * @return 处理后的图像文件名
     */
    public String detectImage(String imagePath) {
        // 加载图像
        log.info("Loading image from: " + imagePath);
        // 使用 OpenCV 读取图像为 Mat 对象
        Mat image = opencv_imgcodecs.imread(imagePath);
        if (image.empty()) {
            throw new RuntimeException("无法读取图像文件: " + imagePath);
        }

        // 运行对象检测  调用安全背心模型执行推理，返回检测结果
        List<Detection> detectionsVest = inferenceVest.runInference(image);
        // 给所有检测到的安全背心设置统一颜色（绿色）
        for (Detection d : detectionsVest) {
            d.setColor(new Scalar(0, 255, 0, 0)); // 绿色用于安全背心
        }
        // 调用行人检测模型执行推理
        List<Detection> detectionsPedestrian = inferencePedestrian.runInference(image);
        // 给所有检测到的行人设置统一颜色（蓝色）
        for (Detection d : detectionsPedestrian) {
            d.setColor(new Scalar(255, 0, 0, 0)); // 蓝色用于行人
        }

        //  合并两个模型的所有检测结果  在图像上绘制检测结果
        List<Detection> allDetections = new ArrayList<>();
        allDetections.addAll(detectionsVest);           // 添加安全背心检测框
        allDetections.addAll(detectionsPedestrian);     // 添加行人检测框
        // 将所有检测结果绘制到原始图像上
        drawDetections(image, allDetections);

        // 保存结果图像
        // 获取原始文件名（不含路径）
        String originalFilename = Paths.get(imagePath).getFileName().toString();
        // 构建新的结果图像文件名（添加前缀 result_）
        String resultFilename = "result_" + originalFilename;
        // 构建保存路径（使用 resultDir 目录）
        Path resultPath = resultDir.resolve(resultFilename);
        // 使用 OpenCV 将绘制后的图像保存到文件系统
        opencv_imgcodecs.imwrite(resultPath.toString(), image);
        // 返回生成的结果图像文件名
        return resultFilename;
    }

    /**
     * 处理视频文件并进行对象检测
     * @param videoPath 视频文件路径
     * @return 处理后的视频文件名
     */
    public String detectVideo(String videoPath) {
        // 生成唯一的输出文件名
        String resultFilename = "result_" + UUID.randomUUID().toString() + ".mp4";
        String outputPath = resultDir.resolve(resultFilename).toString();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath)) {
            // 启动视频读取器（解码器）
            grabber.start();

            // 获取视频参数 视频的宽度、高度和帧率信息
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            double frameRate = grabber.getVideoFrameRate();

            // 创建视频写入器（编码器）
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath, width, height);
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);  // 使用 H.264 编码
            recorder.setFormat("mp4");  // 输出格式为 mp4
            recorder.setFrameRate(frameRate); // 设置帧率
            recorder.setVideoBitrate(grabber.getVideoBitrate());  // 设置码率为与原视频相同
            recorder.start();  // 启动写入器

            // 创建OpenCV帧转换器  用于帧之间的 OpenCV 与 JavaCV 的转换
            OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

            // 处理每一帧
            Frame frame;
            while ((frame = grabber.grab()) != null) {
                if (frame.image != null) {
                    // 将Java帧转换为OpenCV Mat
                    Mat mat = converter.convert(frame);

                    // 检测对象
                    List<Detection> detectionsVest = inferenceVest.runInference(mat);
                    for (Detection d : detectionsVest) {
                        d.setColor(new Scalar(0, 255, 0, 0)); // 绿色用于安全背心
                    }

                    List<Detection> detectionsPedestrian = inferencePedestrian.runInference(mat);
                    for (Detection d : detectionsPedestrian) {
                        d.setColor(new Scalar(255, 0, 0, 0)); // 蓝色用于行人
                    }

                    List<Detection> allDetections = new ArrayList<>();
                    allDetections.addAll(detectionsVest);
                    allDetections.addAll(detectionsPedestrian);

                    // 在帧上绘制检测结果
                    drawDetections(mat, allDetections);

                    // 将处理后的帧写入输出视频
                    recorder.record(converter.convert(mat));
                }
            }

            // 关闭资源
            recorder.stop();
            recorder.release();
            grabber.stop();

            return resultFilename;
        } catch (Exception e) {
            throw new RuntimeException("视频处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在图像上绘制检测结果  包括边界框和标签。
     * @param image 图像
     * @param detections 检测结果列表
     */
    void drawDetections(Mat image, List<Detection> detections) {
        if (detections == null) {
            log.warn("检测结果为空，跳过绘制");
            return;
        }
        // 遍历每一个检测结果，绘制边界框和标签
        for (Detection detection : detections) {
            // 绘制边界框
            opencv_imgproc.rectangle(
                    image,                          // 目标图像
                    detection.getBox(),             // 边界框（Rect）
                    detection.getColor(),           // 边框颜色（已设置）
                    2,                              // 边框粗细
                    opencv_imgproc.LINE_8,          // 线型
                    0                               // 偏移量
            );

            // 准备标签文本
            String label = detection.getClassName() + ": " + String.format("%.2f", detection.getConfidence());

            // 获取文本大小
            int[] baseLine = new int[1];
            Size labelSize = opencv_imgproc.getTextSize(
                    label,                          // 标签内容
                    opencv_imgproc.FONT_HERSHEY_SIMPLEX, // 字体
                    0.6,                            // 字体缩放
                    1,                              // 线宽
                    baseLine                        // 基线，用于计算文本对齐
            );

            // 计算标签位置，确保不超出图像边界
            int x = Math.max(detection.getBox().x(), 0);
            int y = Math.max(detection.getBox().y() - labelSize.height(), 0);

            // 如果标签太靠近图像顶部，则将其放在框的底部
            if (y < 5) {
                y = detection.getBox().y() + detection.getBox().height();
            }

            // 确保标签宽度不超出图像
            int width = Math.min(labelSize.width(), image.cols() - x);

            // 绘制标签背景
            opencv_imgproc.rectangle(
                    image,
                    new Point(x, y),
                    new Point(x + width, y + labelSize.height() + baseLine[0]),
                    detection.getColor()
            );

            // 绘制标签文本
            opencv_imgproc.putText(
                    image,
                    label,
                    new Point(x, y + labelSize.height()),
                    opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    new Scalar(255, 255, 255, 0),
                    1,
                    opencv_imgproc.LINE_AA,
                    false);
        }
    }

    // 同时运行安全背心和行人检测模型，合并返回所有检测结果。 运行推理
    public List<Detection> runInference(Mat image) {
        // 调用两个模型分别执行推理
        List<Detection> detectionsVest = inferenceVest.runInference(image);
        List<Detection> detectionsPedestrian = inferencePedestrian.runInference(image);
        // 合并两个检测结果列表
        detectionsVest.addAll(detectionsPedestrian);
        return detectionsVest;
    }
}
