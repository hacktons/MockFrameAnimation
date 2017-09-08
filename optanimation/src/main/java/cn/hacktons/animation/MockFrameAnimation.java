package cn.hacktons.animation;


import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.widget.ImageView;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Based on <a href="https://github.com/tigerjj/FasterAnimationsContainer">FasterAnimationsContainer</>
 * Changes:
 * <ul>
 * <li>fix warning when reuse bitmap with option.in;</li>
 * <li>fix animation frozen issue after Home press</li>
 * <li>{@link MockFrameAnimation} is no longer singleton, so each ImageView
 * may control it's animation;</li>
 * <li>global bitmap cache supported;</li>
 * </ul>
 */
public class MockFrameAnimation extends Drawable implements Runnable, Drawable.Callback {
    private class AnimationFrame {
        private int mResourceId;
        private int mDuration;

        AnimationFrame(int resourceId, int duration) {
            mResourceId = resourceId;
            mDuration = duration;
        }

        public int getResourceId() {
            return mResourceId;
        }

        public int getDuration() {
            return mDuration;
        }
    }

    // list for all frames of animation
    private ArrayList<AnimationFrame> mFrames;
    // index of current frame
    private int mIndex;
    // true if the animation should continue running. Used to stop the animation
    private boolean mShouldRun;
    // true if the animation prevents starting the animation twice
    private boolean mIsRunning;
    // Used to prevent holding ImageView when it should be dead.
    private SoftReference<ImageView> mImageRef;
    // Handler to communication with UIThread
    private Handler mHandler;
    /**
     * strong reference for cache
     */
    private static LifoCache<Integer, Bitmap> sharedCache = new LifoCache<Integer, Bitmap>(4) {
        /**
         * avoid permanent bitmap cache
         */
        SparseArray<WeakReference<Bitmap>> refs = new SparseArray<>(4);

        @Override
        protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
            refs.put(key, new WeakReference<Bitmap>(oldValue));
        }

