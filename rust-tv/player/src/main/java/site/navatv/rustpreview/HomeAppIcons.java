package site.navatv.rustpreview;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;

/** Native launcher artwork, read only for apps the user pinned to this TV's home. */
public final class HomeAppIcons {
    private HomeAppIcons() { }

    public static byte[] icon(Context context, String packageName) {
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(packageName);
            int side = 96;
            Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            float ratio = (float)Math.max(1, drawable.getIntrinsicWidth()) / Math.max(1, drawable.getIntrinsicHeight());
            int width = ratio >= 1 ? side : Math.max(1, Math.round(side * ratio));
            int height = ratio <= 1 ? side : Math.max(1, Math.round(side / ratio));
            int left = (side - width) / 2, top = (side - height) / 2;
            drawable.setBounds(left, top, left + width, top + height);
            drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            return png.toByteArray();
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }
}
