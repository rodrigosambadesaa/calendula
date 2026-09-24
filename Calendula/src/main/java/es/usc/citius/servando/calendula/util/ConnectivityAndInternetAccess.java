/*
 * SPDX-License-Identifier: MIT
 *
 * Connectivity primitives adapted for Calendula from Rodrigo Sambade Saa's
 * ConnectivityAndInternetAccess gist:
 * https://gist.github.com/rodrigosambadesaa/729cca29a031fef4e2f15751863b655f
 *
 * Based on Connectivity.java by Emil Davtyan (emil2k), later modified by str4d.
 */

package es.usc.citius.servando.calendula.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * VPN-aware connectivity helpers used by Calendula.
 *
 * <p>The passive connectivity result deliberately does not claim that an arbitrary Internet
 * destination is reachable. It verifies that Android exposes an Internet-capable route and, when
 * that route is a VPN, that a usable non-VPN underlying network still exists. Backend preflight
 * then resolves the actual request host through the effective Android network before the request
 * is started.</p>
 */
public final class ConnectivityAndInternetAccess {

    static final long BACKEND_DNS_TIMEOUT_MS = 1500L;

    private ConnectivityAndInternetAccess() {
    }

    public interface NetworkStateCallback {
        void onNetworkStateChanged(NetworkState state);
    }

    public static final class NetworkState {
        private final boolean connected;
        private final boolean internetValidated;
        private final boolean captivePortalDetected;
        private final boolean vpnActive;
        private final long observedAtElapsedRealtime;

        NetworkState(boolean connected,
                     boolean internetValidated,
                     boolean captivePortalDetected,
                     boolean vpnActive,
                     long observedAtElapsedRealtime) {
            this.connected = connected;
            this.internetValidated = internetValidated;
            this.captivePortalDetected = captivePortalDetected;
            this.vpnActive = vpnActive;
            this.observedAtElapsedRealtime = observedAtElapsedRealtime;
        }

        public boolean isConnected() {
            return connected;
        }

        public boolean isInternetValidated() {
            return internetValidated;
        }

        public boolean isCaptivePortalDetected() {
            return captivePortalDetected;
        }

        public boolean isVpnActive() {
            return vpnActive;
        }

        public long getObservedAtElapsedRealtime() {
            return observedAtElapsedRealtime;
        }

        boolean sameConnectivityState(NetworkState other) {
            return other != null
                    && connected == other.connected
                    && internetValidated == other.internetValidated
                    && captivePortalDetected == other.captivePortalDetected
                    && vpnActive == other.vpnActive;
        }
    }

    /**
     * Lifecycle-friendly passive observer. API 24+ follows the application's default network;
     * older supported versions use the legacy connectivity broadcast.
     */
    public static final class NetworkObserver implements Closeable {
        private final Context applicationContext;
        private final ConnectivityManager connectivityManager;
        private final NetworkStateCallback callback;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        private volatile boolean closed;
        private volatile NetworkState latestState;
        private ConnectivityManager.NetworkCallback networkCallback;
        private BroadcastReceiver legacyReceiver;

        NetworkObserver(Context context, NetworkStateCallback callback) {
            if (context == null) {
                throw new IllegalArgumentException("context == null");
            }
            if (callback == null) {
                throw new IllegalArgumentException("callback == null");
            }

            Context appContext = context.getApplicationContext();
            applicationContext = appContext != null ? appContext : context;
            connectivityManager = manager(applicationContext);
            this.callback = callback;

            publish(snapshotNetworkState(applicationContext));

            if (connectivityManager == null) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        publish(snapshotNetworkState(applicationContext));
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        publish(networkStateFromCapabilities(
                                connectivityManager,
                                caps,
                                vpnActive(applicationContext)));
                    }

