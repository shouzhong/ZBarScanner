package com.shouzhong.zbar;

import android.graphics.Bitmap;
import android.text.TextUtils;

import net.sourceforge.zbar.Config;
import net.sourceforge.zbar.Image;
import net.sourceforge.zbar.ImageScanner;
import net.sourceforge.zbar.Symbol;
import net.sourceforge.zbar.SymbolSet;

import java.nio.charset.StandardCharsets;

/**
 * Created by Administrator on 2018/07/31.
 *
 * zbar的识别图片二维码
 */

public class DecodeUtils {

    /**
     * 识别图片，建议在子线程运行
     *
     * @param bmp
     * @return
     */
    public static String decode(Bitmap bmp) throws Exception {
        if (bmp == null) throw new Exception("图片不存在");
        byte[] data = getYUV420sp(bmp.getWidth(), bmp.getHeight(), bmp);
        ImageScanner imageScanner = new ImageScanner();
        imageScanner.setConfig(0, Config.X_DENSITY, 3);
        imageScanner.setConfig(0, Config.Y_DENSITY, 3);
        imageScanner.setConfig(Symbol.NONE, Config.ENABLE, 0);
        for (BarcodeFormat format : BarcodeFormat.ALL_FORMATS) {//设置支持的码格式
            imageScanner.setConfig(format.getId(), Config.ENABLE, 1);
        }
        Image barcode = new Image(bmp.getWidth(), bmp.getHeight(), "Y800");
        barcode.setData(data);
        //使用zbar库识别扫码区域
        int result = imageScanner.scanImage(barcode);
        if (result == 0) throw new Exception("识别失败");
        SymbolSet syms = imageScanner.getResults();
        for (Symbol sym : syms) {
            String symData;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                symData = new String(sym.getDataBytes(), StandardCharsets.UTF_8);
            } else {
                symData = sym.getData();
            }
            if (!TextUtils.isEmpty(symData)) {
                //识别成功一个就结束
                return symData;
            }
        }
        throw new Exception("识别失败");
    }

    /**
     * YUV420sp
     *
     * @param inputWidth
     * @param inputHeight
     * @param scaled
     * @return
     */
    public static byte[] getYUV420sp(int inputWidth, int inputHeight, Bitmap scaled) {
        int[] argb = new int[inputWidth * inputHeight];
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        int len = inputWidth * inputHeight + ((inputWidth % 2 == 0 ? inputWidth : (inputWidth + 1)) * (inputHeight % 2 == 0 ? inputHeight : (inputHeight + 1))) / 2;
        byte[] yuv = new byte[len];
        // 帧图片的像素大小
        final int frameSize = inputWidth * inputHeight;
        // ---YUV数据---
        int Y, U, V;
        // Y的index从0开始
        int yIndex = 0;
        // UV的index从frameSize开始
        int uvIndex = frameSize;
        // ---颜色数据---
        int a, R, G, B;
        int argbIndex = 0;
        // ---循环所有像素点，RGB转YUV---
        for (int j = 0; j < inputHeight; j++) {
            for (int i = 0; i < inputWidth; i++) {
                // a is not used obviously
                a = (argb[argbIndex] & 0xff000000) >> 24;
                R = (argb[argbIndex] & 0xff0000) >> 16;
                G = (argb[argbIndex] & 0xff00) >> 8;
                B = (argb[argbIndex] & 0xff);
                //
                argbIndex++;

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                //
                Y = Math.max(0, Math.min(Y, 255));
                U = Math.max(0, Math.min(U, 255));
                V = Math.max(0, Math.min(V, 255));

                // ---Y---
                yuv[yIndex++] = (byte) Y;
                // ---UV---
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    //
                    yuv[uvIndex++] = (byte) V;
                    //
                    yuv[uvIndex++] = (byte) U;
                }
            }
        }
        scaled.recycle();
        return yuv;
    }
}
