package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * 二维码管理器
 */
public class QRCodeManager {

    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;
    /** 🟢 新增：内置 UI 线程 Handler，用于将生成好的 Bitmap 传回主线程 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ====================== 构造函数 ======================
    public QRCodeManager(Context context) {
        this.context = context;
    }

    // ====================================================================
    // 1. 显示二维码对话框（已改为内部异步生成，100% 免疫主线程卡顿）
    // ====================================================================
    public void showQRCodeDialog(String content) {
        final ImageView iv = new ImageView(context);
        iv.setBackgroundColor(Color.LTGRAY); // 生成前先给个灰色占位背景

        // 在子线程生成 Bitmap
        new Thread(() -> {
            final Bitmap bitmap = createQR(content, 250);
            // 切回主线程显示
            mainHandler.post(() -> {
                if (bitmap != null) {
                    iv.setImageBitmap(bitmap);
                } else {
                    iv.setBackgroundColor(Color.LTGRAY);
                }
            });
        }).start();

        new AlertDialog.Builder(context)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ====================================================================
    // 2. 生成二维码图片（已优化格式，解决灰色方块问题）
    // ====================================================================
    public Bitmap createQR(String text, int size) {
        try {
            // 🛡️ 强制设置最小尺寸，防止 ZXing 因尺寸过小编码失败返回 null
            if (size < 150) {
                size = 200;
            }
            // 使用 ZXing 生成二维码矩阵
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);

            // 🛡️ 使用 ARGB_8888 格式，防止黑白像素在 RGB_565 下显示为灰色
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

            // 遍历每一个像素，设置颜色
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
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
