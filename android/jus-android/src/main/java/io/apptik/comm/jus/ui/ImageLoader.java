/**
 * Copyright (C) 2013 The Android Open Source Project
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apptik.comm.jus.ui;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import java.util.HashMap;
import java.util.LinkedList;

import io.apptik.comm.jus.RequestListener.ErrorListener;
import io.apptik.comm.jus.RequestListener.ResponseListener;
import io.apptik.comm.jus.Request;
import io.apptik.comm.jus.RequestQueue;
import io.apptik.comm.jus.error.JusError;
import io.apptik.comm.jus.request.ImageRequest;
import io.apptik.comm.jus.util.BitmapPool;
import io.apptik.comm.jus.util.DefaultBitmapLruCache;

/**
 * Helper that handles loading and caching images from remote URLs.
 *
 * The simple way to use this class is to call {@link ImageLoader#get(String, ImageListener)}
 * and to pass in the default image listener provided by
 * {@link ImageLoader#getImageListener(ImageView, int, int)}. Note that all function calls to
 * this class must be made from the main thead, and all responses will be delivered to the main
 * threadId as well.
 */
public class ImageLoader {
    /** RequestQueue for dispatching ImageRequests onto. */
    private final RequestQueue requestQueue;

    /** Amount of time to wait after first response arrives before delivering all responses. */
    private int mBatchResponseDelayMs = 100;

    /** The cache implementation to be used as an L1 cache before calling into jus. */
    private final ImageCache imageCache;

    private final BitmapPool bitmapPool;

    /**
     * HashMap of Cache keys -> BatchedImageRequest used to track in-flight requests so
     * that we can coalesce multiple requests to the same URL into a single network request.
     */
    private final HashMap<String, BatchedImageRequest> mInFlightRequests =
            new HashMap<String, BatchedImageRequest>();

    /** HashMap of the currently pending responses (waiting to be delivered). */
    private final HashMap<String, BatchedImageRequest> mBatchedResponses =
            new HashMap<String, BatchedImageRequest>();

    /** Handler to the main threadId. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Runnable for in-flight response delivery. */
    private Runnable mRunnable;

    /**
     * Resource ID of the image to be used as a placeholder until network image using this loader
     * is loaded.
     */
    private int mDefaultImageId;

    /**
     * Resource ID of the image to be used if the network response fails using this loader.
     */
    private int mErrorImageId;

    /**
     * Simple cache adapter interface. If provided to the ImageLoader, it
     * will be used as an L1 cache before dispatch to Jus. Implementations
     * must not block. Implementation with an LruCache is recommended.
     */
    public interface ImageCache {
        Bitmap getBitmap(String url);

        void putBitmap(String url, Bitmap bitmap);
    }

    /**
     * Constructs a new ImageLoader.
     */
    public ImageLoader(RequestQueue queue) {
        this.requestQueue = queue;
        DefaultBitmapLruCache defaultBitmapLruCache =  new DefaultBitmapLruCache();
        this.imageCache = defaultBitmapLruCache;
        this.bitmapPool = defaultBitmapLruCache;
    }

    /**
     * Constructs a new ImageLoader.
     * @param queue The RequestQueue to use for making image requests.
     * @param imageCache The cache to use as an L1 cache.
     */
    public ImageLoader(RequestQueue queue, ImageCache imageCache, BitmapPool bitmapPool) {
        this.requestQueue = queue;
        this.imageCache = imageCache;
        this.bitmapPool = bitmapPool;
    }

    /**
     * Sets the default image resource ID to be used for this view until the attempt to load it
     * completes.
     */
    public ImageLoader setDefaultImageResId(int defaultImage) {
        mDefaultImageId = defaultImage;
        return this;
    }

    /**
     * Sets the error image resource ID to be used for this view in the event that the image
     * requested fails to load.
     */
    public ImageLoader setErrorImageResId(int errorImage) {
        mErrorImageId = errorImage;
        return this;
    }

    public int getErrorImageId() {
        return mErrorImageId;
    }

    public int getDefaultImageId() {
        return mDefaultImageId;
    }

