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

package org.djodjo.comm.jus.toolbox;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;

import org.djodjo.comm.jus.NetworkResponse;
import org.djodjo.comm.jus.Response;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowBitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ImageRequestTest {

    @Test public void parseNetworkResponse_resizing() throws Exception {
        // This is a horrible hack but Robolectric doesn't have a way to provide
        // width and height hints for decodeByteArray. It works because the byte array
        // "file:fake" is ASCII encodable and thus the name in Robolectric's fake
        // bitmap creator survives as-is, and provideWidthAndHeightHints puts
        // "file:" + name in its lookaside map. I write all this because it will
        // probably break mysteriously at some point and I feel terrible about your
        // having to debug it.
        byte[] jpegBytes = "file:fake".getBytes();
        ShadowBitmapFactory.provideWidthAndHeightHints("fake", 1024, 500);
        NetworkResponse jpeg = new NetworkResponse(jpegBytes);

        // No resize
        verifyResize(jpeg, 0, 0, 1024, 500);

        // Exact sizes
        verifyResize(jpeg, 512, 250, 512, 250); // exactly half
        verifyResize(jpeg, 511, 249, 509, 249); // just under half
        verifyResize(jpeg, 1080, 500, 1024, 500); // larger
        verifyResize(jpeg, 500, 500, 500, 244); // keep same ratio

        // Specify only width, preserve aspect ratio
        verifyResize(jpeg, 512, 0, 512, 250);
        verifyResize(jpeg, 800, 0, 800, 390);
        verifyResize(jpeg, 1024, 0, 1024, 500);

        // Specify only height, preserve aspect ratio
        verifyResize(jpeg, 0, 250, 512, 250);
        verifyResize(jpeg, 0, 391, 800, 391);
        verifyResize(jpeg, 0, 500, 1024, 500);
    }

    private void verifyResize(NetworkResponse networkResponse, int maxWidth, int maxHeight,
            int expectedWidth, int expectedHeight) {
        ImageRequest request = new ImageRequest(
                "", null, maxWidth, maxHeight, Config.RGB_565, null);
        Response<Bitmap> response = request.parseNetworkResponse(networkResponse);
        assertNotNull(response);
        assertTrue(response.isSuccess());
        Bitmap bitmap = response.result;
        assertNotNull(bitmap);
        assertEquals(expectedWidth, bitmap.getWidth());
        assertEquals(expectedHeight, bitmap.getHeight());
    }

    @Test public void findBestSampleSize() {
        // desired == actual == 1
        assertEquals(1, ImageRequest.findBestSampleSize(100, 150, 100, 150));

        // exactly half == 2
        assertEquals(2, ImageRequest.findBestSampleSize(280, 160, 140, 80));

        // just over half == 1
        assertEquals(1, ImageRequest.findBestSampleSize(1000, 800, 501, 401));

        // just under 1/4 == 4
        assertEquals(4, ImageRequest.findBestSampleSize(100, 200, 24, 50));
    }

    private static byte[] readInputStream(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        in.close();
        return bytes.toByteArray();
    }
}