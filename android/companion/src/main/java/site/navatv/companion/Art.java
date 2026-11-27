package site.navatv.companion;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Small line icons that inherit the colour and size of their surrounding control. */
final class Art extends View {
    private final String name;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;

    Art(Context c, String name, int color) {
        super(c);
        this.name = name;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void line(Canvas c, float... x) {
        Path path = new Path();
        path.moveTo(x[0], x[1]);
        for (int i = 2; i < x.length; i += 2) path.lineTo(x[i], x[i + 1]);
        c.drawPath(path, p);
    }

    private void poly(Canvas c, float... x) {
        p.setStyle(Paint.Style.FILL);
        line(c, x);
        p.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        float scale = Math.min(getWidth(), getHeight()) / 24f;
        c.translate((getWidth() - 24 * scale) / 2f, (getHeight() - 24 * scale) / 2f);
        c.scale(scale, scale);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.65f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (name) {
            case "history":
                c.drawArc(4, 4, 20, 20, -150, 300, false, p);
                line(c, 4.2f, 4.6f, 4.6f, 9.2f, 9, 8.4f);
                line(c, 12, 8, 12, 12.4f, 15.2f, 14.2f);
                break;
            case "settings":
                c.drawCircle(12, 12, 3.2f, p);
                c.drawCircle(12, 12, 7, p);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * i / 4;
                    line(c, 12 + 7 * (float) Math.cos(angle), 12 + 7 * (float) Math.sin(angle),
                            12 + 9.6f * (float) Math.cos(angle), 12 + 9.6f * (float) Math.sin(angle));
                }
                break;
            case "arrowUpRight":
                line(c, 5, 19, 18, 6);
                line(c, 7.5f, 6, 18, 6, 18, 16.5f);
                break;
            case "close":
                line(c, 5.5f, 5.5f, 18.5f, 18.5f);
                line(c, 5.5f, 18.5f, 18.5f, 5.5f);
                break;
            case "schedule":
                c.drawRoundRect(2.5f, 4, 21.5f, 20.5f, 2, 2, p);
                line(c, 3, 9, 21, 9);
                line(c, 7.5f, 2.5f, 7.5f, 5.5f);
                line(c, 16.5f, 2.5f, 16.5f, 5.5f);
                line(c, 7, 13, 16, 13);
                line(c, 7, 16.5f, 12, 16.5f);
                break;
            case "saved":
                line(c, 6, 3.5f, 18, 3.5f, 18, 20.5f, 12, 16.5f, 6, 20.5f, 6, 3.5f);
                line(c, 9.5f, 9, 14.5f, 9);
                break;
            case "resume":
                poly(c, 6.5f, 3.5f, 20, 12, 6.5f, 20.5f, 6.5f, 3.5f);
                break;
            case "pause":
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(6, 4, 10, 20, 1.2f, 1.2f, p);
                c.drawRoundRect(14, 4, 18, 20, 1.2f, 1.2f, p);
                break;
            case "record":
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 6.5f, p);
                break;
            case "scan":
                line(c, 3, 9, 3, 4, 8, 4);
                line(c, 16, 4, 21, 4, 21, 9);
                line(c, 3, 15, 3, 20, 8, 20);
                line(c, 16, 20, 21, 20, 21, 15);
                line(c, 6, 12, 18, 12);
                break;
            case "photo":
                c.drawRoundRect(3, 4, 21, 20, 2, 2, p);
                c.drawCircle(8, 9, 1.3f, p);
                line(c, 4, 17, 10, 12, 13, 15, 16, 11, 20, 15);
                break;
            case "tv":
                c.drawRoundRect(2.4f, 4.4f, 21.6f, 17.4f, 2.5f, 2.5f, p);
                line(c, 8, 20.6f, 16, 20.6f);
                break;
            case "remote":
                c.drawRoundRect(6, 2, 18, 22, 4, 4, p);
                c.drawCircle(12, 9, 3.3f, p);
                line(c, 10, 16.7f, 14, 16.7f);
                break;
            case "library":
                c.drawRoundRect(2.5f, 4.5f, 7, 19.6f, 1, 1, p);
                c.drawRoundRect(9, 4.5f, 13.5f, 19.6f, 1, 1, p);
                line(c, 16, 5, 18.7f, 4.5f, 22, 18.9f, 19, 19.5f, 16, 5);
                break;
            case "cast":
                c.drawRoundRect(3, 3.5f, 22, 18, 1.9f, 1.9f, p);
                c.drawCircle(6, 17, 1, p);
                c.drawArc(-2, 12, 11, 24, -81, -52, false, p);
                break;
            case "back":
                line(c, 10, 5, 3, 12, 10, 19);
                line(c, 4, 12, 21, 12);
                break;
            case "home":
                line(c, 2.5f, 11.5f, 12, 3.2f, 21.5f, 11.5f);
                line(c, 5.5f, 9, 5.5f, 20, 18.5f, 20, 18.5f, 9);
                break;
            case "account":
                c.drawCircle(12, 8.2f, 3.8f, p);
                c.drawArc(4.5f, 14, 19.5f, 28, 200, 140, false, p);
                break;
            case "back10":
            case "skip10": {
                boolean ahead = "skip10".equals(name);
                c.save();
                if (ahead) c.scale(-1, 1, 12, 12);
                // An open circle with its arrow head at the top, turning back or ahead.
                c.drawArc(3.2f, 3.6f, 20.8f, 21.2f, -80, -300, false, p);
                line(c, 14.6f, 1.2f, 11.6f, 3.9f, 14.8f, 6.4f);
                c.restore();
                Paint.Style style = p.getStyle();
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(7.6f);
                p.setTextAlign(Paint.Align.CENTER);
                p.setFakeBoldText(true);
                c.drawText("10", 12, 15.2f, p);
                p.setFakeBoldText(false);
                p.setStyle(style);
                break;
            }
            case "apps":
                for (int x = 4; x <= 13; x += 9)
                    for (int y = 4; y <= 13; y += 9) c.drawRoundRect(x, y, x + 7, y + 7, 2, 2, p);
                break;
            case "menu":
                for (int i = 7; i < 20; i += 5) line(c, 4, i, 20, i);
                break;
            case "power":
                line(c, 12, 2.5f, 12, 11);
                c.drawArc(4, 4, 20, 20, -58, 296, false, p);
                break;
            case "rewind":
                line(c, 4, 5, 4, 19);
                poly(c, 18, 4.8f, 7, 12, 18, 19.2f, 18, 4.8f);
                break;
            case "forward":
                line(c, 20, 5, 20, 19);
                poly(c, 6, 4.8f, 17, 12, 6, 19.2f, 6, 4.8f);
                break;
            case "playpause":
                poly(c, 3.5f, 4.5f, 13, 12, 3.5f, 19.5f, 3.5f, 4.5f);
                line(c, 16, 5, 16, 19);
                line(c, 20, 5, 20, 19);
                break;
            case "left":
                line(c, 14.5f, 6, 8.5f, 12, 14.5f, 18);
                break;
            case "right":
                line(c, 9.5f, 6, 15.5f, 12, 9.5f, 18);
                break;
            case "up":
                line(c, 6, 14.5f, 12, 8.5f, 18, 14.5f);
                break;
            case "down":
                line(c, 6, 9.5f, 12, 15.5f, 18, 9.5f);
                break;
            case "folder":
                line(c, 2.5f, 8, 2.5f, 5, 9, 5, 11, 7.5f, 21.5f, 7.5f, 21.5f, 19.5f, 2.5f, 19.5f, 2.5f, 8);
                line(c, 3, 10, 21, 10);
                break;
            case "search":
                c.drawCircle(10.5f, 10.5f, 6.7f, p);
                line(c, 15.7f, 15.7f, 21, 21);
                break;
            case "volume":
                line(c, 3, 10, 7, 10, 12, 6, 12, 18, 7, 14, 3, 14, 3, 10);
                c.drawArc(9, 6, 19, 18, -53, 106, false, p);
                break;
            case "pencil":
                line(c, 4, 20, 5.2f, 15.2f, 16, 4.4f, 19.6f, 8, 8.8f, 18.8f, 4, 20);
                line(c, 13.8f, 6.6f, 17.4f, 10.2f);
                break;
            case "plus":
                line(c, 12, 5, 12, 19);
                line(c, 5, 12, 19, 12);
                break;
            case "minus":
                line(c, 5, 12, 19, 12);
                break;
            case "ok":
                line(c, 4.5f, 12, 9.5f, 17, 19.5f, 7);
                break;
            case "arrow":
                line(c, 5, 12, 19, 12);
                line(c, 13, 6, 19, 12, 13, 18);
                break;
            case "onair":
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 2.5f, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawArc(5, 5, 19, 19, -48, 96, false, p);
                c.drawArc(5, 5, 19, 19, 132, 96, false, p);
                c.drawArc(1.5f, 1.5f, 22.5f, 22.5f, -45, 90, false, p);
                c.drawArc(1.5f, 1.5f, 22.5f, 22.5f, 135, 90, false, p);
                break;
            case "message":
                c.drawRoundRect(2.8f, 4, 21.2f, 18, 3, 3, p);
                line(c, 5, 17.5f, 4, 21, 10, 18);
                break;
        }
        c.restore();
    }
}