    /**
     * Interface for the response handlers on image requests.
     *
     * The call flow is this:
     * 1. Upon being  attached to a request, onResponse(response, true) will
     * be invoked to reflect any cached data that was already available. If the
     * data was available, response.getBitmap() will be non-null.
     *
     * 2. After a network response returns, only one of the following cases will happen:
     *   - onResponse(response, false) will be called if the image was loaded.
     *   or
     *   - onError will be called if there was an error loading the image.
     */
    public interface ImageListener extends ErrorListener {
        /**
         * Listens for non-error changes to the loading of the image request.
         *
         * @param response Holds all information pertaining to the request, as well
         * as the bitmap (if it is loaded).
         * @param isImmediate True if this was called during ImageLoader.get() variants.
         * This can be used to differentiate between a cached image loading and a network
         * image loading in order to, for example, run an animation to fade in network loaded
         * images.
         */
        void onResponse(ImageContainer response, boolean isImmediate);
    }

    /**
     * Checks if the item is available in the cache.
     * @param requestUrl The url of the remote image
     * @param maxWidth The maximum width of the returned image.
     * @param maxHeight The maximum height of the returned image.
     * @return True if the item exists in cache, false otherwise.
     */
    public boolean isCached(String requestUrl, int maxWidth, int maxHeight) {
        return isCached(requestUrl, maxWidth, maxHeight, ScaleType.CENTER_INSIDE);
    }

    /**
     * Checks if the item is available in the cache.
     *
     * @param requestUrl The url of the remote image
     * @param maxWidth   The maximum width of the returned image.
     * @param maxHeight  The maximum height of the returned image.
     * @param scaleType  The scaleType of the imageView.
     * @return True if the item exists in cache, false otherwise.
     */
    public boolean isCached(String requestUrl, int maxWidth, int maxHeight, ScaleType scaleType) {
        throwIfNotOnMainThread();

        String cacheKey = getCacheKey(requestUrl, maxWidth, maxHeight, scaleType);
        return imageCache.getBitmap(cacheKey) != null;
    }

    /**
     * Returns an ImageContainer for the requested URL.
     *
     * The ImageContainer will contain either the specified default bitmap or the loaded bitmap.
     * If the default was returned, the {@link ImageLoader} will be invoked when the
     * request is fulfilled.
     *
     * @param requestUrl The URL of the image to be loaded.
     */
    public ImageContainer get(String requestUrl, final ImageListener listener) {
        return get(requestUrl, listener, 0, 0);
    }

    /**
     * Equivalent to calling {@link #get(String, ImageListener, int, int, ScaleType)} with
     * {@code Scaletype == ScaleType.CENTER_INSIDE}.
     */
    public ImageContainer get(String requestUrl, ImageListener imageListener,
                              int maxWidth, int maxHeight) {
        return get(requestUrl, imageListener, maxWidth, maxHeight, ScaleType.CENTER_INSIDE);
    }

    /**
     * Issues a bitmap request with the given URL if that image is not available
     * in the cache, and returns a bitmap container that contains all of the data
     * relating to the request (as well as the default image if the requested
     * image is not available).
     * @param requestUrl The url of the remote image
     * @param imageListener The listener to call when the remote image is loaded
     * @param maxWidth The maximum width of the returned image.
     * @param maxHeight The maximum height of the returned image.
     * @param scaleType The ImageViews ScaleType used to calculate the needed image size.
     * @return A container object that contains all of the properties of the request, as well as
     *     the currently available image (default if remote is not loaded).
     */
    public ImageContainer get(String requestUrl, ImageListener imageListener,
                              int maxWidth, int maxHeight, ScaleType scaleType) {
        // only fulfill requests that were initiated from the main threadId.
        throwIfNotOnMainThread();

        final String cacheKey = getCacheKey(requestUrl, maxWidth, maxHeight, scaleType);

        // Try to look up the request in the cache of remote images.
        Bitmap cachedBitmap = imageCache.getBitmap(cacheKey);
        if (cachedBitmap != null) {
            // Return the cached bitmap.
            ImageContainer container = new ImageContainer(cachedBitmap, requestUrl, null, null);
            imageListener.onResponse(container, true);
            return container;
        }

        // The bitmap did not exist in the cache, fetch it!
        ImageContainer imageContainer =
                new ImageContainer(null, requestUrl, cacheKey, imageListener);

        // Update the caller to let them know that they should use the default bitmap.
        // Note this can be done from the ImageView but then it would be invalidated twice in case
        // of a cache
        imageListener.onResponse(imageContainer, true);

        // Check to see if a request is already in-flight.
        BatchedImageRequest request = mInFlightRequests.get(cacheKey);
        if (request != null) {
            // If it is, add this request to the list of listeners.
            request.addContainer(imageContainer);
            return imageContainer;
        }

        // The request is not already in flight. Send the new request to the network and
        // track it.
        ImageRequest newRequest = makeImageRequest(requestUrl, maxWidth, maxHeight, scaleType,
                cacheKey);

        requestQueue.add(newRequest);
        mInFlightRequests.put(cacheKey,
                new BatchedImageRequest(newRequest, imageContainer));
        return imageContainer;
    }

