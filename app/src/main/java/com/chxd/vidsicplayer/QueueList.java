package com.chxd.vidsicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/**
 * Play-queue list with drag & drop. Grab the 3-bar handle on the left of a row and move it;
 * the list scrolls by itself near the top / bottom edge. Reports every step through {@link Cb}.
 */
class QueueList extends ListView {
    interface Cb {
        void move(int from, int to); // dragged row moved from one position to another
        void done();                 // finger lifted
    }

    Cb cb;
    int drag = -1;
    float y, grab;
    Bitmap shot;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int handleW, edge, speed;
    final Runnable auto = new Runnable() {
        @Override public void run() {
            if (drag < 0) return;
            int dy = 0;
            if (y < edge) dy = -Math.max(1, Math.round((edge - Math.max(0, y)) / edge * speed));
            else if (y > getHeight() - edge) dy = Math.max(1, Math.round((y - (getHeight() - edge)) / edge * speed));
            if (dy != 0) { scrollListBy(dy); retarget(); invalidate(); }
            postOnAnimation(this);
        }
    };

    QueueList(Context c) {
        super(c);
        handleW = Ui.dp(c, 52);
        edge = Ui.dp(c, 56);
        speed = Ui.dp(c, 14);
        paint.setAlpha(235);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getX() < handleW) {
            int pos = pointToPosition((int) e.getX(), (int) e.getY());
            if (pos != INVALID_POSITION && begin(pos, e.getY())) return true;
        }
        return super.onInterceptTouchEvent(e);
    }

    boolean begin(int pos, float fy) {
        View v = getChildAt(pos - getFirstVisiblePosition());
        if (v == null || v.getWidth() == 0 || v.getHeight() == 0) return false;
        shot = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(shot);
        c.drawColor(Ui.SURF2);
        v.draw(c);
        grab = fy - v.getTop();
        y = fy;
        drag = pos;
        v.setAlpha(0f);
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        getParent().requestDisallowInterceptTouchEvent(true);
        postOnAnimation(auto);
        return true;
    }

    void retarget() {
        int pos = pointToPosition(getWidth() / 2, (int) Math.max(0, Math.min(getHeight() - 1, y)));
        if (pos != INVALID_POSITION && pos != drag) {
            int from = drag;
            drag = pos;
            if (cb != null) cb.move(from, pos);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (drag < 0) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                y = e.getY();
                retarget();
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                removeCallbacks(auto);
                if (shot != null) { shot.recycle(); shot = null; }
                if (cb != null) cb.done();
                invalidate();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv);
        if (drag >= 0 && shot != null) {
            float top = Math.max(0, Math.min(getHeight() - shot.getHeight(), y - grab));
            cv.drawBitmap(shot, 0, top, paint);
        }
    }
}
