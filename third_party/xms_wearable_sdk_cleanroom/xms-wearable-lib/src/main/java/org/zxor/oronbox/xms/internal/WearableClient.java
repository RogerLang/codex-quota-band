package org.zxor.oronbox.xms.internal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import com.xiaomi.xms.wearable.IServiceConnectedListener;
import com.xiaomi.xms.wearable.IWearableInterface;
import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.node.INodeCallback;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;

import org.zxor.oronbox.xms.WearableBackend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the XMS service binding and keeps backend selection separate from API
 * calls. A successful bindService call only means that Android accepted the
 * request; a backend becomes active after getConnectedNodes returns at least
 * one node.
 */
public final class WearableClient {
    public static final String SERVICE_ACTION = "com.xiaomi.wearable.XMS_WEARABLE_SERVICE";
    public static final String ORONBOX_PACKAGE = "org.zxor.oronbox";
    public static final String MI_HEALTH_PACKAGE = "com.mi.health";
    public static final String MI_WEAR_PACKAGE = "com.xiaomi.wearable";

    private static final long PROBE_TIMEOUT_SECONDS = 3;

    private static volatile WearableClient instance;
    private static volatile WearableBackend configuredBackend = WearableBackend.AUTO;

    private final Context context;
    private final Object lock = new Object();
    private final ArrayDeque<RemoteOperation> pending = new ArrayDeque<>();
    private final CopyOnWriteArrayList<OnServiceConnectionListener> connectionListeners =
            new CopyOnWriteArrayList<>();

    private BackendAttemptPlan attemptPlan;
    private IWearableInterface service;
    private IWearableInterface probingService;
    private boolean binding;
    private boolean bound;
    private String bindingPackage;
    private String activePackage;
    private ScheduledFuture<?> probeTimeout;

