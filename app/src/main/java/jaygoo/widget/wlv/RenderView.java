/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: Copyright 2017 JayGoo
 *
 * Vendored from jaygoo/WaveLineView
 * (https://github.com/Jay-Goo/WaveLineView), licensed under the Apache
 * License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0);
 * modified for fcitx5-android.
 * Local deviations from upstream: surfaceLock widened to protected so
 * WaveLineView.clearDraw() can serialize its lockCanvas with the render
 * thread; release() no longer releases the system-owned Surface;
 * RenderThread state fields are volatile; stopAnim() now destroys the
 * render thread instead of leaving it idle-spinning (startThread()
 * rebuilds it on demand), the pause-wait exits on destroy, surfaceDestroyed
 * wakes a parked thread so it can die, and lockCanvas/render failures
 * stop the thread instead of crashing; surfaceCreated() reuses a live
 * thread instead of orphaning it; onWindowFocusChanged() pauses rendering
 * on focus loss instead of restarting the render thread.
 */
package jaygoo.widget.wlv;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 封装的SurfaceView，用于波形渲染线程管理（移植自言犀同名实现）。
 */
public abstract class RenderView extends SurfaceView implements SurfaceHolder.Callback {

    // fork 修正：渲染线程在 surfaceLock 内读，主线程在锁外写
    // （startAnim/stopAnim），须 volatile 保证可见性
    private volatile boolean isStartAnim = false;
    // fork 修正：private → protected，WaveLineView.clearDraw 的
    // lockCanvas 须与渲染线程共用同一把锁（见该处注释）
    protected final static Object surfaceLock = new Object();
    private RenderThread renderThread;

    protected abstract void doDrawBackground(Canvas canvas);

    protected abstract void onRender(Canvas canvas, long millisPassed);

