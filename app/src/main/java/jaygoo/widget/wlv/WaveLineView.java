/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: Copyright 2017 JayGoo
 *
 * Vendored from jaygoo/WaveLineView
 * (https://github.com/Jay-Goo/WaveLineView), licensed under the Apache
 * License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0);
 * modified for fcitx5-android.
 * Local deviations from upstream: clearDraw() now runs under
 * RenderView.surfaceLock to serialize its lockCanvas with the render
 * thread (was a main/render thread race); targetVolume defaults to 0
 * instead of 50 so no fake wave is shown before the first volume update;
 * initParameters() runs under surfaceLock so a dying render thread's
 * last frame cannot observe nulled sampling arrays; clearDraw() clears
 * with PorterDuff CLEAR in transparent mode and guards its final unlock.
 */
package jaygoo.widget.wlv;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.util.SparseArray;

import org.fcitx.fcitx5.android.R;

import java.util.ArrayList;
import java.util.List;

/**
 * WaveLineView（移植自言犀），绘制音量驱动的波形动画。
 */
public class WaveLineView extends RenderView {

    private static final int DEFAULT_SAMPLING_SIZE = 64;
    private static final float DEFAULT_OFFSET_SPEED = 250F;
    private static final int DEFAULT_SENSIBILITY = 5;

    private int samplingSize;
    private float offsetSpeed;
    private float volume = 0;
    // fork 修正：上游初值 50 会在首个 setVolume 到达前渲染出半高假波形，
    // 波形覆盖层场景下就绪前闪现一次无输入波。改为 0（无输入不画波）
    private int targetVolume = 0;
    private float perVolume;
    private int sensibility;
    private int backGroundColor = Color.WHITE;
    private int lineColor;
    private int thickLineWidth;
    private int fineLineWidth;

    private final Paint paint = new Paint();
    { paint.setDither(true); paint.setAntiAlias(true); }

    private final List<Path> paths = new ArrayList<>();
    { for (int i = 0; i < 4; i++) paths.add(new Path()); }

    private final float[] pathFuncs = { 0.6f, 0.35f, 0.1f, -0.1f };
    private float[] samplingX;
    private float[] mapX;
    private int width, height, centerHeight;
    private float amplitude;
    private final SparseArray<Double> recessionFuncs = new SparseArray<>();
    private boolean isPrepareLineAnimEnd = false;
    private int lineAnimX = 0;
    private float prepareAlpha = 0f;
    private boolean isOpenPrepareAnim = false;
    private boolean isTransparentMode = false;