    /**
     * The official XMS contract uses this Binder only as a registration
     * acknowledgement. It intentionally has no callback method.
     */
    private final IServiceConnectedListener remoteConnectedListener =
            new IServiceConnectedListener.Stub() {};

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            IWearableInterface connected = IWearableInterface.Stub.asInterface(binder);
            String packageName = name.getPackageName();
            synchronized (lock) {
                if (!binding || !packageName.equals(bindingPackage)) return;
                binding = false;
                probingService = connected;
                activePackage = packageName;
            }
            probe(packageName, connected);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            handleServiceLoss(name.getPackageName(), true);
        }

        @Override public void onBindingDied(ComponentName name) {
            handleServiceLoss(name.getPackageName(), true);
        }

        @Override public void onNullBinding(ComponentName name) {
            handleCandidateFailure(name.getPackageName(), null);
        }
    };

    private WearableClient(Context context) {
        this.context = context;
        synchronized (lock) {
            attemptPlan = new BackendAttemptPlan(configuredBackend);
        }
        bind();
    }

    public static WearableClient get(Context context) {
        WearableClient value = instance;
        if (value == null) {
            synchronized (WearableClient.class) {
                value = instance;
                if (value == null) {
                    instance = value = new WearableClient(context.getApplicationContext());
                }
            }
        }
        return value;
    }

    public static void configure(Context context, WearableBackend backend) {
        synchronized (WearableClient.class) {
            configuredBackend = backend == null ? WearableBackend.AUTO : backend;
            if (instance != null) {
                instance.rebind();
            } else {
                instance = new WearableClient(context.getApplicationContext());
            }
        }
    }

    public WearableBackend getConfiguredBackend() {
        return configuredBackend;
    }

    public boolean isConnected() {
        synchronized (lock) {
            return service != null;
        }
    }

    public String getBoundPackage() {
        synchronized (lock) {
            return service == null ? null : activePackage;
        }
    }

    public void execute(boolean requireConnected, RemoteOperation operation) {
        IWearableInterface current;
        synchronized (lock) {
            current = service;
            if (current == null && !requireConnected) {
                pending.add(operation);
            }
        }
        if (current != null) {
            IWearableInterface target = current;
            TaskExecutors.BACKGROUND.execute(() -> invoke(target, operation));
        } else if (requireConnected) {
            operation.onUnavailable();
        } else {
            bind();
        }
    }

    public void addConnectionListener(OnServiceConnectionListener listener) {
        if (listener == null) return;
        connectionListeners.addIfAbsent(listener);
        if (isConnected()) TaskExecutors.MAIN.execute(listener::onServiceConnected);
    }

    public void removeConnectionListener(OnServiceConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    private void invoke(IWearableInterface target, RemoteOperation operation) {
        try {
            operation.run(target);
        } catch (RemoteException error) {
            operation.onRemoteError(error);
            handleServiceLoss(null, true);
        } catch (RuntimeException error) {
            operation.onRemoteError(error);
        }
    }

    /** Starts one candidate binding. The next candidate is selected only after
     * this candidate fails to bind or fails the readiness probe. */
    private void bind() {
        String packageName;
        synchronized (lock) {
            if (service != null || probingService != null || binding) return;
            packageName = attemptPlan.next();
            if (packageName == null) {
                failPendingUnavailableLocked();
                return;
            }
            binding = true;
            bindingPackage = packageName;
        }

        Intent intent = new Intent(SERVICE_ACTION).setPackage(packageName);
        PackageManager pm = context.getPackageManager();
        if (pm.resolveService(intent, 0) == null) {
            handleCandidateFailure(packageName, null);
            return;
        }

        boolean started;
        try {
            started = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException ignored) {
            started = false;
        }
        synchronized (lock) {
            bound = started;
        }
        if (!started) handleCandidateFailure(packageName, null);
    }

    private void probe(String packageName, IWearableInterface candidate) {
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledFuture<?> timeout = TaskExecutors.SCHEDULED.schedule(
                () -> {
                    if (completed.compareAndSet(false, true)) {
                        handleCandidateFailure(packageName, candidate);
                    }
                },
                PROBE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );
        synchronized (lock) {
            if (probingService != candidate) {
                timeout.cancel(false);
                return;
            }
            probeTimeout = timeout;
        }
        try {
            candidate.getConnectedNodes(new INodeCallback.Stub() {
                @Override public void onNodesConnected(List<Node> nodes) {
                    if (!completed.compareAndSet(false, true)) return;
                    timeout.cancel(false);
                    if (nodes == null || nodes.isEmpty()) {
                        handleCandidateFailure(packageName, candidate);
                    } else {
                        activate(packageName, candidate);
                    }
                }

                @Override public void onFailure(Status status) {
                    if (!completed.compareAndSet(false, true)) return;
                    timeout.cancel(false);
                    handleCandidateFailure(packageName, candidate);
                }
            });
        } catch (RemoteException | RuntimeException error) {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel(false);
                handleCandidateFailure(packageName, candidate);
            }
        }
    }

    private void activate(String packageName, IWearableInterface candidate) {
        List<RemoteOperation> queued;
        synchronized (lock) {
            if (probingService != candidate) return;
            if (probeTimeout != null) probeTimeout.cancel(false);
            probeTimeout = null;
            probingService = null;
            service = candidate;
            activePackage = packageName;
            queued = new ArrayList<>(pending);
            pending.clear();
        }
        notifyConnected();
        TaskExecutors.BACKGROUND.execute(() -> {
            for (RemoteOperation operation : queued) invoke(candidate, operation);
            try {
                candidate.registerServiceConnectedListener(remoteConnectedListener);
            } catch (RemoteException error) {
                handleServiceLoss(packageName, true);
            }
        });
    }

    private void handleCandidateFailure(String packageName, IWearableInterface candidate) {
        boolean shouldUnbind;
        synchronized (lock) {
            if (candidate != null && probingService != candidate) return;
            if (candidate == null && (!binding || !packageName.equals(bindingPackage))) return;
            if (probeTimeout != null) probeTimeout.cancel(false);
            probeTimeout = null;
            probingService = null;
            binding = false;
            bindingPackage = null;
            activePackage = null;
            shouldUnbind = bound;
            bound = false;
        }
        if (shouldUnbind) unbindSafely();
        TaskExecutors.BACKGROUND.execute(this::bind);
    }

    private void handleServiceLoss(String packageName, boolean notify) {
        boolean changed;
        boolean shouldUnbind;
        synchronized (lock) {
            if (packageName != null && activePackage != null && !packageName.equals(activePackage)) {
                return;
            }
            changed = service != null;
            service = null;
            probingService = null;
            binding = false;
            bindingPackage = null;
            activePackage = null;
            if (probeTimeout != null) probeTimeout.cancel(false);
            probeTimeout = null;
            shouldUnbind = bound;
            bound = false;
        }
        if (shouldUnbind) unbindSafely();
        if (notify && changed) notifyDisconnected();
        TaskExecutors.BACKGROUND.execute(this::bind);
    }

    private void rebind() {
        boolean hadService;
        boolean shouldUnbind;
        synchronized (lock) {
            hadService = service != null;
            shouldUnbind = bound;
            service = null;
            probingService = null;
            binding = false;
            bound = false;
            bindingPackage = null;
            activePackage = null;
            if (probeTimeout != null) probeTimeout.cancel(false);
            probeTimeout = null;
            attemptPlan = new BackendAttemptPlan(configuredBackend);
        }
        if (shouldUnbind) unbindSafely();
        if (hadService) notifyDisconnected();
        bind();
    }

    private void unbindSafely() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // The service may have died before Android delivered the callback.
        }
    }

    private void failPendingUnavailableLocked() {
        if (pending.isEmpty()) return;
        List<RemoteOperation> failed = new ArrayList<>(pending);
        pending.clear();
        TaskExecutors.BACKGROUND.execute(() -> {
            for (RemoteOperation operation : failed) operation.onUnavailable();
        });
    }

    private void notifyConnected() {
        for (OnServiceConnectionListener listener : connectionListeners) {
            TaskExecutors.MAIN.execute(listener::onServiceConnected);
        }
    }

    private void notifyDisconnected() {
        for (OnServiceConnectionListener listener : connectionListeners) {
            TaskExecutors.MAIN.execute(listener::onServiceDisconnected);
        }
    }

    public interface RemoteOperation {
        void run(IWearableInterface service) throws RemoteException;
        void onUnavailable();
        void onRemoteError(Exception error);
    }
}