        @Override
        protected Bitmap create(Integer key) {
            WeakReference<Bitmap> reference = refs.get(key);
            return reference != null ? reference.get() : super.create(key);
        }
    };
    private static int maxCacheSize;

    Drawable mCurrDrawable;

    @Override
    public void draw(Canvas canvas) {

    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        if (who == mCurrDrawable && getCallback() != null) {
            getCallback().invalidateDrawable(this);
        }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        if (who == mCurrDrawable && getCallback() != null) {
            getCallback().scheduleDrawable(this, what, when);
        }
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        if (who == mCurrDrawable && getCallback() != null) {
            getCallback().unscheduleDrawable(this, what);
        }
    }

    @Override
    public void run() {
        nextFrame(false);
    }

    public MockFrameAnimation(int maxCachedBitmapCount) {
        init();
        Log.i("LifoCache", "max cache count = " + maxCachedBitmapCount);
        /*
         * should be at least 2 for bitmap decode reuse
         */
        maxCacheSize = maxCachedBitmapCount < 2 ? 2 : maxCachedBitmapCount;
        sharedCache.resize(maxCacheSize);
    }

    public void init() {
        mFrames = new ArrayList<AnimationFrame>();
        mHandler = new Handler();
        mShouldRun = false;
        mIsRunning = false;
        mIndex = -1;
    }

    public MockFrameAnimation into(ImageView imageView) {
        mImageRef = new SoftReference<ImageView>(imageView);
        setCallback(imageView);
        return this;
    }

    /**
     * add a frame of animation
     *
     * @param resId    resource id of drawable
     * @param interval milliseconds
     */
    public void addFrame(int resId, int interval) {
        mFrames.add(new AnimationFrame(resId, interval));
    }

    /**
     * add all frames of animation
     *
     * @param resIds   resource id of drawable
     * @param interval milliseconds
     */
    public MockFrameAnimation with(int[] resIds, int interval) {
        removeAllFrames();
        for (int resId : resIds) {
            mFrames.add(new AnimationFrame(resId, interval));
        }
        return this;
    }

    /**
     * clear all frames
     */
    public void removeAllFrames() {
        mFrames.clear();
    }

    private AnimationFrame getNext() {
        mIndex++;
        if (mIndex >= mFrames.size())
            mIndex = 0;
        return mFrames.get(mIndex);
    }

    private void nextFrame(boolean unschedule) {
        int nextFrame = mIndex + 1;
        final int numFrames = mFrames.size();
        boolean oneShot = false;
        final boolean isLastFrame = oneShot && nextFrame >= (numFrames - 1);
        // loop
        if (!oneShot && nextFrame >= numFrames) {
            nextFrame = 0;
        }
        setFrame(nextFrame, unschedule, !isLastFrame);
    }

    /**
     * Starts the animation
     */
    public synchronized void start() {
        mShouldRun = true;
        if (!mIsRunning) {
            setFrame(0, false, true);
        }
    }

    /**
     * Stops the animation
     */
    public synchronized void stop() {
        mShouldRun = false;
        sharedCache.evictAll();
        if (mIsRunning) {
            unscheduleSelf(this);
        }
    }

    private void setFrame(int frame, boolean unschedule, boolean animate) {
        Log.i("Animation", "setFrame:" + frame + ", unschedule:" + unschedule + ", animate:" + animate);
        if (frame >= mFrames.size()) {
            return;
        }
        mIndex = frame;
        mShouldRun = animate;
        selectFrame(frame);
        if (unschedule || animate) {
            unscheduleSelf(this);
        }
        if (animate) {
            mIndex = frame;
            mIsRunning = true;
            scheduleSelf(this, SystemClock.uptimeMillis() + mFrames.get(frame).getDuration());
        }
    }

    private void selectFrame(int idx) {
        ImageView imageView = mImageRef.get();
        AnimationFrame frame = mFrames.get(idx);
        GetImageDrawableTask task = new GetImageDrawableTask(imageView);
        task.execute(frame.getResourceId());
    }

    private Runnable mAnimationLoop = new Runnable() {

        private AnimationFrame pausedFrame;

        @Override
        public void run() {
            ImageView imageView = mImageRef.get();
            if (!mShouldRun || imageView == null) {
                mIsRunning = false;
                return;
            }
            mIsRunning = true;
            if (imageView.isShown()) {
                AnimationFrame frame = pausedFrame == null ? getNext() : pausedFrame;
                GetImageDrawableTask task = new GetImageDrawableTask(imageView);
                task.execute(frame.getResourceId());
                mHandler.postDelayed(this, frame.getDuration());
                pausedFrame = null;
            } else {
                if (pausedFrame == null) {
                    pausedFrame = getNext();
                    sharedCache.evictAll();
                }
                mHandler.postDelayed(this, pausedFrame.getDuration());
            }
        }
    };

    private class GetImageDrawableTask extends AsyncTask<Integer, Void, Bitmap> {

        private ImageView mImageView;
        private Resources mResource;
        private int mExpectWidth;
        private int mExpectHeight;

        public GetImageDrawableTask(ImageView imageView) {
            mImageView = imageView;
            mResource = mImageView.getResources();
            mExpectWidth = mImageView.getWidth();
            mExpectHeight = mImageView.getHeight();
        }

        @SuppressLint("NewApi")
        @Override
        protected Bitmap doInBackground(Integer... params) {
            int resId = params[0];
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = true;
            Bitmap bitmap = sharedCache.get(resId);
            if (bitmap == null) {
                sampleSize(resId, options);
                if (sharedCache.size() >= maxCacheSize) {
                    Integer lastKey = sharedCache.lastKey(2);
                    if (lastKey != null) {
                        Bitmap b = sharedCache.get(lastKey);
                        if (b != null) {
                            options.inBitmap = b;
                        }
                        sharedCache.remove(lastKey);
                    }
                }
                try {
                    bitmap = BitmapFactory.decodeResource(mResource, resId, options);
                    sharedCache.put(resId, bitmap);
                } catch (OutOfMemoryError e) {
                    Log.w("LifoCache", "decode bitmap failed, maybe too large", e);
                    // not instant gc
                    evictAllCache();
                }
            }
            return bitmap;
        }

        private void sampleSize(int resId, BitmapFactory.Options options) {
            if (mExpectWidth > 0 && mExpectHeight > 0) {
                // trigger inSampleSize
                options.inJustDecodeBounds = true;
                try {
                    BitmapFactory.decodeResource(mResource, resId, options);
                } catch (OutOfMemoryError e) {
                    Log.w("LifoCache", "decode bitmap failed, maybe too large", e);
                    // not instant gc
                    evictAllCache();
                }
                int w = options.outWidth;
                int h = options.outHeight;
                int inSampleSize = Math.max(w / mExpectWidth, h / mExpectHeight);
                // reset to normal size
                if (inSampleSize <= 0) {
                    inSampleSize = 1;
                }
                Log.i("LifoCache", "inSampleSize=" + inSampleSize + ", width=" + mExpectWidth + ", " +
                    "height=" + mExpectHeight + ", raw width=" + w + ", raw h=" + h);
                options.inSampleSize = inSampleSize;
                options.inJustDecodeBounds = false;
            }
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            super.onPostExecute(result);
            if (result != null) {
                mImageView.setImageBitmap(result);
            }
        }
    }

    /**
     * Clear all cache and notify gc
     */
    public void evictAllCache() {
        sharedCache.evictAll();
        System.gc();
    }

}