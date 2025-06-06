/** 
 * Copyright (C) 2011 Whisper Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.mms;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.annimon.stream.Stream;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.util.MediaUtil;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class SlideDeck {

  private final List<Slide> slides = new LinkedList<>();

  public SlideDeck(@NonNull Context context, @NonNull List<? extends Attachment> attachments) {
    for (Attachment attachment : attachments) {
      Slide slide = MediaUtil.getSlideForAttachment(context, attachment);
      if (slide != null) slides.add(slide);
    }
  }

  public SlideDeck(@NonNull Context context, @NonNull Attachment attachment) {
    Slide slide = MediaUtil.getSlideForAttachment(context, attachment);
    if (slide != null) slides.add(slide);
  }

  public SlideDeck() { }

  public void clear() {
    slides.clear();
  }

  @NonNull
  public String getBody() {
    String body = "";

    for (Slide slide : slides) {
      Optional<String> slideBody = slide.getBody();

      if (slideBody.isPresent()) {
        body = slideBody.get();
      }
    }
    return body;
  }

  @NonNull
  public List<Attachment> asAttachments() {
    List<Attachment> attachments = new LinkedList<>();

    for (Slide slide : slides) {
      attachments.add(slide.asAttachment());
    }

    return attachments;
  }

  public void addSlide(Slide slide) {
    slides.add(slide);
  }

  public List<Slide> getSlides() {
    return slides;
  }

  public boolean containsMediaSlide() {
    for (Slide slide : slides) {
      if (slide.hasImage() || slide.hasVideo() || slide.hasAudio() || slide.hasDocument()) {
        return true;
      }
    }
    return false;
  }

  public @Nullable Slide getThumbnailSlide() {
    for (Slide slide : slides) {
      if (slide.hasImage()) {
        return slide;
      }
    }

    return null;
  }

  public @NonNull List<Slide> getThumbnailSlides() {
    return Stream.of(slides).filter(Slide::hasImage).toList();
  }

  public @Nullable AudioSlide getAudioSlide() {
    for (Slide slide : slides) {
      if (slide.hasAudio()) {
        return (AudioSlide)slide;
      }
    }

    return null;
  }

  public @Nullable DocumentSlide getDocumentSlide() {
    for (Slide slide: slides) {
      if (slide.hasDocument()) {
        return (DocumentSlide)slide;
      }
    }

    return null;
  }

  public boolean hasVideo() {
    for (Slide slide : slides) {
      if (slide.hasVideo()) {
        return true;
      }
    }

    return false;
  }

  public @Nullable TextSlide getTextSlide() {
    for (Slide slide: slides) {
      if (MediaUtil.isLongTextType(slide.getContentType())) {
        return (TextSlide)slide;
      }
    }

    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SlideDeck slideDeck = (SlideDeck) o;
    return Objects.equals(slides, slideDeck.slides);
  }

  @Override
  public int hashCode() {
    return Objects.hash(slides);
  }

  public boolean isVoiceNote() {
    List<Attachment> attachments = asAttachments();
    if (attachments.isEmpty()) {
      return false;
    }

    Attachment attachment = attachments.get(0);
    return attachment.isVoiceNote();

  }
}
