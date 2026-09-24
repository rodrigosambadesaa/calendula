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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public class ConnectivityAndInternetAccessTest {

    @Test
    public void plainInternetNetworkIsConnected() {
        Context context = mock(Context.class);
        ConnectivityManager manager = mock(ConnectivityManager.class);
        Network wifi = mock(Network.class);
        NetworkCapabilities wifiCapabilities = mock(NetworkCapabilities.class);

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager);
        when(manager.getActiveNetwork()).thenReturn(wifi);
        when(manager.getNetworkCapabilities(wifi)).thenReturn(wifiCapabilities);
        when(wifiCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true);
        when(wifiCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN)).thenReturn(false);

        assertTrue(ConnectivityAndInternetAccess.isConnected(context));
    }

    @Test
    public void adGuardVpnWithoutUnderlyingNetworkIsDisconnected() {
        Context context = mock(Context.class);
        ConnectivityManager manager = mock(ConnectivityManager.class);
        Network vpn = mock(Network.class);
        NetworkCapabilities vpnCapabilities = mock(NetworkCapabilities.class);

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager);
        when(manager.getActiveNetwork()).thenReturn(vpn);
        when(manager.getAllNetworks()).thenReturn(new Network[]{vpn});
        when(manager.getNetworkCapabilities(vpn)).thenReturn(vpnCapabilities);
        when(vpnCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true);
        when(vpnCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true);
        when(vpnCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(false);

        assertFalse(ConnectivityAndInternetAccess.isConnected(context));
        assertFalse(ConnectivityAndInternetAccess.hasUnderlyingNetwork(context));
    }

    @Test
    public void vpnWithUnderlyingWifiIsConnected() {
        Context context = mock(Context.class);
        ConnectivityManager manager = mock(ConnectivityManager.class);
        Network vpn = mock(Network.class);
        Network wifi = mock(Network.class);
        NetworkCapabilities vpnCapabilities = mock(NetworkCapabilities.class);
        NetworkCapabilities wifiCapabilities = mock(NetworkCapabilities.class);

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager);
        when(manager.getActiveNetwork()).thenReturn(vpn);
        when(manager.getAllNetworks()).thenReturn(new Network[]{vpn, wifi});
        when(manager.getNetworkCapabilities(vpn)).thenReturn(vpnCapabilities);
        when(manager.getNetworkCapabilities(wifi)).thenReturn(wifiCapabilities);

        when(vpnCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true);
        when(vpnCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true);
        when(vpnCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(false);

        when(wifiCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true);
        when(wifiCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(true);

        assertTrue(ConnectivityAndInternetAccess.isConnected(context));
        assertTrue(ConnectivityAndInternetAccess.hasUnderlyingNetwork(context));
    }

    @Test
    public void backendPreflightUsesTheRequestedBackendHost() throws Exception {
        Context context = mock(Context.class);
        ConnectivityManager manager = mock(ConnectivityManager.class);
        Network wifi = mock(Network.class);
        NetworkCapabilities wifiCapabilities = mock(NetworkCapabilities.class);
        ConnectivityAndInternetAccess.HostResolver resolver =
                mock(ConnectivityAndInternetAccess.HostResolver.class);

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager);
        when(manager.getActiveNetwork()).thenReturn(wifi);
        when(manager.getNetworkCapabilities(wifi)).thenReturn(wifiCapabilities);
        when(wifiCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true);
        when(wifiCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN)).thenReturn(false);
        when(resolver.resolves(eq("www.aemps.gob.es"), eq(wifi))).thenReturn(true);

        assertTrue(ConnectivityAndInternetAccess.canReachBackend(
                context,
                "https://www.aemps.gob.es/cima/dochtml/p/example",
                resolver));
        verify(resolver).resolves("www.aemps.gob.es", wifi);
    }

    @Test
    public void malformedBackendUrlIsRejectedWithoutDnsLookup() {
        ConnectivityAndInternetAccess.HostResolver resolver =
                mock(ConnectivityAndInternetAccess.HostResolver.class);

        assertFalse(ConnectivityAndInternetAccess.canReachBackend(
                null,
                "not a URL",
                resolver));
        verifyZeroInteractions(resolver);
    }

    @Test
    public void backendHostAcceptsOnlyHttpAndHttps() {
        assertEquals(
                "tec.citius.usc.es",
                ConnectivityAndInternetAccess.backendHost(
                        "http://tec.citius.usc.es/calendula/dbs/versions.json"));
        assertEquals(
                "www.accessdata.fda.gov",
                ConnectivityAndInternetAccess.backendHost(
                        "https://www.accessdata.fda.gov/spl/data/123/123.xml"));
        assertEquals(
                null,
                ConnectivityAndInternetAccess.backendHost("file:///tmp/local.html"));
    }
}
