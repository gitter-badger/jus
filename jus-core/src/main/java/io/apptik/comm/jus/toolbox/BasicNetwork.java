/*
 * Copyright (C) 2014 Kalin Maldzhanski
 * Copyright (C) 2011 The Android Open Source Project
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

package io.apptik.comm.jus.toolbox;

import android.os.SystemClock;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import io.apptik.comm.jus.Cache;
import io.apptik.comm.jus.Cache.Entry;
import io.apptik.comm.jus.JusLog;
import io.apptik.comm.jus.Network;
import io.apptik.comm.jus.NetworkResponse;
import io.apptik.comm.jus.Request;
import io.apptik.comm.jus.RetryPolicy;
import io.apptik.comm.jus.auth.Authenticator;
import io.apptik.comm.jus.error.AuthFailureError;
import io.apptik.comm.jus.error.AuthenticatorError;
import io.apptik.comm.jus.error.ForbiddenError;
import io.apptik.comm.jus.error.JusError;
import io.apptik.comm.jus.error.NetworkError;
import io.apptik.comm.jus.error.NoConnectionError;
import io.apptik.comm.jus.error.RequestError;
import io.apptik.comm.jus.error.ServerError;
import io.apptik.comm.jus.error.TimeoutError;
import io.apptik.comm.jus.http.DateUtils;
import io.apptik.comm.jus.stack.HttpStack;

/**
 * A network performing Jus requests over an {@link HttpStack}.
 */
public class BasicNetwork implements Network {
    protected static final boolean DEBUG = JusLog.DEBUG;

    private static int SLOW_REQUEST_THRESHOLD_MS = 3000;

    private static int DEFAULT_POOL_SIZE = 4096;

    protected final HttpStack mHttpStack;

    protected final ByteArrayPool mPool;

    protected final Authenticator authenticator;

    protected String authToken = null;

    /**
     * @param httpStack HTTP stack to be used
     */
    public BasicNetwork(HttpStack httpStack) {
        // If a pool isn't passed in, then build a small default pool that will give us a lot of
        // benefit and not use too much memory.
        this(httpStack, new ByteArrayPool(DEFAULT_POOL_SIZE), null);
    }

    /**
     * @param httpStack HTTP stack to be used
     * @param pool      a buffer pool that improves GC performance in copy operations
     */
    public BasicNetwork(HttpStack httpStack, ByteArrayPool pool, Authenticator authenticator) {
        this.authenticator = authenticator;
        mHttpStack = httpStack;
        mPool = pool;
    }

