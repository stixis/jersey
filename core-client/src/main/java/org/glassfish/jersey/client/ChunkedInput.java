/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.jersey.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.glassfish.jersey.client.internal.LocalizationMessages;
import org.glassfish.jersey.message.MessageBodyWorkers;

/**
 * Response entity type used for receiving messages in "typed" chunks.
 *
 * This data type is useful for consuming partial responses from large or continuous data
 * input streams.
 *
 * @param <T> chunk type.
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
public class ChunkedInput<T> extends GenericType<T> implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(ChunkedInput.class.getName());

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ChunkParser parser = createParser("\r\n");
    private MediaType mediaType;

    private final InputStream inputStream;
    private final Annotation[] annotations;
    private final MultivaluedMap<String, String> headers;
    private final MessageBodyWorkers messageBodyWorkers;
    private final ClientRequest request;

    /**
     * Create new chunk parser that will split the response entity input stream
     * based on a fixed boundary string.
     *
     * @param boundary chunk boundary.
     * @return new fixed boundary string-based chunk parser.
     */
    public static ChunkParser createParser(final String boundary) {
        return new FixedBoundaryParser(boundary.getBytes());
    }

    /**
     * Create new chunk parser that will split the response entity input stream
     * based on a fixed boundary sequence of bytes.
     *
     * @param boundary chunk boundary.
     * @return new fixed boundary sequence-based chunk parser.
     */
    public static ChunkParser createParser(final byte[] boundary) {
        return new FixedBoundaryParser(boundary);
    }

    private static class FixedBoundaryParser implements ChunkParser {
        private final byte[] delimiter;
        private final byte[] boundary;

        public FixedBoundaryParser(byte[] boundary) {
            this.boundary = boundary;
            delimiter = Arrays.copyOf(boundary, boundary.length);
        }

        @Override
        public byte[] readChunk(InputStream in) throws IOException {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final byte[] delimiterBuffer = new byte[delimiter.length];

            int data;
            do {
                int dPos = 0;
                while ((data = in.read()) != -1) {
                    final byte b = (byte) data;
                    if (b == delimiter[dPos]) {
                        delimiterBuffer[dPos++] = b;
                        if (dPos == delimiter.length) {
                            // found chunk delimiter
                            break;
                        }
                    } else if (dPos > 0) {
                        baos.write(delimiterBuffer, 0, dPos);
                        dPos = 0;
                    }
                    baos.write(b);
                }
            } while (data != -1 && baos.size() == 0);

            if (baos.size() > 0) {
                return baos.toByteArray();
            }
            return null;
        }
    }

    /**
     * Package-private constructor used by the {@link ChunkedInputReader}.
     *
     * @param chunkType          chunk type.
     * @param inputStream        response input stream.
     * @param annotations        annotations associated with response entity.
     * @param mediaType          response entity media type.
     * @param headers            response headers.
     * @param messageBodyWorkers message body workers.
     * @param request            client request.
     */
    ChunkedInput(
            Type chunkType,
            InputStream inputStream,
            Annotation[] annotations,
            MediaType mediaType,
            MultivaluedMap<String, String> headers,
            MessageBodyWorkers messageBodyWorkers,
            ClientRequest request) {
        super(chunkType);

        this.inputStream = inputStream;
        this.annotations = annotations;
        this.mediaType = mediaType;
        this.headers = headers;
        this.messageBodyWorkers = messageBodyWorkers;
        this.request = request;
    }

    /**
     * Get the underlying chunk parser.
     * <p>
     * Note: Access to internal chunk parser is not a thread-safe operation and has to be explicitly synchronized
     * in case the chunked input is used from multiple threads.
     * </p>
     *
     * @return underlying chunk parser.
     */
    public ChunkParser getParser() {
        return parser;
    }

    /**
     * Set new chunk parser.
     * <p>
     * Note: Access to internal chunk parser is not a thread-safe operation and has to be explicitly synchronized
     * in case the chunked input is used from multiple threads.
     * </p>
     *
     * @param parser new chunk parser.
     */
    public void setParser(ChunkParser parser) {
        this.parser = parser;
    }

    /**
     * Get chunk data media type.
     *
     * Default chunk data media type is derived from the value of the response
     * <code>{@value javax.ws.rs.core.HttpHeaders#CONTENT_TYPE}</code> header field.
     * This default value may be manually overridden by {@link #setChunkType(javax.ws.rs.core.MediaType) setting}
     * a custom non-{@code null} chunk media type value.
     * <p>
     * Note: Access to internal chunk media type is not a thread-safe operation and has to
     * be explicitly synchronized in case the chunked input is used from multiple threads.
     * </p>
     *
     * @return media type specific to each chunk of data.
     */
    @SuppressWarnings("HtmlTagCanBeJavadocTag")
    public MediaType getChunkType() {
        return mediaType;
    }

    /**
     * Set custom chunk data media type.
     *
     * By default, chunk data media type is derived from the value of the response
     * <code>{@value javax.ws.rs.core.HttpHeaders#CONTENT_TYPE}</code> header field.
     * Using this methods will override the default chunk media type value and set it
     * to a custom non-{@code null} chunk media type. Once this method is invoked,
     * all subsequent {@link #read chunk reads} will use the newly set chunk media
     * type when selecting the proper {@link javax.ws.rs.ext.MessageBodyReader} for
     * chunk de-serialization.
     * <p>
     * Note: Access to internal chunk media type is not a thread-safe operation and has to
     * be explicitly synchronized in case the chunked input is used from multiple threads.
     * </p>
     *
     * @param mediaType custom chunk data media type. Must not be {@code null}.
     * @throws IllegalArgumentException in case the {@code mediaType} is {@code null}.
     */
    @SuppressWarnings("HtmlTagCanBeJavadocTag")
    public void setChunkType(MediaType mediaType) throws IllegalArgumentException {
        if (mediaType == null) {
            throw new IllegalArgumentException(LocalizationMessages.CHUNKED_INPUT_MEDIA_TYPE_NULL());
        }
        this.mediaType = mediaType;
    }

    /**
     * Set custom chunk data media type from a string value.
     * <p>
     * Note: Access to internal chunk media type is not a thread-safe operation and has to
     * be explicitly synchronized in case the chunked input is used from multiple threads.
     * </p>
     *
     * @param mediaType custom chunk data media type. Must not be {@code null}.
     * @throws IllegalArgumentException in case the {@code mediaType} cannot be parsed into
     *                                  a valid {@link MediaType} instance or is {@code null}.
     * @see #setChunkType(javax.ws.rs.core.MediaType)
     */
    @SuppressWarnings("HtmlTagCanBeJavadocTag")
    public void setChunkType(String mediaType) throws IllegalArgumentException {
        this.mediaType = MediaType.valueOf(mediaType);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, LocalizationMessages.CHUNKED_INPUT_STREAM_CLOSING_ERROR(), e);
                }
            }
        }
    }

    /**
     * Check if the chunked input has been closed.
     *
     * @return {@code true} if this chunked input has been closed, {@code false} otherwise.
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Read next chunk from the response stream and convert it to a Java instance
     * using the {@link #getChunkType() chunk media type}.
     * <p>
     * Note: Access to internal chunk parser is not a thread-safe operation and has to be explicitly synchronized
     * in case the chunked input is used from multiple threads.
     * </p>
     *
     * @return next streamed chunk.
     * @throws IllegalStateException in case this chunked input has been closed.
     */
    @SuppressWarnings("HtmlTagCanBeJavadocTag")
    public T read() throws IllegalStateException {
        if (closed.get()) {
            throw new IllegalStateException(LocalizationMessages.CHUNKED_INPUT_CLOSED());
        }

        try {
            byte[] chunk = parser.readChunk(inputStream);
            if (chunk != null) {
                ByteArrayInputStream chunkStream = new ByteArrayInputStream(chunk);
                //noinspection unchecked
                return (T) messageBodyWorkers.readFrom(
                        getRawType(),
                        getType(),
                        annotations,
                        mediaType,
                        headers,
                        request.getPropertiesDelegate(),
                        chunkStream,
                        false);
            }
        } catch (IOException e) {
            Logger.getLogger(this.getClass().getName()).log(Level.FINE, e.getMessage(), e);
            close();
        }

        close();
        return null;
    }
}
