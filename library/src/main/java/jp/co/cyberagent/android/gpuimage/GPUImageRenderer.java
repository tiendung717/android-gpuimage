/*
 * Copyright (C) 2018 CyberAgent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.cyberagent.android.gpuimage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;
import java.util.Queue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.util.OpenGlUtils;
import jp.co.cyberagent.android.gpuimage.util.Rotation;
import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil;

import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_NO_ROTATION;

public class GPUImageRenderer implements GLSurfaceView.Renderer, GLTextureView.Renderer, PreviewCallback {
    private static final int NO_IMAGE = -1;
    public static final float CUBE[] = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };

    private GPUImageFilter filter;

    private int glTextureId = NO_IMAGE;
    private SurfaceTexture surfaceTexture = null;
    private final FloatBuffer glCubeBuffer;
    private final FloatBuffer glTextureBuffer;
    private IntBuffer glRgbBuffer;

    private int outputWidth;
    private int outputHeight;
    private int imageWidth;
    private int imageHeight;
    private int addedPadding;

    private final Queue<Runnable> runOnDraw;
    private final Queue<Runnable> runOnDrawEnd;
    private Rotation rotation;
    private boolean flipHorizontal;
    private boolean flipVertical;
    private GPUImage.ScaleType scaleType = GPUImage.ScaleType.CENTER_CROP;

    private float backgroundRed = 0;
    private float backgroundGreen = 0;
    private float backgroundBlue = 0;

    public GPUImageRenderer(final GPUImageFilter filter) {
        this.filter = filter;
        runOnDraw = new LinkedList<>();
        runOnDrawEnd = new LinkedList<>();

        glCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        glCubeBuffer.put(CUBE).position(0);

        glTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        setRotation(Rotation.NORMAL, false, false);
    }

    @Override
    public void onSurfaceCreated(final GL10 unused, final EGLConfig config) {
        GLES20.glClearColor(backgroundRed, backgroundGreen, backgroundBlue, 1);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        filter.ifNeedInit();

        int textures[] = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        glTextureId = textures[0];
    }

    @Override
    public void onSurfaceChanged(final GL10 gl, final int width, final int height) {
        outputWidth = width;
        outputHeight = height;
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(filter.getProgram());
        filter.onOutputSizeChanged(width, height);
//        adjustImageScaling();
//        synchronized (surfaceChangedWaiter) {
//            surfaceChangedWaiter.notifyAll();
//        }
    }

    @Override
    public void onDrawFrame(final GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        runAll(runOnDraw);
        filter.onDraw(glTextureId, glCubeBuffer, glTextureBuffer);
        runAll(runOnDrawEnd);
//        if (surfaceTexture != null) {
//            surfaceTexture.updateTexImage();
//        }
    }

    /**
     * Sets the background color
     *
     * @param red   red color value
     * @param green green color value
     * @param blue  red color value
     */
    public void setBackgroundColor(float red, float green, float blue) {
        backgroundRed = red;
        backgroundGreen = green;
        backgroundBlue = blue;
    }

    private void runAll(Queue<Runnable> queue) {
        synchronized (queue) {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera camera) {
        final Size previewSize = camera.getParameters().getPreviewSize();
        onPreviewFrame(data, previewSize.width, previewSize.height);
    }

    public void onPreviewFrame(final byte[] data, final int width, final int height) {
        if (glRgbBuffer == null) {
            glRgbBuffer = IntBuffer.allocate(width * height);
        }
        if (runOnDraw.isEmpty()) {
            runOnDraw(new Runnable() {
                @Override
                public void run() {
                    GPUImageNativeLibrary.YUVtoRBGA(data, width, height, glRgbBuffer.array());
                    glTextureId = OpenGlUtils.loadTexture(glRgbBuffer, width, height, glTextureId);

                    if (imageWidth != width) {
                        imageWidth = width;
                        imageHeight = height;
//                        adjustImageScaling();
                    }
                }
            });
        }
    }

    public void setUpSurfaceTexture(final Camera camera) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                surfaceTexture = new SurfaceTexture(textures[0]);
                try {
                    camera.setPreviewTexture(surfaceTexture);
                    camera.setPreviewCallback(GPUImageRenderer.this);
                    camera.startPreview();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void setFilter(final GPUImageFilter filter) {
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                final GPUImageFilter oldFilter = GPUImageRenderer.this.filter;
                GPUImageRenderer.this.filter = filter;
                if (oldFilter != null) {
                    oldFilter.destroy();
                }
                GPUImageRenderer.this.filter.ifNeedInit();
                GLES20.glUseProgram(GPUImageRenderer.this.filter.getProgram());
                GPUImageRenderer.this.filter.onOutputSizeChanged(outputWidth, outputHeight);
            }
        });
    }

    public void deleteImage() {
//        runOnDraw(new Runnable() {
//
//            @Override
//            public void run() {
//                GLES20.glDeleteTextures(1, new int[]{
//                        glTextureId
//                }, 0);
//                glTextureId = NO_IMAGE;
//            }
//        });
    }

    public void setImageBitmap(final Bitmap bitmap) {
        setImageBitmap(bitmap, true);
    }

    public void setImageBitmap(final Bitmap bitmap, final boolean recycle) {
        if (bitmap == null) {
            return;
        }

        runOnDraw(new Runnable() {

            @Override
            public void run() {
                Bitmap resizedBitmap = null;
                if (bitmap.getWidth() % 2 == 1) {
                    resizedBitmap = Bitmap.createBitmap(bitmap.getWidth() + 1, bitmap.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    resizedBitmap.setDensity(bitmap.getDensity());
                    Canvas can = new Canvas(resizedBitmap);
                    can.drawARGB(0x00, 0x00, 0x00, 0x00);
                    can.drawBitmap(bitmap, 0, 0, null);
                    addedPadding = 1;
                } else {
                    addedPadding = 0;
                }

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0,  resizedBitmap != null ? resizedBitmap : bitmap, 0);
                GLES20.glTexParameteri(
                        GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
                );
                GLES20.glTexParameteri(
                        GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
                );
                GLES20.glTexParameteri(
                        GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                        GLES20.GL_CLAMP_TO_EDGE
                );
                GLES20.glTexParameteri(
                        GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                        GLES20.GL_CLAMP_TO_EDGE
                );

                if (resizedBitmap != null) {
                    resizedBitmap.recycle();
                }
                imageWidth = bitmap.getWidth();
                imageHeight = bitmap.getHeight();
            }
        });
    }

    public void setScaleType(GPUImage.ScaleType scaleType) {
        this.scaleType = scaleType;
    }

    protected int getFrameWidth() {
        return outputWidth;
    }

    protected int getFrameHeight() {
        return outputHeight;
    }

    void updateTransform(){
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                float[] textureBuffer =
                        TextureRotationUtil.getRotation(rotation, flipHorizontal, flipVertical);
                glTextureBuffer.clear();
                glTextureBuffer.put(textureBuffer).position(0);
            }
        });
    }

    private float addDistance(float coordinate, float distance) {
        return coordinate == 0.0f ? distance : 1 - distance;
    }

    public void setRotationCamera(final Rotation rotation, final boolean flipHorizontal,
                                  final boolean flipVertical) {
        setRotation(rotation, flipVertical, flipHorizontal);
    }

    public void setRotation(final Rotation rotation) {
        this.rotation = rotation;
        updateTransform();
    }

    public void flipVertical(boolean enabled) {
        flipVertical = enabled;
        updateTransform();
    }

    public void flipHorizontal(boolean enabled) {
        flipHorizontal = enabled;
        updateTransform();
    }

    public void setRotation(final Rotation rotation,
                            final boolean flipHorizontal, final boolean flipVertical) {
        this.rotation = rotation;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        updateTransform();
    }

    public Rotation getRotation() {
        return rotation;
    }

    public GPUImage.ScaleType getScaleType() {
        return scaleType;
    }

    public boolean isFlippedHorizontally() {
        return flipHorizontal;
    }

    public boolean isFlippedVertically() {
        return flipVertical;
    }

    protected void runOnDraw(final Runnable runnable) {
        synchronized (runOnDraw) {
            runOnDraw.add(runnable);
        }
    }

    protected void runOnDrawEnd(final Runnable runnable) {
        synchronized (runOnDrawEnd) {
            runOnDrawEnd.add(runnable);
        }
    }
}