                    @Override
                    public void onLost(Network network) {
                        publish(snapshotNetworkState(applicationContext));
                    }
                };
                try {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback);
                } catch (RuntimeException e) {
                    LogUtil.w("NetworkObserver",
                            "Unable to register default network callback; falling back to broadcasts");
                    networkCallback = null;
                    registerLegacyReceiver();
                }
            } else {
                registerLegacyReceiver();
            }
        }

        private void registerLegacyReceiver() {
            legacyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    publish(snapshotNetworkState(applicationContext));
                }
            };
            applicationContext.registerReceiver(
                    legacyReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }

        private void publish(final NetworkState state) {
            if (closed || state == null) {
                return;
            }

            NetworkState previous = latestState;
            if (previous != null && previous.sameConnectivityState(state)) {
                return;
            }
            latestState = state;

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!closed) {
                        callback.onNetworkStateChanged(state);
                    }
                }
            });
        }

        public NetworkState getLatestState() {
            NetworkState state = latestState;
            return state != null ? state : snapshotNetworkState(applicationContext);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;

            if (connectivityManager != null && networkCallback != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered by the platform or lifecycle owner.
                }
                networkCallback = null;
            }

            if (legacyReceiver != null) {
                try {
                    applicationContext.unregisterReceiver(legacyReceiver);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered.
                }
                legacyReceiver = null;
            }
        }
    }

    interface HostResolver {
        boolean resolves(String host, Network network) throws IOException;
    }

    private static final HostResolver DEFAULT_HOST_RESOLVER = new HostResolver() {
        @Override
        public boolean resolves(String host, Network network) throws IOException {
            InetAddress[] addresses;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && network != null) {
                addresses = network.getAllByName(host);
            } else {
                addresses = InetAddress.getAllByName(host);
            }
            return addresses != null && addresses.length > 0;
        }
    };

    /**
     * Returns whether Android exposes a locally usable Internet-capable route.
     *
     * <p>A VPN is only considered usable while at least one Internet-capable non-VPN network
     * remains available underneath it. This prevents a VPN interface such as AdGuard from being
     * mistaken for real connectivity after Wi-Fi/cellular/Ethernet disappears.</p>
     */
    public static boolean isConnected(Context context) {
        ConnectivityManager connectivityManager = manager(context);
        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network active = connectivityManager.getActiveNetwork();
            return active != null
                    && isEffectivelyUsable(
                    connectivityManager,
                    connectivityManager.getNetworkCapabilities(active));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return false;
            }
            for (Network network : networks) {
                if (isEffectivelyUsable(
                        connectivityManager,
                        connectivityManager.getNetworkCapabilities(network))) {
                    return true;
                }
            }
            return false;
        }

        NetworkInfo active = connectivityManager.getActiveNetworkInfo();
        if (active == null || !active.isConnected()) {
            return false;
        }
        return active.getType() != ConnectivityManager.TYPE_VPN
                || hasUsableNonVpnNetworkLegacy(connectivityManager);
    }

    public static boolean hasUnderlyingNetwork(Context context) {
        ConnectivityManager connectivityManager = manager(context);
        return connectivityManager != null && hasUsableNonVpnNetwork(connectivityManager);
    }

    /** Compatibility alias retained from the source gist. */
    public static boolean hasPhysicalNetwork(Context context) {
        return hasUnderlyingNetwork(context);
    }

    public static boolean vpnActive(Context context) {
        ConnectivityManager connectivityManager = manager(context);
        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return false;
            }
            for (Network network : networks) {
                NetworkCapabilities capabilities =
                        connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
            return false;
        }

        NetworkInfo[] networks = connectivityManager.getAllNetworkInfo();
        if (networks == null) {
            return false;
        }
        for (NetworkInfo info : networks) {
            if (info != null
                    && info.getType() == ConnectivityManager.TYPE_VPN
                    && info.isConnected()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInternetValidated(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        ConnectivityManager connectivityManager = manager(context);
        if (connectivityManager == null) {
            return false;
        }
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
        return isEffectivelyUsable(connectivityManager, capabilities)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public static boolean isCaptivePortalDetected(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        ConnectivityManager connectivityManager = manager(context);
        if (connectivityManager == null) {
            return false;
        }
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    public static NetworkState snapshotNetworkState(Context context) {
        ConnectivityManager connectivityManager = manager(context);
        if (connectivityManager == null) {
            return disconnectedNetworkState(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network active = connectivityManager.getActiveNetwork();
            if (active == null) {
                return disconnectedNetworkState(vpnActive(context));
            }
            return networkStateFromCapabilities(
                    connectivityManager,
                    connectivityManager.getNetworkCapabilities(active),
                    vpnActive(context));
        }

        return new NetworkState(
                isConnected(context),
                false,
                false,
                vpnActive(context),
                SystemClock.elapsedRealtime());
    }

    public static NetworkObserver observeNetwork(
            Context context,
            NetworkStateCallback callback) {
        return new NetworkObserver(context, callback);
    }

    /**
     * Checks the two conditions Calendula needs immediately before a backend request:
     * a usable VPN-aware Android route and DNS resolution of the request's own backend host.
     *
     * <p>This intentionally avoids generic Google/Cloudflare probes. The real backend request
     * remains authoritative and must still handle transport and HTTP errors normally.</p>
     */
    public static boolean canReachBackend(Context context, String url) {
        return canReachBackend(context, url, DEFAULT_HOST_RESOLVER);
    }

    static boolean canReachBackend(Context context, String url, HostResolver resolver) {
        final String host = backendHost(url);
        if (host == null || resolver == null || !isConnected(context)) {
            return false;
        }

        final Network effectiveNetwork = effectiveNetwork(context);
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "calendula-backend-dns");
                thread.setDaemon(true);
                return thread;
            }
        });

        Future<Boolean> future = executor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return resolver.resolves(host, effectiveNetwork);
            }
        });

        try {
            return Boolean.TRUE.equals(
                    future.get(BACKEND_DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            future.cancel(true);
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    static String backendHost(String url) {
        if (url == null) {
            return null;
        }
        try {
            URL parsed = new URL(url);
            String protocol = parsed.getProtocol();
            if (!"http".equalsIgnoreCase(protocol)
                    && !"https".equalsIgnoreCase(protocol)) {
                return null;
            }
            String host = parsed.getHost();
            return host == null || host.trim().isEmpty() ? null : host;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static ConnectivityManager manager(Context context) {
        if (context == null) {
            return null;
        }
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private static boolean isUsable(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static boolean isEffectivelyUsable(
            ConnectivityManager connectivityManager,
            NetworkCapabilities capabilities) {
        if (!isUsable(capabilities)) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return true;
        }

        // A VPN may retain INTERNET after its real underlying path disappeared.
        return hasUsableNonVpnNetwork(connectivityManager);
    }

    private static boolean hasUsableNonVpnNetwork(
            ConnectivityManager connectivityManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return hasUsableNonVpnNetworkLegacy(connectivityManager);
        }

        Network[] networks = connectivityManager.getAllNetworks();
        if (networks == null) {
            return false;
        }

        for (Network network : networks) {
            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(network);
            if (!isUsable(capabilities)) {
                continue;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return true;
                }
            } else if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableNonVpnNetworkLegacy(
            ConnectivityManager connectivityManager) {
        NetworkInfo[] networks = connectivityManager.getAllNetworkInfo();
        if (networks == null) {
            return false;
        }
        for (NetworkInfo info : networks) {
            if (info != null
                    && info.getType() != ConnectivityManager.TYPE_VPN
                    && info.isConnected()) {
                return true;
            }
        }
        return false;
    }

    private static Network effectiveNetwork(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // On API 21-22 there is no public default-Network object. Let InetAddress use
            // the process/system default route instead of selecting an arbitrary network.
            return null;
        }

        ConnectivityManager connectivityManager = manager(context);
        if (connectivityManager == null) {
            return null;
        }

        Network active = connectivityManager.getActiveNetwork();
        if (active == null) {
            return null;
        }

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
        return isEffectivelyUsable(connectivityManager, capabilities) ? active : null;
    }

    private static NetworkState networkStateFromCapabilities(
            ConnectivityManager connectivityManager,
            NetworkCapabilities capabilities,
            boolean vpnActive) {
        boolean connected = isEffectivelyUsable(connectivityManager, capabilities);
        boolean validated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && connected
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean captivePortal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && capabilities != null
                && capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);

        return new NetworkState(
                connected,
                validated,
                captivePortal,
                vpnActive,
                SystemClock.elapsedRealtime());
    }

    private static NetworkState disconnectedNetworkState(boolean vpnActive) {
        return new NetworkState(
                false,
                false,
                false,
                vpnActive,
                SystemClock.elapsedRealtime());
    }
}
