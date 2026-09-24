/*
 *    Calendula - An assistant for personal medication management.
 *    Copyright (C) 2014-2018 CiTIUS - University of Santiago de Compostela
 *
 *    Calendula is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 */

package es.usc.citius.servando.calendula.util;

import android.content.Context;

/**
 * Application-facing network facade.
 *
 * <p>All point-in-time checks are delegated to the VPN-aware connectivity implementation rather
 * than trusting the active VPN interface. The application-level observer is passive; request
 * preflight still re-checks current state to avoid using a stale callback snapshot.</p>
 */
public final class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    private static ConnectivityAndInternetAccess.NetworkObserver networkObserver;
    private static volatile ConnectivityAndInternetAccess.NetworkState latestState;

    private NetworkUtils() {
    }

    public static boolean isNetworkAvailable(final Context ctx) {
        return ConnectivityAndInternetAccess.isConnected(ctx);
    }

    public static boolean isBackendAvailable(final Context ctx, final String url) {
        return ConnectivityAndInternetAccess.canReachBackend(ctx, url);
    }

    public static boolean isVpnActive(final Context ctx) {
        return ConnectivityAndInternetAccess.vpnActive(ctx);
    }

    public static ConnectivityAndInternetAccess.NetworkState latestState(final Context ctx) {
        ConnectivityAndInternetAccess.NetworkState state = latestState;
        return state != null
                ? state
                : ConnectivityAndInternetAccess.snapshotNetworkState(ctx);
    }

    public static synchronized void startNetworkObserver(final Context ctx) {
        if (networkObserver != null) {
            return;
        }

        networkObserver = ConnectivityAndInternetAccess.observeNetwork(
                ctx,
                new ConnectivityAndInternetAccess.NetworkStateCallback() {
                    @Override
                    public void onNetworkStateChanged(
                            ConnectivityAndInternetAccess.NetworkState state) {
                        latestState = state;
                        LogUtil.d(
                                TAG,
                                "Network state: connected=" + state.isConnected()
                                        + ", vpn=" + state.isVpnActive()
                                        + ", validated=" + state.isInternetValidated()
                                        + ", captivePortal=" + state.isCaptivePortalDetected());
                    }
                });
        latestState = networkObserver.getLatestState();
    }

    public static synchronized void stopNetworkObserver() {
        if (networkObserver == null) {
            return;
        }
        networkObserver.close();
        networkObserver = null;
        latestState = null;
    }
}