    @Override
    public NetworkResponse performRequest(Request<?> request) throws JusError {
        long requestStart = SystemClock.elapsedRealtime();
        while (true) {
            NetworkResponse httpResponse = null;
            try {
                // Gather headers.
                Map<String, String> headers = new HashMap<String, String>();
                addCacheHeaders(headers, request.getCacheEntry());
                addAuthHeaders(headers);

                httpResponse = mHttpStack.performRequest(request, headers, mPool);

                // Handle cache validation.
                if (httpResponse.statusCode == HttpURLConnection.HTTP_NOT_MODIFIED
                        && request.getCacheEntry() != null) {

                    Entry entry = request.getCacheEntry();

                    // A HTTP 304 response does not have all header fields. We
                    // have to use the header fields from the cache entry plus
                    // the new ones from the response.
                    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
                    entry.responseHeaders.putAll(httpResponse.headers);
                    httpResponse.headers.putAll(entry.responseHeaders);
                }

                // if the request is slow, log it.
                long requestLifetime = SystemClock.elapsedRealtime() - requestStart;
                logSlowRequests(requestLifetime, request, httpResponse.data, httpResponse.statusCode);

                if (httpResponse.statusCode < 200 || httpResponse.statusCode > 299) {
                    throw new IOException();
                }
                return httpResponse;
            } catch (SocketTimeoutException e) {
                attemptRetryOnException("socket", request, new TimeoutError());
            } catch (MalformedURLException e) {
                throw new RuntimeException("Bad URL " + request.getUrl(), e);
            } catch (IOException e) {
                NetworkResponse networkResponse = null;
                if (httpResponse != null) {
                    networkResponse = httpResponse;
                } else {
                    throw new NoConnectionError(e);
                }
                JusLog.e("Unexpected response code %d for %s", networkResponse.statusCode, request.getUrl());
                if (networkResponse != null) {
                    if (networkResponse.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        // thrown when available Authenticator is available
                        if (authenticator != null) {
                            try {
                                //TODO call refresh token may be
                                authToken = authenticator.getAuthToken();
                            } catch (AuthenticatorError authenticatorError) {
                                //finally we didn't succeed
                                throw new AuthFailureError(
                                        authenticatorError.getResolutionIntent());
                            }
                            attemptRetryOnException("auth", request,
                                    new AuthFailureError(networkResponse));
                        } else {
                            //or if another way of auth is used
                            throw new AuthFailureError(networkResponse);
                        }
                    } else if (networkResponse.statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
                        throw new ForbiddenError(networkResponse);
                    } else if (networkResponse.statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
                        attemptRetryOnException("http-client", request, new TimeoutError());
                    } else if (networkResponse.statusCode == HttpURLConnection.HTTP_GATEWAY_TIMEOUT) {
                        attemptRetryOnException("gateway-client", request, new TimeoutError());
                    } else if (networkResponse.statusCode > 399 && networkResponse.statusCode < 500) {
                        //some request query error that does not make sense to retry, assuming the service we use is deterministic
                        throw new RequestError(networkResponse);
                    } else if (networkResponse.statusCode > 499) {
                        //TODO some server error might not need to be retried
                        attemptRetryOnException("server",
                                request, new ServerError(request, networkResponse));
                    } else {
                        //unclassified error
                        throw new JusError(networkResponse);
                    }
                } else {
                    throw new NetworkError(networkResponse);
                }
            } catch (AuthenticatorError authenticatorError) {
                //we have failed to get a token so give it up
                throw new AuthFailureError(
                        authenticatorError.getResolutionIntent());
            }
        }
    }

    /**
     * Logs requests that took over SLOW_REQUEST_THRESHOLD_MS to complete.
     */
    private void logSlowRequests(long requestLifetime, Request<?> request,
                                 byte[] responseContents, int statusCode) {
        if (DEBUG || requestLifetime > SLOW_REQUEST_THRESHOLD_MS) {
            JusLog.d("HTTP response for request=<%s> [lifetime=%d], [size=%s], " +
                            "[rc=%d], [retryCount=%s]", request, requestLifetime,
                    responseContents != null ? responseContents.length : "null",
                    statusCode, request.getRetryPolicy().getCurrentRetryCount());
        }
    }

    /**
     * Attempts to prepare the request for a retry. If there are no more attempts remaining in the
     * request's retry policy, a timeout exception is thrown.
     *
     * @param request The request to use.
     */
    private static void attemptRetryOnException(String logPrefix, Request<?> request,
                                                JusError exception) throws JusError {
        RetryPolicy retryPolicy = request.getRetryPolicy();
        int oldTimeout = request.getTimeoutMs();

        try {
            retryPolicy.retry(exception);
        } catch (JusError e) {
            request.addMarker(
                    String.format("%s-timeout-giveup [timeout=%s]", logPrefix, oldTimeout));
            throw e;
        }
        request.addMarker(String.format("%s-retry [timeout=%s]", logPrefix, oldTimeout));
    }

    private void addAuthHeaders(Map<String, String> headers) throws AuthenticatorError {
        if (authenticator == null) return;
        if (authToken == null) {
            authToken = authenticator.getAuthToken();
        }
        headers.put("Authorization", "Bearer " + authToken);
    }

    private void addCacheHeaders(Map<String, String> headers, Cache.Entry entry) {
        // If there's no cache entry, we're done.
        if (entry == null) {
            return;
        }

        if (entry.etag != null) {
            headers.put("If-None-Match", entry.etag);
        }

        if (entry.lastModified > 0) {
            Date refTime = new Date(entry.lastModified);
            headers.put("If-Modified-Since", DateUtils.formatDate(refTime));
        }
    }

    protected void logError(String what, String url, long start) {
        long now = SystemClock.elapsedRealtime();
        JusLog.v("HTTP ERROR(%s) %d ms to fetch %s", what, (now - start), url);
    }

}