    protected ImageRequest makeImageRequest(String requestUrl, int maxWidth, int maxHeight,
                                            ScaleType scaleType, final String cacheKey) {
        return (ImageRequest) new ImageRequest(requestUrl, maxWidth, maxHeight, scaleType, Config.RGB_565)
                .setBitmapPool(bitmapPool)
                .addResponseListener(new ResponseListener<Bitmap>() {
                    @Override
                    public void onResponse(Bitmap response) {
                        onGetImageSuccess(cacheKey, response);
                    }
                })
                .addErrorListener(new ErrorListener() {
                    @Override
                    public void onError(JusError error) {
                        onGetImageError(cacheKey, error);
                    }
                })
                ;
    }

    /**
     * Sets the amount of time to wait after the first response arrives before delivering all
     * responses. Batching can be disabled entirely by passing in 0.
     * @param newBatchedResponseDelayMs The time in milliseconds to wait.
     */
    public void setBatchedResponseDelay(int newBatchedResponseDelayMs) {
        mBatchResponseDelayMs = newBatchedResponseDelayMs;
    }

    /**
     * Handler for when an image was successfully loaded.
     * @param cacheKey The cache key that is associated with the image request.
     * @param response The bitmap that was returned from the network.
     */
    protected void onGetImageSuccess(String cacheKey, Bitmap response) {
        // cache the image that was fetched.
        imageCache.putBitmap(cacheKey, response);

        // remove the request from the list of in-flight requests.
        BatchedImageRequest request = mInFlightRequests.remove(cacheKey);

        if (request != null) {
            // Update the response bitmap.
            request.mResponseBitmap = response;

            // Send the batched response
            batchResponse(cacheKey, request);
        }
    }

    /**
     * Handler for when an image failed to load.
     * @param cacheKey The cache key that is associated with the image request.
     */
    protected void onGetImageError(String cacheKey, JusError error) {
        // Notify the requesters that something failed via a null result.
        // Remove this request from the list of in-flight requests.
        BatchedImageRequest request = mInFlightRequests.remove(cacheKey);

        if (request != null) {
            // Set the error for this request
            request.setError(error);

            // Send the batched response
            batchResponse(cacheKey, request);
        }
    }

    /**
     * Container object for all of the data surrounding an image request.
     */
    public class ImageContainer {
        /**
         * The most relevant bitmap for the container. If the image was in cache, the
         * Holder to use for the final bitmap (the one that pairs to the requested URL).
         */
        private Bitmap mBitmap;

        private final ImageListener mListener;

        /** The cache key that was associated with the request */
        private final String mCacheKey;

        /** The request URL that was specified */
        private final String mRequestUrl;

        /**
         * Constructs a BitmapContainer object.
         * @param bitmap The final bitmap (if it exists).
         * @param requestUrl The requested URL for this container.
         * @param cacheKey The cache key that identifies the requested URL for this container.
         */
        public ImageContainer(Bitmap bitmap, String requestUrl,
                              String cacheKey, ImageListener listener) {
            mBitmap = bitmap;
            mRequestUrl = requestUrl;
            mCacheKey = cacheKey;
            mListener = listener;
        }

