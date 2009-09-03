/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewRoot;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class WallpaperManager {
    private static String TAG = "WallpaperManager";
    private static boolean DEBUG = false;

    /**
     * Launch an activity for the user to pick the current global live
     * wallpaper.
     * @hide
     */
    public static final String ACTION_LIVE_WALLPAPER_CHOOSER
            = "android.service.wallpaper.LIVE_WALLPAPER_CHOOSER";
    
    private final Context mContext;
    
    static class Globals extends IWallpaperManagerCallback.Stub {
        private IWallpaperManager mService;
        private Bitmap mWallpaper;
        
        private static final int MSG_CLEAR_WALLPAPER = 1;
        
        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_CLEAR_WALLPAPER:
                        synchronized (this) {
                            mWallpaper = null;
                        }
                        break;
                }
            }
        };
        
        Globals() {
            IBinder b = ServiceManager.getService(Context.WALLPAPER_SERVICE);
            mService = IWallpaperManager.Stub.asInterface(b);
        }
        
        public void onWallpaperChanged() {
            /* The wallpaper has changed but we shouldn't eagerly load the
             * wallpaper as that would be inefficient. Reset the cached wallpaper
             * to null so if the user requests the wallpaper again then we'll
             * fetch it.
             */
            mHandler.sendEmptyMessage(MSG_CLEAR_WALLPAPER);
        }
        
        public Bitmap peekWallpaperBitmap(Context context) {
            synchronized (this) {
                if (mWallpaper != null) {
                    return mWallpaper;
                }
                mWallpaper = getCurrentWallpaperLocked(context);
                return mWallpaper;
            }
        }
        
        private Bitmap getCurrentWallpaperLocked(Context context) {
            try {
                Bundle params = new Bundle();
                ParcelFileDescriptor fd = mService.getWallpaper(this, params);
                if (fd != null) {
                    int width = params.getInt("width", 0);
                    int height = params.getInt("height", 0);
                    
                    if (width <= 0 || height <= 0) {
                        // Degenerate case: no size requested, just load
                        // bitmap as-is.
                        Bitmap bm = BitmapFactory.decodeFileDescriptor(
                                fd.getFileDescriptor(), null, null);
                        try {
                            fd.close();
                        } catch (IOException e) {
                        }
                        if (bm != null) {
                            bm.setDensity(DisplayMetrics.DENSITY_DEVICE);
                        }
                        return bm;
                    }
                    
                    // Load the bitmap with full color depth, to preserve
                    // quality for later processing.
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inDither = false;
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap bm = BitmapFactory.decodeFileDescriptor(
                            fd.getFileDescriptor(), null, options);
                    try {
                        fd.close();
                    } catch (IOException e) {
                    }
                    if (bm == null) {
                        return bm;
                    }
                    bm.setDensity(DisplayMetrics.DENSITY_DEVICE);
                    
                    // This is the final bitmap we want to return.
                    Bitmap newbm = Bitmap.createBitmap(width, height,
                            bm.getConfig());
                    newbm.setDensity(DisplayMetrics.DENSITY_DEVICE);
                    Canvas c = new Canvas(newbm);
                    c.setDensity(DisplayMetrics.DENSITY_DEVICE);
                    Rect targetRect = new Rect();
                    targetRect.left = targetRect.top = 0;
                    targetRect.right = bm.getWidth();
                    targetRect.bottom = bm.getHeight();
                    
                    int deltaw = width - targetRect.right;
                    int deltah = height - targetRect.bottom;
                    
                    if (deltaw > 0 || deltah > 0) {
                        // We need to scale up so it covers the entire
                        // area.
                        float scale = 1.0f;
                        if (deltaw > deltah) {
                            scale = width / (float)targetRect.right;
                        } else {
                            scale = height / (float)targetRect.bottom;
                        }
                        targetRect.right = (int)(targetRect.right*scale);
                        targetRect.bottom = (int)(targetRect.bottom*scale);
                        deltaw = width - targetRect.right;
                        deltah = height - targetRect.bottom;
                    }
                    
                    targetRect.offset(deltaw/2, deltah/2);
                    Paint paint = new Paint();
                    paint.setFilterBitmap(true);
                    paint.setDither(true);
                    c.drawBitmap(bm, null, targetRect, paint);
                    
                    bm.recycle();
                    return newbm;
                }
            } catch (RemoteException e) {
            }
            return null;
        }
    }
    
    private static Object mSync = new Object();
    private static Globals sGlobals;

    static void initGlobals(Looper looper) {
        synchronized (mSync) {
            if (sGlobals == null) {
                sGlobals = new Globals();
            }
        }
    }
    
    /*package*/ WallpaperManager(Context context, Handler handler) {
        mContext = context;
        initGlobals(context.getMainLooper());
    }

    /**
     * Retrieve a WallpaperManager associated with the given Context.
     */
    public static WallpaperManager getInstance(Context context) {
        return (WallpaperManager)context.getSystemService(
                Context.WALLPAPER_SERVICE);
    }
    
    /** @hide */
    public IWallpaperManager getIWallpaperManager() {
        return sGlobals.mService;
    }
    
    /**
     * Like {@link #peekDrawable}, but always returns a valid Drawable.  If
     * no wallpaper is set, the system default wallpaper is returned.
     *
     * @return Returns a Drawable object that will draw the wallpaper.
     */
    public Drawable getDrawable() {
        Drawable dr = peekDrawable();
        return dr != null ? dr : Resources.getSystem().getDrawable(
                com.android.internal.R.drawable.default_wallpaper);
    }

    /**
     * Retrieve the current system wallpaper.  This is returned as an
     * abstract Drawable that you can install in a View to display whatever
     * wallpaper the user has currently set.  If there is no wallpaper set,
     * a null pointer is returned.
     *
     * @return Returns a Drawable object that will draw the wallpaper or a
     * null pointer if these is none.
     */
    public Drawable peekDrawable() {
        Bitmap bm = sGlobals.peekWallpaperBitmap(mContext);
        return bm != null ? new BitmapDrawable(mContext.getResources(), bm) : null;
    }

    /**
     * Change the current system wallpaper to the bitmap in the given resource.
     * The resource is opened as a raw data stream and copied into the
     * wallpaper; it must be a valid PNG or JPEG image.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * @param resid The bitmap to save.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void setResource(int resid) throws IOException {
        try {
            Resources resources = mContext.getResources();
            /* Set the wallpaper to the default values */
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(
                    "res:" + resources.getResourceName(resid));
            if (fd != null) {
                FileOutputStream fos = null;
                try {
                    fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    setWallpaper(resources.openRawResource(resid), fos);
                } finally {
                    if (fos != null) {
                        fos.close();
                    }
                }
            }
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Change the current system wallpaper to a bitmap.  The given bitmap is
     * converted to a PNG and stored as the wallpaper.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * @param bitmap The bitmap to save.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void setBitmap(Bitmap bitmap) throws IOException {
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null);
            if (fd == null) {
                return;
            }
            FileOutputStream fos = null;
            try {
                fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Change the current system wallpaper to a specific byte stream.  The
     * give InputStream is copied into persistent storage and will now be
     * used as the wallpaper.  Currently it must be either a JPEG or PNG
     * image.  On success, the intent {@link Intent#ACTION_WALLPAPER_CHANGED}
     * is broadcast.
     *
     * @param data A stream containing the raw data to install as a wallpaper.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void setStream(InputStream data) throws IOException {
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null);
            if (fd == null) {
                return;
            }
            FileOutputStream fos = null;
            try {
                fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                setWallpaper(data, fos);
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        } catch (RemoteException e) {
        }
    }

    private void setWallpaper(InputStream data, FileOutputStream fos)
            throws IOException {
        byte[] buffer = new byte[32768];
        int amt;
        while ((amt=data.read(buffer)) > 0) {
            fos.write(buffer, 0, amt);
        }
    }

    /**
     * Returns the desired minimum width for the wallpaper. Callers of
     * {@link #setBitmap(android.graphics.Bitmap)} or
     * {@link #setStream(java.io.InputStream)} should check this value
     * beforehand to make sure the supplied wallpaper respects the desired
     * minimum width.
     *
     * If the returned value is <= 0, the caller should use the width of
     * the default display instead.
     *
     * @return The desired minimum width for the wallpaper. This value should
     * be honored by applications that set the wallpaper but it is not
     * mandatory.
     */
    public int getDesiredMinimumWidth() {
        try {
            return sGlobals.mService.getWidthHint();
        } catch (RemoteException e) {
            // Shouldn't happen!
            return 0;
        }
    }

    /**
     * Returns the desired minimum height for the wallpaper. Callers of
     * {@link #setBitmap(android.graphics.Bitmap)} or
     * {@link #setStream(java.io.InputStream)} should check this value
     * beforehand to make sure the supplied wallpaper respects the desired
     * minimum height.
     *
     * If the returned value is <= 0, the caller should use the height of
     * the default display instead.
     *
     * @return The desired minimum height for the wallpaper. This value should
     * be honored by applications that set the wallpaper but it is not
     * mandatory.
     */
    public int getDesiredMinimumHeight() {
        try {
            return sGlobals.mService.getHeightHint();
        } catch (RemoteException e) {
            // Shouldn't happen!
            return 0;
        }
    }

    /**
     * For use only by the current home application, to specify the size of
     * wallpaper it would like to use.  This allows such applications to have
     * a virtual wallpaper that is larger than the physical screen, matching
     * the size of their workspace.
     * @param minimumWidth Desired minimum width
     * @param minimumHeight Desired minimum height
     */
    public void suggestDesiredDimensions(int minimumWidth, int minimumHeight) {
        try {
            sGlobals.mService.setDimensionHints(minimumWidth, minimumHeight);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Set the position of the current wallpaper within any larger space, when
     * that wallpaper is visible behind the given window.  The X and Y offsets
     * are floating point numbers ranging from 0 to 1, representing where the
     * wallpaper should be positioned within the screen space.  These only
     * make sense when the wallpaper is larger than the screen.
     * 
     * @param windowToken The window who these offsets should be associated
     * with, as returned by {@link android.view.View#getWindowVisibility()
     * View.getWindowToken()}.
     * @param xOffset The offset olong the X dimension, from 0 to 1.
     * @param yOffset The offset along the Y dimension, from 0 to 1.
     */
    public void setWallpaperOffsets(IBinder windowToken, float xOffset, float yOffset) {
        try {
            ViewRoot.getWindowSession(mContext.getMainLooper()).setWallpaperPosition(
                    windowToken, xOffset, yOffset);
        } catch (RemoteException e) {
            // Ignore.
        }
    }
    
    /**
     * Clear the offsets previously associated with this window through
     * {@link #setWallpaperOffsets(IBinder, float, float)}.  This reverts
     * the window to its default state, where it does not cause the wallpaper
     * to scroll from whatever its last offsets were.
     * 
     * @param windowToken The window who these offsets should be associated
     * with, as returned by {@link android.view.View#getWindowVisibility()
     * View.getWindowToken()}.
     */
    public void clearWallpaperOffsets(IBinder windowToken) {
        try {
            ViewRoot.getWindowSession(mContext.getMainLooper()).setWallpaperPosition(
                    windowToken, -1, -1);
        } catch (RemoteException e) {
            // Ignore.
        }
    }
    
    /**
     * Remove any currently set wallpaper, reverting to the system's default
     * wallpaper. On success, the intent {@link Intent#ACTION_WALLPAPER_CHANGED}
     * is broadcast.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void clear() throws IOException {
        setResource(com.android.internal.R.drawable.default_wallpaper);
    }
}
