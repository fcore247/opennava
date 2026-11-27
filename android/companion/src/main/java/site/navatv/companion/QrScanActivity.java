package site.navatv.companion;

import android.content.Intent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.zxing.client.android.R;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

public final class QrScanActivity extends CaptureActivity {
    static final String EXTRA_HOME = "site.navatv.companion.RETURN_HOME";

    @Override protected DecoratedBarcodeView initializeContent() {
        Surface ui = new Surface(this);
        LinearLayout page = ui.column();
        page.setBackgroundColor(Surface.PAPER);
        FrameLayout preview = new FrameLayout(this);
        getLayoutInflater().inflate(R.layout.zxing_capture, preview, true);
        page.addView(preview, new LinearLayout.LayoutParams(Surface.MATCH, 0, 1));
        page.addView(ui.button("Close", "close", false, () -> {
            setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_HOME, true));
            finish();
        }), ui.lp(Surface.MATCH, 56));
        setContentView(page);
        return preview.findViewById(R.id.zxing_barcode_scanner);
    }
}
