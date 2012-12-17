package com.rc.exynosmemfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		VirtualTerminal.run("chmod 600 /dev/exynos-mem", true);
		System.exit(0);
	}
}
