package com.hcbt.hcisup.common;

import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_dnn.*;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_dnn;
import org.bytedeco.opencv.global.opencv_imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * javacv-yolo5和yolo8检测推理服务
 */
@Slf4j
public class Inference {
    // 模型路径
    private String modelPath;
    // 模型输入尺寸（如 640x640）
    private Size modelShape;
    // 是否启用 CUDA 加速
    private boolean cudaEnabled;
    // OpenCV 的 DNN 网络对象
    private Net net;
    // 模型分类标签
    private List<String> classes ;
    // 模型推理的置信度阈值（用于 YOLOv5）
    private float modelConfidenceThreshold = 0.25f;  // 模型输出置信度的阈值，低于此阈值的检测框将被过滤掉
    // 分数阈值，用于筛选低置信度的框（用于 NMS 前过滤）
    private float modelScoreThreshold = 0.45f;  // 模型输出得分的阈值，用于在非极大值抑制 (NMS) 之前过滤掉低得分的检测框
    // NMS 阈值，IOU 超过此值的框会被去重
    private float modelNMSThreshold = 0.50f;  // 非极大值抑制 (NMS) 的 IoU 阈值，用于去除重叠度过高的冗余检测框
    // 是否对图像进行 letterbox 操作以适配方形输入
    private boolean letterBoxForSquare = true;  // 是否对输入图像进行 Letterbox 处理以适应方形模型输入

    /**
     * 构造函数
     * @param onnxModelPath 模型文件路径（ONNX 格式）
     * @param modelInputShape 模型输入尺寸
     * @param runWithCuda 是否使用 CUDA 加速
     * @param classes 分类标签列表
     */
    public Inference(String onnxModelPath, Size modelInputShape, boolean runWithCuda, List<String> classes) {
        this.modelPath = onnxModelPath;
        this.modelShape = modelInputShape;
        this.cudaEnabled = runWithCuda;
        this.classes = classes;
        // 加载 ONNX 模型
        loadOnnxNetwork();
    }

