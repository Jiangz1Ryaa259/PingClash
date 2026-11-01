// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.test;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

/** Separate UID's actual system VPN. Only loopback test proxies are used; Internet packets are discarded. */
public final class PeerVpnService extends VpnService {
    private ParcelFileDescriptor tun;
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&"stop".equals(intent.getAction())) {closeTun();stopSelf();return START_NOT_STICKY;}
        if(tun==null)tun=new Builder().setSession("Synthetic peer VPN").addAddress("172.28.0.1",30)
            .addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.28.0.2").establish();
        return START_NOT_STICKY;
    }
    private void closeTun() {
        if(tun!=null)try {tun.close();} catch(java.io.IOException ignored) { }
        tun=null;
    }
    @Override public void onRevoke() {closeTun();stopSelf();super.onRevoke();}
    @Override public void onDestroy() {closeTun();super.onDestroy();}
}
