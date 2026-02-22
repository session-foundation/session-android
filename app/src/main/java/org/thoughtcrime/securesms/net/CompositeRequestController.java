package org.thoughtcrime.securesms.net;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class CompositeRequestController implements RequestController {

  private final List<RequestController> controllers = new ArrayList<>();
  private       boolean                 canceled    = false;

  public synchronized void addController(@NonNull RequestController controller) {
    if (canceled) {
      controller.cancel();
    } else {
      controllers.add(controller);
    }
  }

    @Override
    public synchronized void cancel() {
        canceled = true;
        for (RequestController controller : controllers) {
            controller.cancel();
        }
    }

  public synchronized boolean isCanceled() {
    return canceled;
  }
}