    /**
     * 加载 ONNX 模型
     */
    private void loadOnnxNetwork() {
        net = opencv_dnn.readNetFromONNX(modelPath);
        if (cudaEnabled) {
            log.info("Running on CUDA");
            net.setPreferableBackend(opencv_dnn.DNN_BACKEND_CUDA);
            net.setPreferableTarget(opencv_dnn.DNN_TARGET_CUDA);
        } else {
            log.info("Running on CPU");
            net.setPreferableBackend(opencv_dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(opencv_dnn.DNN_TARGET_CPU);
        }
    }

    /**
     * 执行推理，返回目标检测结果 ,对输入的图像进行推理
     * @param input 待进行目标检测的输入图像 (OpenCV Mat 对象)
     * @return 包含检测结果的 Detection 对象列表
     */
    public List<Detection> runInference(Mat input) {
        Mat modelInput = input.clone();
        int[] padXY = new int[2];        // 存储 padding 的 x 和 y
        float[] scale = new float[1];   // 存储缩放因子

        // 如果需要将图像调整为正方形
        if (letterBoxForSquare && modelShape.width() == modelShape.height()) {
            modelInput = formatToSquare(modelInput, padXY, scale);
        }
        // 构建 blob，进行归一化并调整尺寸
        Mat blob = opencv_dnn.blobFromImage(modelInput, 1.0 / 255.0, modelShape, new Scalar(), true, false, opencv_core.CV_32F);
        net.setInput(blob);
        // 推理输出
        MatVector outputs = new MatVector();
        StringVector outNames = net.getUnconnectedOutLayersNames();
        net.forward(outputs, outNames);

        Mat output = outputs.get(0);            // 假设只有一个输出
        int rows = output.size(1);          // 输出行数
        int dimensions = output.size(2);    // 每个检测的维度数（坐标+类别分数）

        // 判断是否是 YOLOv8 输出格式（维度在前）
        boolean yolov8 = false;
        if (dimensions > rows) { // 检查shape[2]是否大于shape[1]（YOLOv8）
            yolov8 = true;
            rows = output.size(2);
            dimensions = output.size(1);

            // 转置为正常的行表示检测框
            output = output.reshape(1, dimensions);
            Mat transposed = new Mat();
            opencv_core.transpose(output, transposed);
            output = transposed;
        }

        // 创建索引器读取输出内容
        FloatIndexer data = output.createIndexer();
        List<Integer> classIds = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Rect> boxes = new ArrayList<>();

        // 遍历每一行（一个检测框）
        for (int i = 0; i < rows; i++) {
            if (yolov8) {
                // YOLOv8 模式：得分从第4列开始，前4个是 bbox 信息
                Mat scores = new Mat(1, classes.size(), opencv_core.CV_32FC1);
                FloatIndexer scoresIdx = scores.createIndexer();

                // 复制类别得分
                for (int j = 0; j < classes.size(); j++) {
                    scoresIdx.put(0, j, data.get(i, j + 4));
                }

                // 查找最大得分及其索引
                Point maxLoc = new Point();
                DoublePointer maxVal = new DoublePointer(1);
                opencv_core.minMaxLoc(scores, null, maxVal, null, maxLoc, null);
                double maxScore = maxVal.get();
                // 判断是否超过阈值
                if (maxScore > modelScoreThreshold) {
                    confidences.add((float) maxScore);
                    classIds.add(maxLoc.x());
                    // 获取坐标并反缩放还原
                    float x = data.get(i, 0);
                    float y = data.get(i, 1);
                    float w = data.get(i, 2);
                    float h = data.get(i, 3);

                    int left = (int) ((x - 0.5 * w - padXY[0]) / scale[0]);
                    int top = (int) ((y - 0.5 * h - padXY[1]) / scale[0]);
                    int width = (int) (w / scale[0]);
                    int height = (int) (h / scale[0]);

                    boxes.add(new Rect(left, top, width, height));
                }
            } else {
                // YOLOv5
                float confidence = data.get(i, 4);

                if (confidence >= modelConfidenceThreshold) {
                    Mat scores = new Mat(1, classes.size(), opencv_core.CV_32FC1);
                    FloatIndexer scoresIdx = scores.createIndexer();

                    // 复制类别得分
                    for (int j = 0; j < classes.size(); j++) {
                        scoresIdx.put(0, j, data.get(i, j + 5));
                    }

                    // 查找最大得分及其索引
                    Point maxLoc = new Point();
                    DoublePointer maxVal = new DoublePointer(1);
                    opencv_core.minMaxLoc(scores, null, maxVal, null, maxLoc, null);
                    double maxClassScore = maxVal.get();

                    // 如果得分大于阈值，保存检测结果
                    if (maxClassScore > modelScoreThreshold) {
                        confidences.add(confidence);
                        classIds.add(maxLoc.x());

                        float x = data.get(i, 0);
                        float y = data.get(i, 1);
                        float w = data.get(i, 2);
                        float h = data.get(i, 3);

                        int left = (int) ((x - 0.5 * w - padXY[0]) / scale[0]);
                        int top = (int) ((y - 0.5 * h - padXY[1]) / scale[0]);
                        int width = (int) (w / scale[0]);
                        int height = (int) (h / scale[0]);
                        boxes.add(new Rect(left, top, width, height));
                    }
                }
            }
        }

        Mat bboxesMat = new Mat(boxes.size(), 4, opencv_core.CV_32S);
        IntIndexer bboxIndexer = bboxesMat.createIndexer();
        for (int i = 0; i < boxes.size(); i++) {
            Rect r = boxes.get(i);
            bboxIndexer.put(i, 0, r.x());
            bboxIndexer.put(i, 1, r.y());
            bboxIndexer.put(i, 2, r.width());
            bboxIndexer.put(i, 3, r.height());
        }

        // 准备进行 NMS 过滤
        RectVector bboxesVec = new RectVector();
        FloatPointer scoresPtr = new FloatPointer(confidences.size());
        for (int i = 0; i < boxes.size(); i++) {
            Rect r = boxes.get(i);
            bboxesVec.push_back(new Rect(r.x(), r.y(), r.width(), r.height()));
            scoresPtr.put(i, confidences.get(i));
        }
        IntPointer indicesPtr = new IntPointer();
        opencv_dnn.NMSBoxes(bboxesVec, scoresPtr, modelScoreThreshold, modelNMSThreshold, indicesPtr);
        // 提取保留的索引
        int[] indicesArray = new int[(int) indicesPtr.limit()];
        indicesPtr.get(indicesArray);
        // 构建最终检测结果
        List<Detection> detections = new ArrayList<>();
        Random rand = new Random();
        for (int idx : indicesArray) {
            Detection detection = new Detection();
            detection.setClassId(classIds.get(idx));
            detection.setConfidence(confidences.get(idx));
            detection.setColor(new Scalar(
                    rand.nextInt(156) + 100,         // 保证颜色偏亮
                    rand.nextInt(156) + 100,
                    rand.nextInt(156) + 100,
                    0
            ));
            detection.setClassName(classes.get(detection.getClassId()));
            detection.setBox(boxes.get(idx));
            detections.add(detection);
        }

        return detections;
    }

    /**
     * 将图像转为 letterbox 方形图像，保持比例缩放 + 居中填充 将图像转换为正方形，保持纵横比
     * @param source 原始图像
     * @param padXY 填充的X和Y值
     * @param scale 缩放因子
     * @return 处理后的图像
     */
    private Mat formatToSquare(Mat source, int[] padXY, float[] scale) {
        int col = source.cols();  // 原图宽
        int row = source.rows();  // 原图高
        int m_inputWidth = modelShape.width();  // 模型输入宽
        int m_inputHeight = modelShape.height(); // 模型输入高
        // 计算缩放因子
        scale[0] = Math.min((float) m_inputWidth / col, (float) m_inputHeight / row);
        int resizedW = (int) (col * scale[0]);
        int resizedH = (int) (row * scale[0]);
        // 计算 padding（居中填充）
        padXY[0] = (m_inputWidth - resizedW) / 2;
        padXY[1] = (m_inputHeight - resizedH) / 2;
        // 缩放图像
        Mat resized = new Mat();
        opencv_imgproc.resize(source, resized, new Size(resizedW, resizedH));
        // 新建一个黑色背景图像并复制图像到中间
        Mat result = new Mat(m_inputHeight, m_inputWidth, source.type(), new Scalar(0, 0, 0, 0));
        Mat roi = result.apply(new Rect(padXY[0], padXY[1], resizedW, resizedH));
        resized.copyTo(roi);

        return result;
    }

}
