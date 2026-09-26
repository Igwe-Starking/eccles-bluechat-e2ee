package starking.eccles.receivers;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;

public class BluetoothStateReceiver extends EcclesReceiver {

	@Override
	public void onReceive(Context con,Intent intent){
		EcclesApplication app= (EcclesApplication) con.getApplicationContext();
		// ACTION_STATE_CHANGED is a system-wide broadcast and fires regardless of whether the
		// app is currently in the foreground - app.currentActivity can legitimately be null
		// here (app backgrounded, or between one activity finishing and the next starting), so
		// calling into it unconditionally was a real crash risk (toggle Bluetooth while the
		// app is backgrounded -> NullPointerException).
		if(app.currentActivity == null) return;
		switch (intent.getAction()){
			case BluetoothAdapter.ACTION_STATE_CHANGED:
				switch (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR)){
					case BluetoothAdapter.STATE_OFF:
						app.currentActivity.state(false);
						break;
					case BluetoothAdapter.STATE_ON:
						app.currentActivity.state(true);
						break;
				}
		}
	}

}