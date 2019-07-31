package com.shouzhong.zbar;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import net.sourceforge.zbar.Config;
import net.sourceforge.zbar.Image;
import net.sourceforge.zbar.ImageScanner;
import net.sourceforge.zbar.Symbol;
import net.sourceforge.zbar.SymbolSet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


/**
 *
 *
 *
 */
public class ZBarScannerView extends FrameLayout implements Camera.PreviewCallback, CameraPreview.FocusAreaSetter {

    public static final String TAG = "ZBarScannerView";

    /*
     * 加载zbar动态库
     * zbar.jar中的类会用到
     */
    static {
        System.loadLibrary("iconv");
    }

    private CameraWrapper cameraWrapper;
    private IViewFinder viewFinderView;
    private CameraPreview cameraPreview;
    private Rect scaledRect, rotatedRect;
    private ArrayList<Camera.Area> focusAreas;
    private CameraHandlerThread cameraHandlerThread;
    private boolean shouldAdjustFocusArea;//是否需要自动调整对焦区域
    private ImageScanner imageScanner;
    private List<BarcodeFormat> formats;
    private Callback callback;
    private int[] previewSize;

    public ZBarScannerView(Context context) {
        this(context, null);
    }

    public ZBarScannerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZBarScannerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        //创建ImageScanner（zbar扫码器）并进行基本设置（如支持的码格式）
        setupScanner();
    }

    /**
     * Called as preview frames are displayed.<br/>
     * This callback is invoked on the event thread open(int) was called from.<br/>
     * (此方法与Camera.open运行于同一线程，在本项目中，就是CameraHandlerThread线程)
     */
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (callback == null) return;
        try {
            Camera.Parameters parameters = camera.getParameters();
            int previewWidth = parameters.getPreviewSize().width;
            int previewHeight = parameters.getPreviewSize().height;
            //根据ViewFinderView和preview的尺寸之比，缩放扫码区域
            Rect rect = getScaledRect(previewWidth, previewHeight);
            /*
             * 方案一：旋转图像数据
             */
            //int rotationCount = getRotationCount();//相机图像需要被顺时针旋转几次（每次90度）
            //if (rotationCount == 1 || rotationCount == 3) {//相机图像需要顺时针旋转90度或270度
            //    //交换宽高
            //    int tmp = previewWidth;
            //    previewWidth = previewHeight;
            //    previewHeight = tmp;
            //}
            ////旋转数据
            //data = rotateData(data, camera);

            /*
             * 方案二：旋转截取区域
             */
            rect = getRotatedRect(previewWidth, previewHeight, rect);

            //从preView的图像中截取扫码区域
            Image barcode = new Image(previewWidth, previewHeight, "Y800");
            barcode.setData(data);
            barcode.setCrop(rect.left, rect.top, rect.width(), rect.height());

            //使用zbar库识别扫码区域
            int result = imageScanner.scanImage(barcode);
            // 识别失败
            if (result == 0) {
                //再获取一帧图像数据进行识别（会再次触发onPreviewFrame方法）
                getOneMoreFrame();
                return;
            }
            // 识别成功
            SymbolSet syms = imageScanner.getResults();
            String s = null;
            for (Symbol sym : syms) {
                s = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT ? new String(sym.getDataBytes(), StandardCharsets.UTF_8) : sym.getData();
                if (!TextUtils.isEmpty(s)) {
                    break;//识别成功一个就跳出循环
                }
            }
            final String str = s;
            post(new Runnable() {//切换到主线程
                @Override
                public void run() {
                    if (callback != null) callback.result(str);
                }
            });
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setAutoFocusArea() {
        //设置对焦区域
        if (!shouldAdjustFocusArea || cameraWrapper == null) return;
        Camera.Parameters parameters = cameraWrapper.camera.getParameters();
        if (parameters.getMaxNumFocusAreas() <= 0) {
            Log.e(TAG, "不支持设置对焦区域");
            return;
        }
        if (focusAreas == null) {
            int width = 2000, height = 2000;
            Rect framingRect = viewFinderView.getFramingRect();//获得扫码框区域
            if (framingRect == null) return;
            int viewFinderViewWidth = ((View) viewFinderView).getWidth();
            int viewFinderViewHeight = ((View) viewFinderView).getHeight();
            //1.根据ViewFinderView和2000*2000的尺寸之比，缩放对焦区域
            Rect scaledRect = new Rect(framingRect);
            scaledRect.left = scaledRect.left * width / viewFinderViewWidth;
            scaledRect.right = scaledRect.right * width / viewFinderViewWidth;
            scaledRect.top = scaledRect.top * height / viewFinderViewHeight;
            scaledRect.bottom = scaledRect.bottom * height / viewFinderViewHeight;
            //2.旋转对焦区域
            Rect rotatedRect = new Rect(scaledRect);
            int rotationCount = getRotationCount();
            if (rotationCount == 1) {//若相机图像需要顺时针旋转90度，则将扫码框逆时针旋转90度
                rotatedRect.left = scaledRect.top;
                rotatedRect.top = 2000 - scaledRect.right;
                rotatedRect.right = scaledRect.bottom;
                rotatedRect.bottom = 2000 - scaledRect.left;
            } else if (rotationCount == 2) {//若相机图像需要顺时针旋转180度,则将扫码框逆时针旋转180度
                rotatedRect.left = 2000 - scaledRect.right;
                rotatedRect.top = 2000 - scaledRect.bottom;
                rotatedRect.right = 2000 - scaledRect.left;
                rotatedRect.bottom = 2000 - scaledRect.top;
            } else if (rotationCount == 3) {//若相机图像需要顺时针旋转270度，则将扫码框逆时针旋转270度
                rotatedRect.left = 2000 - scaledRect.bottom;
                rotatedRect.top = scaledRect.left;
                rotatedRect.right = 2000 - scaledRect.top;
                rotatedRect.bottom = scaledRect.right;
            }
            //3.坐标系平移
            Rect rect = new Rect(rotatedRect.left - 1000, rotatedRect.top - 1000, rotatedRect.right - 1000, rotatedRect.bottom - 1000);
            Camera.Area area = new Camera.Area(rect, 1000);
            focusAreas = new ArrayList<>();
            focusAreas.add(area);
        }
        parameters.setFocusAreas(focusAreas);
        cameraWrapper.camera.setParameters(parameters);
    }

    // ******************************************************************************
    //
    // ******************************************************************************

    /**
     * 回调
     *
     * @param callback
     */
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * 扫描区域
     *
     * @param viewFinderView
     */
    public void setViewFinder(IViewFinder viewFinderView) {
        if (viewFinderView == null || !(viewFinderView instanceof View)) throw new IllegalArgumentException("viewFinderView必须是View对象");
        this.viewFinderView = viewFinderView;
    }

    /**
     * 开启扫描
     *
     */
    public void onResume() {
        startCamera();
    }

    /**
     * 停止扫描
     *
     */
    public void onPause() {
        stopCamera();
    }

    /**
     * 设置支持的码格式
     */
    public void setFormats(List<BarcodeFormat> formats) {
        this.formats = formats;
        setupScanner();
    }

    public Collection<BarcodeFormat> getFormats() {
        if (formats == null) {
            return BarcodeFormat.ALL_FORMATS;
        }
        return formats;
    }

    public void restartPreviewAfterDelay(long delayMillis) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                getOneMoreFrame();
            }
        }, delayMillis);
    }

    /**
     * 开启/关闭闪光灯
     */
    public void setFlash(boolean flag) {
        if (cameraWrapper == null || !CameraUtils.isFlashSupported(cameraWrapper.camera)) return;
        Camera.Parameters parameters = cameraWrapper.camera.getParameters();
        if (TextUtils.equals(parameters.getFlashMode(), Camera.Parameters.FLASH_MODE_TORCH) && flag)
            return;
        if (TextUtils.equals(parameters.getFlashMode(), Camera.Parameters.FLASH_MODE_OFF) && !flag)
            return;
        parameters.setFlashMode(flag ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
        cameraWrapper.camera.setParameters(parameters);
    }

    /**
     * 切换闪光灯的点亮状态
     */
    public void toggleFlash() {
        if (cameraWrapper == null || !CameraUtils.isFlashSupported(cameraWrapper.camera)) return;
        Camera.Parameters parameters = cameraWrapper.camera.getParameters();
        if (TextUtils.equals(parameters.getFlashMode(), Camera.Parameters.FLASH_MODE_TORCH)) {
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        } else {
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        }
        cameraWrapper.camera.setParameters(parameters);
    }

    /**
     * 闪光灯是否被点亮
     */
    public boolean isFlashOn() {
        if (cameraWrapper == null || !CameraUtils.isFlashSupported(cameraWrapper.camera)) return false;
        Camera.Parameters parameters = cameraWrapper.camera.getParameters();
        return TextUtils.equals(parameters.getFlashMode(), Camera.Parameters.FLASH_MODE_TORCH);
    }

    /**
     * 设置是否要根据扫码框的位置去调整对焦区域的位置<br/>
     * 默认值为false，即不调整，会使用系统默认的配置，那么对焦区域会位于预览画面的中央<br/>
     * <br/>
     * (经测试，此功能对少数机型无效，待优化)
     */
    public void setShouldAdjustFocusArea(boolean shouldAdjustFocusArea) {
        this.shouldAdjustFocusArea = shouldAdjustFocusArea;
    }

    // ******************************************************************************
    //
    // ******************************************************************************

    /**
     * 再获取一帧图像数据进行识别（会再次触发onPreviewFrame方法）
     */
    private void getOneMoreFrame() {
        if (cameraWrapper != null) {
            cameraWrapper.camera.setOneShotPreviewCallback(this);
        }
    }

    /**
     * 创建ImageScanner并进行基本设置（如支持的码格式）
     */
    private void setupScanner() {
        imageScanner = new ImageScanner();
        imageScanner.setConfig(0, Config.X_DENSITY, 3);
        imageScanner.setConfig(0, Config.Y_DENSITY, 3);
        imageScanner.setConfig(Symbol.NONE, Config.ENABLE, 0);
        for (BarcodeFormat format : getFormats()) {//设置支持的码格式
            imageScanner.setConfig(format.getId(), Config.ENABLE, 1);
        }
    }

    void setupCameraPreview(final CameraWrapper cameraWrapper) {
        this.cameraWrapper = cameraWrapper;
        if (this.cameraWrapper == null) return;
        removeAllViews();
        if (previewSize == null) previewSize = getOptimalPreviewSize(getMeasuredWidth(), getMeasuredHeight());
        cameraPreview = new CameraPreview(getContext(), previewSize[0], previewSize[1], cameraWrapper, this, this);
        addView(cameraPreview);
        addView(((View) viewFinderView));
    }

    /**
     * 打开系统相机，并进行基本的初始化
     */
    private void startCamera() {
        if (cameraHandlerThread == null) {
            cameraHandlerThread = new CameraHandlerThread(this);
        }
        cameraHandlerThread.startCamera(CameraUtils.getDefaultCameraId());
    }

    /**
     * 释放相机资源等各种资源
     */
    private void stopCamera() {
        if (cameraHandlerThread != null) {
            cameraHandlerThread.quit();
            cameraHandlerThread = null;
        }
        if (cameraWrapper != null) {
            cameraPreview.stopCameraPreview();//停止相机预览并置空各种回调
            cameraPreview = null;
            cameraWrapper.camera.release();//释放资源
            cameraWrapper = null;
        }
        removeAllViews();
    }

    /**
     * 根据ViewFinderView和preview的尺寸之比，缩放扫码区域
     */
    private Rect getScaledRect(int previewWidth, int previewHeight) {
        if (scaledRect == null) {
            Rect framingRect = viewFinderView.getFramingRect();//获得扫码框区域
            int viewFinderViewWidth = ((View) viewFinderView).getWidth();
            int viewFinderViewHeight = ((View) viewFinderView).getHeight();
            int width, height;
            Point p = new Point();
            ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getSize(p);
            int o = p.x == p.y ? 0 : p.x < p.y ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
            if (o == Configuration.ORIENTATION_PORTRAIT && previewHeight < previewWidth || o == Configuration.ORIENTATION_LANDSCAPE && previewHeight > previewWidth) {
                width = previewHeight;
                height = previewWidth;
            } else {
                width = previewWidth;
                height = previewHeight;
            }
            scaledRect = new Rect(framingRect);
            scaledRect.left = scaledRect.left * width / viewFinderViewWidth;
            scaledRect.right = scaledRect.right * width / viewFinderViewWidth;
            scaledRect.top = scaledRect.top * height / viewFinderViewHeight;
            scaledRect.bottom = scaledRect.bottom * height / viewFinderViewHeight;
        }
        return scaledRect;
    }

    /**
     * 获取旋转后的Rect
     *
     * @param previewWidth
     * @param previewHeight
     * @param rect
     * @return
     */
    private Rect getRotatedRect(int previewWidth, int previewHeight, Rect rect) {
        if (rotatedRect == null) {
            int rotationCount = getRotationCount();
            rotatedRect = new Rect(rect);
            if (rotationCount == 1) {//若相机图像需要顺时针旋转90度，则将扫码框逆时针旋转90度
                rotatedRect.left = rect.top;
                rotatedRect.top = previewHeight - rect.right;
                rotatedRect.right = rect.bottom;
                rotatedRect.bottom = previewHeight - rect.left;
            } else if (rotationCount == 2) {//若相机图像需要顺时针旋转180度,则将扫码框逆时针旋转180度
                rotatedRect.left = previewWidth - rect.right;
                rotatedRect.top = previewHeight - rect.bottom;
                rotatedRect.right = previewWidth - rect.left;
                rotatedRect.bottom = previewHeight - rect.top;
            } else if (rotationCount == 3) {//若相机图像需要顺时针旋转270度，则将扫码框逆时针旋转270度
                rotatedRect.left = previewWidth - rect.bottom;
                rotatedRect.top = rect.left;
                rotatedRect.right = previewWidth - rect.top;
                rotatedRect.bottom = rect.right;
            }
        }
        return rotatedRect;
    }

    /**
     * 旋转data
     */
    private byte[] rotateData(byte[] data, Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        int width = parameters.getPreviewSize().width;
        int height = parameters.getPreviewSize().height;
        int rotationCount = getRotationCount();
        for (int i = 0; i < rotationCount; i++) {
            byte[] rotatedData = new byte[data.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++)
                    rotatedData[x * height + height - y - 1] = data[x + y * width];
            }
            data = rotatedData;
            int tmp = width;
            width = height;
            height = tmp;
        }
        return data;
    }

    /**
     * 获取（旋转角度/90）
     */
    private int getRotationCount() {
        int displayOrientation = cameraPreview.getDisplayOrientation();
        return displayOrientation / 90;
    }

    /**
     * 找到一个合适的previewSize（根据控件的尺寸）
     *
     * @param width 控件宽度
     * @param height 控件高度
     */
    private int[] getOptimalPreviewSize(int width, int height) {
        if (cameraWrapper == null) return new int[] {0, 0};
        //相机图像默认都是横屏(即宽>高)
        List<Camera.Size> sizes = cameraWrapper.camera.getParameters().getSupportedPreviewSizes();
        if (sizes == null) return new int[] {0, 0};
        int w, h;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            w = width;
            h = height;
        } else {
            w = height;
            h = width;
        }
        double targetRatio = w * 1.0 / h;
        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        double aspectTolerance = Double.MAX_VALUE;
        int targetHeight = h;

        // 获取最佳尺寸
        for (Camera.Size size : sizes) {
            double ratio = size.width * 1.0 / size.height;
            if (Math.abs(ratio - targetRatio) > aspectTolerance) continue;
            if (Math.abs(size.height - targetHeight) <= minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
                aspectTolerance = Math.abs(ratio - targetRatio);
            }
        }
        return new int[] {optimalSize.width, optimalSize.height};
    }
}