        /**
         * Releases interest in the in-flight request (and cancels it if no one else is listening).
         */
        public void cancelRequest() {
            if (mListener == null) {
                return;
            }

            BatchedImageRequest request = mInFlightRequests.get(mCacheKey);
            if (request != null) {
                boolean canceled = request.removeContainerAndCancelIfNecessary(this);
                if (canceled) {
                    mInFlightRequests.remove(mCacheKey);
                }
            } else {
                // check to see if it is already batched for delivery.
                request = mBatchedResponses.get(mCacheKey);
                if (request != null) {
                    request.removeContainerAndCancelIfNecessary(this);
                    if (request.mContainers.size() == 0) {
                        mBatchedResponses.remove(mCacheKey);
                    }
                }
            }
        }

        /**
         * Returns the bitmap associated with the request URL if it has been loaded, null otherwise.
         */
        public Bitmap getBitmap() {
            return mBitmap;
        }

        /**
         * Returns the requested URL for this container.
         */
        public String getRequestUrl() {
            return mRequestUrl;
        }
    }

    /**
     * Wrapper class used to map a Request to the set of active ImageContainer objects that are
     * interested in its results.
     */
    private class BatchedImageRequest {
        /** The request being tracked */
        private final Request<?> mRequest;

        /** The result of the request being tracked by this item */
        private Bitmap mResponseBitmap;

        /** Error if one occurred for this response */
        private JusError mError;

        /** List of all of the active ImageContainers that are interested in the request */
        private final LinkedList<ImageContainer> mContainers = new LinkedList<ImageContainer>();

        /**
         * Constructs a new BatchedImageRequest object
         * @param request The request being tracked
         * @param container The ImageContainer of the person who initiated the request.
         */
        public BatchedImageRequest(Request<?> request, ImageContainer container) {
            mRequest = request;
            mContainers.add(container);
        }

        /**
         * Set the error for this response
         */
        public void setError(JusError error) {
            mError = error;
        }

        /**
         * Get the error for this response
         */
        public JusError getError() {
            return mError;
        }

        /**
         * Adds another ImageContainer to the list of those interested in the results of
         * the request.
         */
        public void addContainer(ImageContainer container) {
            mContainers.add(container);
        }

        /**
         * Detatches the bitmap container from the request and cancels the request if no one is
         * left listening.
         * @param container The container to remove from the list
         * @return True if the request was canceled, false otherwise.
         */
        public boolean removeContainerAndCancelIfNecessary(ImageContainer container) {
            mContainers.remove(container);
            if (mContainers.size() == 0) {
                mRequest.cancel();
                return true;
            }
            return false;
        }
    }

    /**
     * Starts the runnable for batched delivery of responses if it is not already started.
     * @param cacheKey The cacheKey of the response being delivered.
     * @param request The BatchedImageRequest to be delivered.
     */
    private void batchResponse(String cacheKey, BatchedImageRequest request) {
        mBatchedResponses.put(cacheKey, request);
        // If we don't already have a batch delivery runnable in flight, make a new one.
        // Note that this will be used to deliver responses to all callers in mBatchedResponses.
        if (mRunnable == null) {
            mRunnable = new Runnable() {
                @Override
                public void run() {
                    for (BatchedImageRequest bir : mBatchedResponses.values()) {
                        for (ImageContainer container : bir.mContainers) {
                            // If one of the callers in the batched request canceled the request
                            // after the response was received but before it was delivered,
                            // skip them.
                            if (container.mListener == null) {
                                continue;
                            }
                            if (bir.getError() == null) {
                                container.mBitmap = bir.mResponseBitmap;
                                container.mListener.onResponse(container, false);
                            } else {
                                container.mListener.onError(bir.getError());
                            }
                        }
                    }
                    mBatchedResponses.clear();
                    mRunnable = null;
                }

            };
            // Post the runnable.
            mHandler.postDelayed(mRunnable, mBatchResponseDelayMs);
        }
    }

    private void throwIfNotOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("ImageLoader must be invoked from the main threadId.");
        }
    }

    /**
     * Creates a cache key for use with the L1 cache.
     * @param url The URL of the request.
     * @param maxWidth The max-width of the output.
     * @param maxHeight The max-height of the output.
     * @param scaleType The scaleType of the imageView.
     */
    private static String getCacheKey(String url, int maxWidth, int maxHeight, ScaleType scaleType) {
        return "#W" + maxWidth + "#H" + maxHeight + "#S" + scaleType.ordinal() + url;
    }
}
