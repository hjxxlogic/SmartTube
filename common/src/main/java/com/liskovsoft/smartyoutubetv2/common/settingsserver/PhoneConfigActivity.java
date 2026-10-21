package com.liskovsoft.smartyoutubetv2.common.settingsserver;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.liskovsoft.smartyoutubetv2.common.R;

import java.util.HashMap;
import java.util.Map;

/**
 * TV screen for phone-based configuration: shows a QR code with the one-time
 * session URL and live status (saved / failed / session ended). The embedded
 * web server runs only while this screen is open (5-minute hard limit, see
 * PhoneConfigManager).
 */
public class PhoneConfigActivity extends Activity implements PhoneConfigManager.Listener {
    private TextView mStatusView;
    private TextView mUrlView;
    private ImageView mQrView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        try {
            String url = PhoneConfigManager.instance().start(this, this);
            mUrlView.setText(url);
            mQrView.setImageBitmap(renderQr(url));
            setStatus(getString(R.string.phone_config_waiting));
        } catch (IllegalStateException e) {
            setStatus(e.getMessage());
            mUrlView.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        PhoneConfigManager.instance().stop();
        super.onDestroy();
    }

    @Override
    public void onConfigSaved(String message) {
        runOnUiThread(() -> setStatus(getString(R.string.phone_config_saved) + "\n" + message));
    }

    @Override
    public void onServerStopped() {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                setStatus(getString(R.string.phone_config_stopped));
            }
        });
    }

    private void setStatus(String text) {
        if (mStatusView != null) {
            mStatusView.setText(text);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 32, 48, 32);

        TextView title = new TextView(this);
        title.setText(R.string.phone_config_title);
        title.setTextSize(28);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.phone_config_hint);
        hint.setTextSize(17);
        root.addView(hint);

        mQrView = new ImageView(this);
        int qrSize = 560;
        mQrView.setLayoutParams(new LinearLayout.LayoutParams(qrSize, qrSize));
        root.addView(mQrView);

        mUrlView = new TextView(this);
        mUrlView.setTextSize(15);
        root.addView(mUrlView);

        mStatusView = new TextView(this);
        mStatusView.setTextSize(17);
        root.addView(mStatusView);

        Button close = new Button(this);
        close.setText(android.R.string.cancel);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = 24;
        root.addView(close, closeParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private Bitmap renderQr(String content) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
