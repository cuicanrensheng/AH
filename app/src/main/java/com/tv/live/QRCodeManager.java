package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.ImageView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * 二维码管理器
 *
 * 【职责】
 * 负责二维码相关的功能，包括：
 * 1. 生成二维码图片
 * 2. 显示二维码对话框
 *
 * 【为什么拆分？】
 * 功能独立，二维码生成是通用功能，
 * 以后其他地方可能也需要生成二维码。
 *
 * 【使用方式】
 * QRCodeManager qrCodeManager = new QRCodeManager(context);
 * qrCodeManager.showQRCodeDialog("https://xxx.com");
 * Bitmap bmp = qrCodeManager.createQR("https://xxx.com", 250);
 */
public class QRCodeManager {

    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     * @param context 上下文
     */
    public QRCodeManager(Context context) {
        this.context = context;
    }

    // ====================================================================
    // 1. 显示二维码对话框
    // ====================================================================
    /**
     * 显示二维码对话框
     *
     * @param content 二维码内容（通常是 URL）
     */
    public void showQRCodeDialog(String content) {
        ImageView iv = new ImageView(context);
        iv.setImageBitmap(createQR(content, 250));

        new AlertDialog.Builder(context)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ====================================================================
    // 2. 生成二维码图片
    // ====================================================================
    /**
     * 生成二维码图片
     *
     * 【原理】
     * 使用 ZXing 库的 QRCodeWriter 生成二维码，
     * 然后遍历每一个像素，设置到 Bitmap 上。
     *
     * @param text 二维码内容
     * @param size 二维码尺寸（像素）
     * @return 二维码 Bitmap，失败返回 null
     */
    public Bitmap createQR(String text, int size) {
        try {
            // 使用 ZXing 生成二维码矩阵
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);

            // 创建 Bitmap
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);

            // 遍历每一个像素，设置颜色
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    // get(x, y) 返回 true 表示黑色，false 表示白色
                    bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return bmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
