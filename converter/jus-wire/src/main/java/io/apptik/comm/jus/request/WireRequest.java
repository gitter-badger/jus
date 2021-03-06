/*
 * Copyright (C) 2015 AppTik Project
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

package io.apptik.comm.jus.request;

import com.squareup.wire.Message;
import com.squareup.wire.ProtoAdapter;

import java.io.IOException;

import io.apptik.comm.jus.NetworkRequest;
import io.apptik.comm.jus.Request;
import io.apptik.comm.jus.converter.WireRequestConverter;
import io.apptik.comm.jus.converter.WireResponseConverter;
import io.apptik.comm.jus.http.HttpUrl;

public class WireRequest<T extends Message> extends Request<T> {

    public WireRequest(String method, HttpUrl url, ProtoAdapter<T> adapter) {
        super(method, url, getWireResponseConverter(adapter));
        setNetworkRequest(NetworkRequest.Builder.from(getNetworkRequest())
                .setHeader("Accept", "application/x-protobuf")
                .build());
    }

    public WireRequest(String method, HttpUrl url, Class<T> cls) {
        this(method, url, getAdapter(cls));
    }

      public WireRequest(String method, String url, ProtoAdapter<T> adapter) {
        super(method, url, getWireResponseConverter(adapter));
        setNetworkRequest(NetworkRequest.Builder.from(getNetworkRequest())
                .setHeader("Accept", "application/x-protobuf")
                .build());
    }

    public WireRequest(String method, String url, Class<T> cls) {
        this(method, url, getAdapter(cls));
    }

    static private WireResponseConverter getWireResponseConverter(ProtoAdapter adapter) {
        if(adapter==null)
            throw new IllegalArgumentException("adapter==null");
        return new WireResponseConverter(adapter);
    }

    static private ProtoAdapter getAdapter(Class cls) {
        if (!(cls instanceof Class<?>)) {
            throw new IllegalArgumentException("Unable to create converter for " + cls);
        }
        Class<?> c = (Class<?>) cls;
        if (!Message.class.isAssignableFrom(c)) {
            throw new IllegalArgumentException("Unable to create converter for " + cls);
        }
        return ProtoAdapter.get(cls);
    }



    public <R extends Message> WireRequest<T> setRequestData(R requestData, ProtoAdapter<R> adapter) {
        try {
            super.setRequestData(requestData, new WireRequestConverter<R>(adapter));
        } catch (IOException e) {
            throw new RuntimeException("Unable to convert " + requestData + " to NetworkRequest", e);
        }
        setNetworkRequest(NetworkRequest.Builder.from(getNetworkRequest())
                .setHeader("Accept", "application/x-protobuf")
                .build());
        return this;
    }

    public <R extends Message> WireRequest<T> setRequestData(R requestData) {
        return setRequestData(requestData,
                (ProtoAdapter<R>) ProtoAdapter.get(requestData.getClass()));
    }

}
