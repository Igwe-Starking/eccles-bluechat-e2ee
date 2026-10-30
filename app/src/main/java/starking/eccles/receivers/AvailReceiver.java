package starking.eccles.receivers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import java.util.ArrayList;
import starking.eccles.Surface.EcclesAdapter;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.bluechat.SelectActivity;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.util.EcclesIcon;

public class AvailReceiver extends EcclesReceiver {

	private final SelectActivity activity;
	private int found= 0;
	private ArrayList<String> list;
	private final ArrayList<BluetoothDevice> foundDevices= new ArrayList<>();

	public AvailReceiver(SelectActivity activity){
		this.activity= activity;
		list= new ArrayList();
	}

	@Override
	public void onReceive(Context con,Intent in){
		String action= in.getAction();
		EcclesAdapter adapter= activity.pairedAdapter;

		switch (action) {
			case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
				adapter.setHeaderNoText(adapter.av,"Scanning...");
				adapter.setHeaderNoTextColor(adapter.av,0xFF5E35B1);
				adapter.setHeaderNoTextVisible(adapter.av,true);
				break;
			case BluetoothDevice.ACTION_FOUND:
				found++;
				BluetoothDevice seen= in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if(seen != null){
					int lvl= ClassicCompat.queryDeviceRSSI(in);
					starking.eccles.util.PresenceTracker.recordSignal(seen.getAddress(),lvl);
					foundDevices.add(seen);
				}
				short rs= in.getShortExtra(BluetoothDevice.EXTRA_RSSI,Short.MIN_VALUE);
				EcclesPojo pojo= new EcclesPojo(in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE),activity,R.layout.pojo_view,EcclesIcon.resize(60,60,ClassicCompat.queryDeviceIcon(((BluetoothDevice) in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)).getAddress(),activity),activity),true,"Available",ClassicCompat.queryDeviceRSSI(in));
				if(!list.contains(pojo.tag)){
				list.add(pojo.tag);
				adapter.add(adapter.pojos,pojo);
				String an= activity.getSupportActionBar().getSubtitle().toString().split(" ")[0];
				activity.getSupportActionBar().setSubtitle(Integer.parseInt(an)+1+" Devices Found");
				adapter.setHeaderSubtitle(adapter.av,found+" Available devices found");
				}
				pojo= null;
				break;
			case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
				try{ activity.unregisterReceiver(this); } catch(IllegalArgumentException ignored){}
				activity.stopRefreshing();
				// The scan is over, so it is now safe to ask each nearby/paired device whether it
				// is actually running Bluechat.
				for(BluetoothDevice fd:foundDevices) starking.eccles.util.PresenceTracker.probe(activity,fd,true);
				activity.probePaired(true);
				list.clear();
				if(found == 0){
					adapter.setHeaderNoTextColor(adapter.av,0xFFD64550);
					adapter.setHeaderNoText(adapter.av,"No Available Devices Found");
					adapter.setHeaderNoTextVisible(adapter.av,true);
					return;
				}
				adapter.setHeaderNoTextVisible(adapter.av,false);
				break;
		}
	}
}
