package cn.chatplanner.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;

/** Same-phone HITA connection. Invoke query/apply on a background thread. */
public final class HitaClient implements AutoCloseable {
  public static final String PACKAGE = "cn.chatplanner.hita";
  public static final String PERMISSION = "cn.chatplanner.hita.permission.LOCAL_PLANNER";
  private static final String DESCRIPTOR = "cn.chatplanner.hita.LocalPlanner.v1";
  private static final ComponentName SERVICE =
      new ComponentName(PACKAGE, "cn.limpu.hita.planner.PlannerLocalService");
  private final Context context;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "hita-local-ipc");
            thread.setDaemon(true);
            return thread;
          });
  private volatile IBinder endpoint;
  private volatile CountDownLatch connectionReady;
  private boolean bound;
  private boolean closed;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
          endpoint = binder;
          signal();
        }

        public void onServiceDisconnected(ComponentName name) {
          endpoint = null;
          signal();
        }

        public void onNullBinding(ComponentName name) {
          endpoint = null;
          signal();
        }

        public void onBindingDied(ComponentName name) {
          endpoint = null;
          signal();
        }

        private void signal() {
          CountDownLatch ready = connectionReady;
          if (ready != null) ready.countDown();
        }
      };

  public HitaClient(Context context) {
    this.context = context.getApplicationContext();
  }

  /** Installed local service; user consent is reported separately by query/apply. */
  public boolean isAvailable() {
    try {
      return context.getPackageManager().getServiceInfo(SERVICE, 0).enabled;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  public Intent authorizationIntent() {
    return new Intent()
        .setComponent(new ComponentName(PACKAGE, "cn.limpu.hita.planner.PlannerSettingsActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  public Intent openIntent() {
    return context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
  }

  public JSONObject query(JSONObject filter) {
    return call(IBinder.FIRST_CALL_TRANSACTION, filter == null ? new JSONObject() : filter, false);
  }

  public JSONObject apply(JSONObject action) {
    if (action == null) return result("invalid", "缺少 HITA 日程操作。", false);
    if ("create".equals(action.optString("operation")) && action.optString("record_id").isEmpty()) {
      try {
        action.put("record_id", UUID.randomUUID().toString());
      } catch (Exception e) {
        return result("invalid", "日程编号生成失败。", false);
      }
    }
    return call(IBinder.FIRST_CALL_TRANSACTION + 1, action, true);
  }

  private synchronized JSONObject call(int code, JSONObject request, boolean mutation) {
    if (Looper.myLooper() == Looper.getMainLooper())
      return result("invalid", "HITA 操作需要在后台执行。", false);
    if (closed) return result("unavailable", "HITA 连接已关闭。", false);
    if (!isAvailable()) return result("unavailable", "请先安装 HITA 兼容版；其他功能仍可正常使用。", false);
    if (context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE)
        != PackageManager.SIGNATURE_MATCH)
      return result("signature_mismatch", "随手记事与 HITA 兼容版需要使用同一版本发布的签名。", false);
    try {
      if (endpoint == null || !endpoint.isBinderAlive()) {
        if (bound) {
          context.unbindService(connection);
          bound = false;
        }
        connectionReady = new CountDownLatch(1);
        bound =
            context.bindService(
                new Intent().setComponent(SERVICE), connection, Context.BIND_AUTO_CREATE);
        if (!bound) return result("unavailable", "无法连接本机 HITA 服务。", false);
        if (!connectionReady.await(5, TimeUnit.SECONDS) || endpoint == null) {
          unbind();
          return result("unavailable", "HITA 连接超时，请打开兼容版后重试。", false);
        }
      }
      final IBinder binder = endpoint;
      final String rawRequest = request.toString();
      if (rawRequest.length() > 65536) return result("invalid", "请求内容过长。", false);
      Future<JSONObject> pending =
          executor.submit(
              () -> {
                Parcel input = Parcel.obtain();
                Parcel output = Parcel.obtain();
                try {
                  input.writeInterfaceToken(DESCRIPTOR);
                  input.writeString(rawRequest);
                  if (!binder.transact(code, input, output, 0))
                    throw new IllegalStateException("Unsupported HITA protocol");
                  output.readException();
                  String raw = output.readString();
                  if (raw == null || raw.length() > 524288)
                    throw new IllegalStateException("Invalid HITA response");
                  return new JSONObject(raw);
                } finally {
                  input.recycle();
                  output.recycle();
                }
              });
      try {
        return pending.get(10, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        // Native Binder cannot be interrupted reliably. A timed-out instance must not queue more
        // calls behind it.
        pending.cancel(true);
        closed = true;
        executor.shutdownNow();
        unbind();
        return result(
            mutation ? "unknown" : "timeout",
            mutation ? "HITA 尚未返回结果，请查询日程核对后再操作。" : "HITA 查询超时，请重试。",
            !mutation ? false : null);
      }
    } catch (SecurityException e) {
      unbind();
      return result("permission_denied", "未获得 HITA 本地接口权限，请安装匹配的兼容版本。", false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      unbind();
      return result(mutation ? "unknown" : "cancelled", "操作中断，请查询日程核对。", mutation ? null : false);
    } catch (Exception e) {
      unbind();
      return result(mutation ? "unknown" : "error", "HITA 连接中断，请查询日程核对。", mutation ? null : false);
    }
  }

  private static JSONObject result(String status, String message, Boolean written) {
    JSONObject value = new JSONObject();
    try {
      value.put("status", status).put("message", message);
      if (written != null) value.put("written", written);
    } catch (Exception ignored) {
    }
    return value;
  }

  private void unbind() {
    endpoint = null;
    if (bound) {
      try {
        context.unbindService(connection);
      } catch (IllegalArgumentException ignored) {
      }
      bound = false;
    }
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      unbind();
      executor.shutdownNow();
    }
  }
}