    public WaveLineView(Context context) { this(context, null); }
    public WaveLineView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public WaveLineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initAttr(attrs);
    }

    private void initAttr(AttributeSet attrs) {
        TypedArray t = getContext().obtainStyledAttributes(attrs, R.styleable.WaveLineView);
        backGroundColor = t.getColor(R.styleable.WaveLineView_wlvBackgroundColor, Color.WHITE);
        samplingSize = t.getInt(R.styleable.WaveLineView_wlvSamplingSize, DEFAULT_SAMPLING_SIZE);
        lineColor = t.getColor(R.styleable.WaveLineView_wlvLineColor, Color.parseColor("#2ED184"));
        thickLineWidth = (int) t.getDimension(R.styleable.WaveLineView_wlvThickLineWidth, 6);
        fineLineWidth = (int) t.getDimension(R.styleable.WaveLineView_wlvFineLineWidth, 2);
        offsetSpeed = t.getFloat(R.styleable.WaveLineView_wlvMoveSpeed, DEFAULT_OFFSET_SPEED);
        sensibility = t.getInt(R.styleable.WaveLineView_wlvSensibility, DEFAULT_SENSIBILITY);
        isTransparentMode = backGroundColor == Color.TRANSPARENT;
        t.recycle();

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(lineColor);
        paint.setStrokeWidth(thickLineWidth);

        // 保持上游 setZOrderOnTop(true)：surface 成为 SurfaceFlinger 的
        // 独立 overlay 平面，逐帧合成开销最小。曾改用 setZOrderMediaOverlay
        // （避免遮挡同窗口长按气泡 popup），但它把波形 surface 压到窗口
        // 内容之下，TRANSLUCENT surface 每帧都要与整窗做 alpha 混合，
        // 实测动画明显卡顿，且 MATCH_PARENT 打孔使覆盖层底色透出下层
        // app 内容。回退为上游行为；代价是语音会话期间同窗口 popup
        // 可能被波形 surface 遮住（P2 视觉瑕疵，接受）
        setZOrderOnTop(true);
        if (getHolder() != null) getHolder().setFormat(PixelFormat.TRANSLUCENT);
    }

    @Override protected void doDrawBackground(Canvas canvas) {
        if (isTransparentMode) canvas.drawColor(backGroundColor, PorterDuff.Mode.CLEAR);
        else canvas.drawColor(backGroundColor);
    }

    private boolean isParametersNull() { return (samplingX == null || mapX == null || pathFuncs == null); }

    @Override protected void onRender(Canvas canvas, long millisPassed) {
        float offset = millisPassed / offsetSpeed;
        if (isParametersNull()) initDraw(canvas);
        if (lineAnim(canvas)) {
            resetPaths();
            softerChangeVolume();
            for (int i = 0; i <= samplingSize; i++) {
                if (isParametersNull()) { initDraw(canvas); if (isParametersNull()) return; }
                float x = samplingX[i];
                float curY = (float) (amplitude * calcValue(mapX[i], offset));
                for (int n = 0; n < paths.size(); n++) {
                    float realY = curY * pathFuncs[n] * volume * 0.01f;
                    paths.get(n).lineTo(x, centerHeight + realY);
                }
            }
            for (Path p : paths) { p.moveTo(width, centerHeight); }
            for (int n = 0; n < paths.size(); n++) {
                if (n == 0) { paint.setStrokeWidth(thickLineWidth); paint.setAlpha((int) (255 * alphaInAnim())); }
                else { paint.setStrokeWidth(fineLineWidth); paint.setAlpha((int) (100 * alphaInAnim())); }
                canvas.drawPath(paths.get(n), paint);
            }
        }
    }

    private void softerChangeVolume() {
        if (volume < targetVolume - perVolume) volume += perVolume;
        else if (volume > targetVolume + perVolume) volume = Math.max(perVolume * 2, volume - perVolume);
        else volume = targetVolume;
    }

    private float alphaInAnim() {
        if (!isOpenPrepareAnim) return 1f;
        if (prepareAlpha < 1f) prepareAlpha += 0.02f; else prepareAlpha = 1f; return prepareAlpha;
    }

    private boolean lineAnim(Canvas canvas) {
        if (isPrepareLineAnimEnd || !isOpenPrepareAnim) return true;
        paths.get(0).moveTo(0, centerHeight);
        paths.get(1).moveTo(width, centerHeight);
        for (int i = 1; i <= samplingSize; i++) {
            float x = 1f * i * lineAnimX / samplingSize;
            paths.get(0).lineTo(x, centerHeight);
            paths.get(1).lineTo(width - x, centerHeight);
        }
        paths.get(0).moveTo(width / 2f, centerHeight);
        paths.get(1).moveTo(width / 2f, centerHeight);
        lineAnimX += width / 60;
        canvas.drawPath(paths.get(0), paint); canvas.drawPath(paths.get(1), paint);
        if (lineAnimX > width / 2) { isPrepareLineAnimEnd = true; return true; }
        return false;
    }

    private void resetPaths() { for (Path p : paths) { p.rewind(); p.moveTo(0, centerHeight); } }

    private void initParameters() { lineAnimX = 0; prepareAlpha = 0f; isPrepareLineAnimEnd = false; samplingX = null; }

    // fork 修正：initParameters 置 samplingX=null 须与渲染线程的
    // surfaceLock 串行——stopAnim 销毁线程后旧线程仍可能跑最后一帧
    // onRender，isParametersNull 检查与解引用之间有窗口，无锁会 NPE
    @Override public void startAnim() { synchronized (surfaceLock) { initParameters(); } super.startAnim(); }
    @Override public void stopAnim() { super.stopAnim(); clearDraw(); }

    public void clearDraw() {
        // fork 修正：stopAnim() 在主线程调用本方法，与渲染线程
        // synchronized(surfaceLock) 内的 lockCanvas 并发争锁会抛
        // IllegalStateException；同一 surfaceLock 串行化两段 lockCanvas
        synchronized (surfaceLock) {
            Canvas canvas = null;
            try {
                canvas = getHolder().lockCanvas(null);
                if (canvas != null) {
                    // fork 修正：透明模式下 drawColor(TRANSPARENT) 默认
                    // SrcOver 是 no-op，残留最后一帧波形；改用 CLEAR 擦除，
                    // 与 doDrawBackground 的透明路径对称
                    if (isTransparentMode) canvas.drawColor(backGroundColor, PorterDuff.Mode.CLEAR);
                    else canvas.drawColor(backGroundColor);
                    resetPaths(); for (Path p: paths) canvas.drawPath(p, paint);
                }
            } catch (Exception ignored) {
            // fork 修正：unlock 自身也可能抛（lock 成功后 surface 失效的
            // 极端竞态），包一层防止异常穿透主线程调用栈
            } finally { try { if (canvas != null) getHolder().unlockCanvasAndPost(canvas); } catch (Exception ignored) { } }
        }
    }

    private void initDraw(Canvas canvas) {
        width = canvas.getWidth(); height = canvas.getHeight(); if (width == 0 || height == 0 || samplingSize == 0) return;
        centerHeight = height >> 1; amplitude = height / 3.0f; perVolume = sensibility * 0.35f;
        samplingX = new float[samplingSize + 1]; mapX = new float[samplingSize + 1];
        float gap = width / (float) samplingSize;
        for (int i = 0; i <= samplingSize; i++) { float x = i * gap; samplingX[i] = x; mapX[i] = (x / (float) width) * 4 - 2; }
        paint.setStyle(Paint.Style.STROKE); paint.setColor(lineColor); paint.setStrokeWidth(thickLineWidth);
    }

    private double calcValue(float mapX, float offset) {
        int keyX = (int) (mapX * 1000); offset %= 2; double sinFunc = Math.sin(Math.PI * mapX - offset * Math.PI);
        Double recessionFunc = recessionFuncs.get(keyX);
        if (recessionFunc == null) { recessionFunc = 4 / (4 + Math.pow(mapX, 4)); recessionFuncs.put(keyX, recessionFunc); }
        return sinFunc * recessionFunc;
    }

    public void setMoveSpeed(float moveSpeed) { this.offsetSpeed = moveSpeed; }
    public void setVolume(int volume) { if (Math.abs(targetVolume - volume) > perVolume) { this.targetVolume = volume; checkVolumeValue(); } }
    public void setBackGroundColor(int backGroundColor) { this.backGroundColor = backGroundColor; this.isTransparentMode = (backGroundColor == Color.TRANSPARENT); }
    public void setLineColor(int lineColor) { this.lineColor = lineColor; }
    public void setSensibility(int sensibility) { this.sensibility = sensibility; checkSensibilityValue(); }

    private void checkVolumeValue() { if (targetVolume > 100) targetVolume = 100; if (targetVolume < 0) targetVolume = 0; }
    private void checkSensibilityValue() { if (sensibility > 10) sensibility = 10; if (sensibility < 1) sensibility = 1; }
}
