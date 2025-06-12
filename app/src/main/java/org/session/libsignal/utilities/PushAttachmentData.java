/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.session.libsignal.utilities;

import org.session.libsignal.streams.OutputStreamFactory;

import java.io.InputStream;

public class PushAttachmentData {

  private final String              contentType;
  private final InputStream         data;
  private final long                dataSize;
  private final OutputStreamFactory outputStreamFactory;

  public PushAttachmentData(String contentType, InputStream data, long dataSize,
                            OutputStreamFactory outputStreamFactory)
  {
    this.contentType         = contentType;
    this.data                = data;
    this.dataSize            = dataSize;
    this.outputStreamFactory = outputStreamFactory;
  }

  public String getContentType() {
    return contentType;
  }

  public InputStream getData() {
    return data;
  }

  public long getDataSize() {
    return dataSize;
  }

  public OutputStreamFactory getOutputStreamFactory() {
    return outputStreamFactory;
  }
}
