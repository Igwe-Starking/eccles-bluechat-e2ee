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
				adapter.setHeaderNoTextColor(adapter.av,Color.BLUE);
				adapter.setHeaderNoTextVisible(adapter.av,true);
				break;
			case BluetoothDevice.ACTION_FOUND:
				found++;
				short rs= in.getShortExtra(BluetoothDevice.EXTRA_RSSI,Short.MIN_VALUE);
				EcclesPojo pojo= new EcclesPojo(in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE),activity,R.layout.pojo_view,EcclesIcon.resize(60,60,R.drawable.user,activity),true,"Available",ClassicCompat.queryDeviceRSSI(in));
				if(!list.contains(pojo.Message)){
				list.add(pojo.Message);
				adapter.add(adapter.pojos,pojo);
				String an= activity.getSupportActionBar().getSubtitle().toString().split(" ")[0];
				activity.getSupportActionBar().setSubtitle(Integer.parseInt(an)+1+" Devices Found");
				adapter.setHeaderSubtitle(adapter.av,found+" Available devices found");
				}
				pojo= null;
				break;
			case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
				activity.unregisterReceiver(this);
				list.clear();
				if(found == 0){
					adapter.setHeaderNoTextColor(adapter.av,Color.RED);
					adapter.setHeaderNoText(adapter.av,"No Available Devices Found");
					adapter.setHeaderNoTextVisible(adapter.av,true);
					return;
				}
				adapter.setHeaderNoTextVisible(adapter.av,false);
				break;
		}
	}
}
