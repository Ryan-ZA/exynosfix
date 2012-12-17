package com.rc.exynosmemfix;

import com.rc.exynosmemfix.VirtualTerminal.VTCommandResult;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

	TextView textVuln;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		textVuln = (TextView) findViewById(R.id.textVuln);
		Button buttonFix = (Button) findViewById(R.id.buttonFix);
		Button buttonUnFix = (Button) findViewById(R.id.buttonUnFix);

		buttonFix.setOnClickListener(fixClickListener);
		buttonUnFix.setOnClickListener(unFixClickListener);

		checkIfVuln();
	}

	private void checkIfVuln() {
		try {
			VTCommandResult r = VirtualTerminal.run("ls -l /dev/exynos-mem", true);
			if (r.stdout.contains("rw-rw-rw-")) {
				textVuln.setText("/dev/exynos-mem is vulnerable!");
				textVuln.setTextColor(Color.RED);
			} else {
				textVuln.setText("/dev/exynos-mem is NOT vulnerable!");
				textVuln.setTextColor(Color.GREEN);
			}
		} catch (Exception ex) {
			textVuln.setText("Unable to determine vulnerability status - is your device rooted?");
			textVuln.setTextColor(Color.YELLOW);
		}
	}

	OnClickListener fixClickListener = new OnClickListener() {

		@Override
		public void onClick(View v) {
			VTCommandResult r = VirtualTerminal.run("chmod 600 /dev/exynos-mem", true);
			if (!r.success()) {
				Toast.makeText(MainActivity.this, "Error: " + r.stderr, Toast.LENGTH_LONG).show();
			}
			checkIfVuln();
		}
	};

	OnClickListener unFixClickListener = new OnClickListener() {

		@Override
		public void onClick(View v) {
			VTCommandResult r = VirtualTerminal.run("chmod 666 /dev/exynos-mem", true);
			if (!r.success()) {
				Toast.makeText(MainActivity.this, "Error: " + r.stderr, Toast.LENGTH_LONG).show();
			}
			checkIfVuln();
		}
	};

}
