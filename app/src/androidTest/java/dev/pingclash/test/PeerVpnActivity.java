// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.test;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

/** Synthetic separate-UID VPN consent/controller, packaged only in androidTest. */
public final class PeerVpnActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("stop".equals(getIntent().getStringExtra("command"))) {
            startService(new Intent(this,PeerVpnService.class).setAction("stop"));finish();return;
        }
        Intent permission=VpnService.prepare(this);
        if(permission==null)startPeer();else startActivityForResult(permission,1);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==1&&result==RESULT_OK)startPeer();else finish();
    }
    private void startPeer() {startService(new Intent(this,PeerVpnService.class));finish();}
}
