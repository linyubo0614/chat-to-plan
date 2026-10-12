package cn.chatplanner.control;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps one conversation request alive across rotation and page switches. */
final class ChatRuntime {
  private static ChatRuntime instance;
  private final Context context;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<Runnable> observers = new HashSet<>();
  private volatile boolean busy;
  private final Runnable pulse =
      new Runnable() {
        public void run() {
          changed();
          if (busy) main.postDelayed(this, 700);
        }
      };

  private ChatRuntime(Context c) {
    context = c.getApplicationContext();
    worker.execute(() -> ReminderScheduler.refresh(context));
  }

  static synchronized ChatRuntime get(Context c) {
    if (instance == null) instance = new ChatRuntime(c);
    return instance;
  }

  boolean busy() {
    return busy;
  }

  void observe(Runnable listener) {
    observers.add(listener);
  }

  void remove(Runnable listener) {
    observers.remove(listener);
  }

  private void changed() {
    for (Runnable r : new HashSet<>(observers)) r.run();
  }

  boolean submit(String text, int action) {
    if (busy) return false;
    busy = true;
    changed();
    main.postDelayed(pulse, 150);
    worker.execute(
        () -> {
          try {
            ConversationEngine e = new ConversationEngine(context);
            if (action == 1) e.retryPending();
            else if (action == 2) e.confirmPending(true);
            else if (action == 3) e.confirmPending(false);
            else if (action == 4) {
              e.clearDraft();
              LocalStore.get(context).appendMessage("assistant", "已结束当前追问，可以记录新的事项。");
            } else e.send(text);
          } catch (Exception ex) {
            LocalStore.get(context).appendMessage("assistant", "这次操作未能完成，请检查设置或稍后重试。");
          } finally {
            main.post(
                () -> {
                  busy = false;
                  main.removeCallbacks(pulse);
                  changed();
                });
          }
        });
    return true;
  }
}
