package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import site.navatv.launcher.PhoneGuide.Channel;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** A finite, vertically snapping wheel with a fixed reading line in the centre. */
final class ChannelWheel extends FrameLayout {
    private final Surface u;
    private final Consumer<Channel> preview;
    private final FrameLayout barrel;
    private final RecyclerView list;
    private final LinearLayoutManager layout;
    private final Stations adapter = new Stations();
    private final int rowHeight;
    private List<Channel> stations = Collections.emptyList();
    private int position;
    private boolean dragging;
    private long lastTick;
    private boolean userMoved;
    private Consumer<Channel> settled, picked;

    /** The viewer let go of the wheel on a channel, or tapped the channel on the reading line. */
    void listen(Consumer<Channel> settled, Consumer<Channel> picked) {
        this.settled = settled;
        this.picked = picked;
    }

    String selectedId() { return stations.isEmpty() ? "" : stations.get(position).id; }

    boolean busy() { return dragging || userMoved; }

    ChannelWheel(MainActivity a, Consumer<Channel> preview) {
        super(a);
        u = a.ui;
        this.preview = preview;
        float scaledText = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22, getResources().getDisplayMetrics());
        rowHeight = Math.max(u.d(62), (int) Math.ceil(2.6f * scaledText) + u.d(8));
        barrel = new FrameLayout(a);
        addView(barrel, new LayoutParams(MATCH, MATCH, Gravity.CENTER));
        View band = new View(a);
        band.setBackground(u.shape(0xff262b3d, 19, 0xff4a4470));
        barrel.addView(band, new LayoutParams(MATCH, rowHeight, Gravity.CENTER));

        list = new RecyclerView(a);
        layout = new LinearLayoutManager(a) {
            @Override protected void calculateExtraLayoutSpace(RecyclerView.State state, int[] extra) {
                extra[0] = extra[1] = rowHeight * 3;
            }
        };
        list.setLayoutManager(layout);
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setItemAnimator(null);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(false);
        barrel.addView(list, new LayoutParams(MATCH, MATCH));
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paintRows());
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { paintRows(); }
            @Override public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) dragging = userMoved = true;
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                dragging = false;
                if (userMoved && !stations.isEmpty() && settled != null) {
                    // The snap helper may still nudge the row; report once it rests on the line.
                    post(() -> {
                        if (list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || !userMoved) return;
                        userMoved = false;
                        settled.accept(centered());
                    });
                }
            }
        });
        new LinearSnapHelper().attachToRecyclerView(list);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        barrel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void show(List<Channel> group, String initialId) {
        list.stopScroll();
        userMoved = false;
        stations = group;
        position = 0;
        for (int i = 0; i < group.size(); i++) if (group.get(i).id.equals(initialId)) position = i;
        adapter.notifyDataSetChanged();
        layout.scrollToPositionWithOffset(position, 0);
        updateSelection();
    }

    Channel centered() {
        View row = list.findChildViewUnder(list.getWidth() / 2f, list.getHeight() / 2f);
        if (row != null) {
            int index = list.getChildAdapterPosition(row);
            if (index != RecyclerView.NO_POSITION && index < stations.size()) return stations.get(index);
        }
        return stations.get(position);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int height = Math.min(MeasureSpec.getSize(heightSpec), rowHeight * 5);
        barrel.getLayoutParams().height = height;
        int spare = Math.max(0, height - rowHeight);
        int top = spare / 2;
        if (list.getPaddingTop() != top || list.getPaddingBottom() != spare - top)
            list.setPadding(0, top, 0, spare - top);
        super.onMeasure(widthSpec, heightSpec);
    }

    private void paintRows() {
        if (stations.isEmpty() || list.getHeight() == 0) return;
        float middle = list.getHeight() / 2f;
        float nearest = Float.MAX_VALUE;
        int candidate = position;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            int index = list.getChildAdapterPosition(row);
            if (index == RecyclerView.NO_POSITION) continue;
            float offset = (row.getTop() + rowHeight / 2f - middle) / rowHeight;
            float distance = Math.abs(offset);
            if (distance < nearest) { nearest = distance; candidate = index; }
            float bend = Math.min(distance, 2.7f);
            row.setPivotX(row.getWidth() / 2f);
            row.setPivotY(rowHeight / 2f);
            row.setRotationX(Math.max(-48f, Math.min(48f, offset * 18f)));
            row.setScaleX(1f - .055f * bend);
            row.setScaleY(1f - .07f * bend);
            row.setAlpha(1f / (1f + .75f * bend * bend));
        }
        if (candidate != position) {
            position = candidate;
            updateSelection();
            long now = SystemClock.uptimeMillis();
            if (dragging && now - lastTick >= 60) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                lastTick = now;
            }
        }
    }

    private void updateSelection() {
        Channel current = stations.get(position);
        setContentDescription("Channel wheel");
        setStateDescription("Channel " + current.number + ", " + current.name);
        preview.accept(current);
    }

    private boolean moveTo(int index, boolean animate) {
        if (index < 0 || index >= stations.size()) return false;
        list.stopScroll();
        View row = layout.findViewByPosition(index);
        if (animate && row != null) {
            int offset = row.getTop() - list.getPaddingTop();
            list.smoothScrollBy(0, offset);
        } else {
            layout.scrollToPositionWithOffset(index, 0);
        }
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return moveTo(position - 1, false);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return moveTo(position + 1, false);
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (stations.isEmpty()) return;
        info.setClassName("android.widget.SeekBar");
        info.setScrollable(stations.size() > 1);
        info.setRangeInfo(new AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1, stations.size(), position + 1));
        if (position > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (position + 1 < stations.size()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return moveTo(position + 1, false);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return moveTo(position - 1, false);
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null
                && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))
            return moveTo(Math.round(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) - 1, false);
        return super.performAccessibilityAction(action, arguments);
    }

    private final class Row extends RecyclerView.ViewHolder {
        final TextView number, name;
        Row(Context context) {
            super(new LinearLayout(context));
            LinearLayout line = (LinearLayout) itemView;
            line.setLayoutParams(new RecyclerView.LayoutParams(MATCH, rowHeight));
            line.setGravity(Gravity.CENTER_VERTICAL);
            u.pad(line, 17, 0, 11, 0);
            number = u.text("", 15, 0xff8c93a8, 640);
            number.setFontFeatureSettings("tnum");
            line.addView(number, u.lp(Math.max(42, (int)(33 * getResources().getConfiguration().fontScale)), WRAP));
            name = u.text("", 22, INK, 740);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(name, u.weight(WRAP));
            line.setOnClickListener(v -> {
                int index = getBindingAdapterPosition();
                if (index < 0 || index >= stations.size()) return;
                if (index == position) {
                    if (picked != null) picked.accept(stations.get(index));
                } else {
                    userMoved = true;
                    moveTo(index, true);
                }
            });
        }
    }

    private final class Stations extends RecyclerView.Adapter<Row> {
        @Override public Row onCreateViewHolder(ViewGroup parent, int type) { return new Row(parent.getContext()); }
        @Override public void onBindViewHolder(Row row, int index) {
            Channel station = stations.get(index);
            row.number.setText(String.format(Locale.ROOT, "%02d", station.number));
            row.name.setText(station.name);
        }
        @Override public int getItemCount() { return stations.size(); }
    }
}