    public RenderView(Context context) { this(context, null); }
    public RenderView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public RenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getHolder().addCallback(this);
    }

    private static class RenderThread extends Thread {
        private static final long SLEEP_TIME = 16;
        private final WeakReference<RenderView> renderView;
        // fork 修正：running/destoryed/isPause 由主线程（startThread/
        // stopAnim/surfaceDestroyed/onPause/onResume）与渲染线程并发读写，
        // 且读侧不全在 surfaceLock 内（while 条件、setRun、isRunning），
        // 必须 volatile 保证可见性
        private volatile boolean running = false;
        private volatile boolean destoryed = false;
        private volatile boolean isPause = false;

        public RenderThread(RenderView renderView) {
            super("RenderThread");
            this.renderView = new WeakReference<>(renderView);
        }

        private SurfaceHolder getSurfaceHolder() {
            RenderView rv = renderView.get();
            return rv != null ? rv.getHolder() : null;
        }

        private RenderView getRenderView() { return renderView.get(); }

        @Override
        public void run() {
            long startAt = System.currentTimeMillis();
            while (!destoryed) {
                synchronized (surfaceLock) {
                    // fork 修正：等待条件加 !destoryed——stopAnim/surfaceDestroyed
                    // 销毁时若线程正 parked 在 isPause wait，须能被唤醒退出，
                    // 否则线程泄漏（surfaceDestroyed/stopAnim 会 notifyAll/interrupt）
                    while (isPause && !destoryed) {
                        try { surfaceLock.wait(); } catch (InterruptedException ignored) { }
                    }
                    if (destoryed) break;
                    if (running) {
                        SurfaceHolder holder = getSurfaceHolder();
                        RenderView rv = getRenderView();
                        if (holder != null && rv != null) {
                            Canvas canvas = null;
                            try {
                                canvas = holder.lockCanvas();
                            } catch (Exception e) {
                                // fork 修正：surface 已销毁/不可用（detach 后
                                // startAnim 重建线程等路径），lockCanvas 抛异常。
                                // 线程内未捕获异常会杀进程，这里终止线程兜底
                                destoryed = true;
                            }
                            if (canvas != null) {
                                // fork 修正：绘制体整体入 try——onRender 任一路径
                                // 抛异常时未捕获异常会杀死线程/进程，且已 lock 的
                                // canvas 未 unlock 会让后续 lockCanvas 永久阻塞；
                                // finally 保证 unlock，异常时终止线程兜底
                                try {
                                    rv.doDrawBackground(canvas);
                                    if (rv.isStartAnim) {
                                        rv.render(canvas, System.currentTimeMillis() - startAt);
                                    }
                                } catch (Exception e) {
                                    running = false;
                                    destoryed = true;
                                } finally {
                                    try { holder.unlockCanvasAndPost(canvas); } catch (Exception ignored) { }
                                }
                            }
                        } else {
                            running = false;
                        }
                    }
                }
                try { Thread.sleep(SLEEP_TIME); } catch (InterruptedException ignored) { }
            }
        }

        public void setRun(boolean isRun) { this.running = isRun; }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // fork 修正：上游无条件覆盖 renderThread——若 startAnim 先于
        // surfaceCreated 到达（VISIBLE 同帧即 start，surface 建立是异步
        // 回调），startThread 已建的活线程引用被覆盖、失控且永不退出
        // （16ms 空转泄漏）。只在无线程/线程已死时才新建，活线程复用
        if (renderThread == null || renderThread.destoryed) {
            renderThread = new RenderThread(this);
        }
        if (isStartAnim) startThread();
    }

    public void onResume() {
        synchronized (surfaceLock) {
            if (renderThread != null) {
                renderThread.isPause = false;
                surfaceLock.notifyAll();
            }
        }
    }

    public void onPause() {
        synchronized (surfaceLock) { if (renderThread != null) renderThread.isPause = true; }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            if (renderThread != null) { renderThread.setRun(false); renderThread.destoryed = true; }
            // fork 修正：线程可能 parked 在 isPause wait（onPause/失焦后），
            // 须唤醒让它检查 destoryed 退出，否则线程泄漏
            surfaceLock.notifyAll();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // fork 修正：原实现 else 分支调 startThread() 方向反了——失焦反而
        // 拉起渲染。复用现成暂停机制：失焦 onPause() 挂起渲染线程
        // （isPause + surfaceLock.wait，保留 isStartAnim 与动画进度），
        // 聚焦 onResume() 唤醒并确保线程在跑（stopAnim 销毁后 startThread
        // 会按需重建，见下）。不用上游 stopAnim 式失焦停法：它会清
        // isStartAnim，聚焦后动画无法自动恢复。
        if (hasFocus) {
            onResume();
            if (isStartAnim) startThread();
        } else {
            onPause();
        }
    }

    private void render(Canvas canvas, long millisPassed) { onRender(canvas, millisPassed); }

    public void startAnim() { isStartAnim = true; startThread(); }

    private void startThread() {
        // fork 修正（PERF-02）：stopAnim/surfaceDestroyed 会销毁线程
        // （destoryed=true，Thread 对象不可重启），此处补重建分支；
        // surfaceCreated 只在 surface 新建时建线程，之后 startAnim
        // 重启依赖这里的按需重建
        if (renderThread == null || renderThread.destoryed) {
            renderThread = new RenderThread(this);
        }
        if (!renderThread.running) {
            renderThread.setRun(true);
            try { if (renderThread.getState() == Thread.State.NEW) renderThread.start(); } catch (Exception ignored) { }
        }
    }

    public void stopAnim() {
        isStartAnim = false;
        if (renderThread != null) {
            // fork 修正（PERF-02）：原实现只 running=false，线程仍 16ms
            // 空转到 surfaceDestroyed 才死。直接销毁线程，重启由
            // startThread 重建；interrupt 唤醒 sleep/wait 使其尽快退出
            renderThread.setRun(false);
            renderThread.destoryed = true;
            renderThread.interrupt();
        }
    }

    public boolean isRunning() { return renderThread != null && renderThread.running; }

    public void release() {
        // fork 修正：Surface 归系统管理（SurfaceFlinger/SurfaceView 生命周期），
        // 非本类创建、不具所有权；getSurface().release() 会误释放它，
        // 渲染线程后续 lockCanvas 崩溃/泄漏。release() 仅注销 callback。
        getHolder().removeCallback(this);
    }
}
