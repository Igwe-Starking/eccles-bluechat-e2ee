package starking.eccles.bluechat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.SearchView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import starking.eccles.Surface.EcclesAdapter;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.bluechat.activities.CallActivity;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.bluechat.activities.VideoActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.receivers.AvailReceiver;
import starking.eccles.util.EcclesIcon;

public class SelectActivity extends EcclesActivity {

	private RecyclerView paired;
	public EcclesAdapter pairedAdapter;
	public int index;
	private boolean requested= false;

	protected void onCreate(Bundle bundle){

		setContentView(R.layout.select);
		super.onCreate(bundle);

		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		index= getIntent().getExtras()!=null ? getIntent().getExtras().getInt("index") : 0;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		switch (item.getItemId()){
			case android.R.id.home:
			super.onBackPressed();
			return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	public void onStart(){

		showSnack("Swipe down to refresh","Got it",Color.BLUE,3000,(View)findViewById(R.id.paired_list).getParent(),null);

		SwipeRefreshLayout swipe= findViewById(R.id.swiper);
		swipe.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener(){
			public void onRefresh(){
				ActivityCompat.recreate(SelectActivity.this);
			}
		});

		paired= findViewById(R.id.paired_list);
		paired.setLayoutManager(new LinearLayoutManager(this));

	    pairedAdapter= new EcclesAdapter(this,true);
	    pairedAdapter.setContextMenuRegistrar(this::registerForContextMenu);

		getSupportActionBar().setTitle("Select Device");

		paired.setAdapter(pairedAdapter);

		SpannableString sps= new SpannableString("Tap and hold on a device for options");
		sps.setSpan(new ForegroundColorSpan(Color.GREEN),0,3,0);
		sps.setSpan(new ForegroundColorSpan(Color.GREEN),8,12,0);
		sps.setSpan(new UnderlineSpan(),18,24,0);
		pairedAdapter.setSearchHint(sps);

		getPaired(pairedAdapter);

		getSupportActionBar().setSubtitle(pairedAdapter.pojos.size()-4+" Devices Found");

		actionSearch.setOnQueryTextListener(new SearchView.OnQueryTextListener(){
			public boolean onQueryTextChange(String cs){

				pairedAdapter.getFilter().filter(cs);

				return false;
			}

			public boolean onQueryTextSubmit(String cs){

				return false;
			}
		});

		super.onStart();
	}

	public void getPaired(EcclesAdapter adapter){
		EcclesApplication ea= (EcclesApplication) getApplication();

		if(ea != null){
			BluetoothAdapter ad= ea.adapter;

			for(BluetoothDevice d:ad.getBondedDevices()){
				EcclesPojo p= new EcclesPojo(d,this,R.layout.pojo_view,EcclesIcon.resize(60,60,R.drawable.user,this),true,"Paired",ClassicCompat.queryDeviceType(d)?0:-1);
				adapter.add(adapter.pojos,p);
			}
		}
	}

	public void getActives(){
		if(!((EcclesApplication)getApplication()).connectionList.isEmpty()){
			pairedAdapter.setHeaderSubtitle(pairedAdapter.ac,((EcclesApplication)getApplication()).connectionList.size()+" Active devices found");
			for (BluetoothSocket s:app.connectionList.values()){
				BluetoothDevice device= s.getRemoteDevice();

				EcclesPojo pojo= new EcclesPojo(device,this,R.layout.pojo_view,EcclesIcon.resize(60,60,R.drawable.user,this),true,"Active",0);
				pairedAdapter.add(pairedAdapter.pojos,pojo);
			}

			String abs= getSupportActionBar().getSubtitle().toString().split(" ")[0];
			if(abs==null || abs.length()<=0){
				getSupportActionBar().setSubtitle(((EcclesApplication)getApplication()).connectionList.size()+" Devices Found");
			} else {
				getSupportActionBar().setSubtitle(Integer.parseInt(abs)+((EcclesApplication)getApplication()).connectionList.size()+" Devices Found");
			}
		}
	}

	@Override
	public void onResume(){
		getActives();
		if(!requested){
			startScan();
			requested= true;
		}
		super.onResume();
	}

	public void startScan(){
		if(app.pref.getBoolean("dont_scan",false) && app.connectionList.size()>0)return;
		if(requestPermission(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERM)){
			IntentFilter filter= new IntentFilter();
			filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
			filter.addAction(BluetoothDevice.ACTION_FOUND);
			filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

			AvailReceiver rec= new AvailReceiver(this);

			androidx.core.content.ContextCompat.registerReceiver(this,rec,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
			if(!((EcclesApplication)getApplication()).adapter.isDiscovering()){
				if(!((EcclesApplication)getApplication()).adapter.startDiscovery()){
					pairedAdapter.setHeaderNoText(pairedAdapter.av,"Failed to initiate Scan");
					pairedAdapter.setHeaderNoTextVisible(pairedAdapter.av,true);
					unregisterReceiver(rec);
				}
			}
		}
	}

	public void onPause(){
		if(((EcclesApplication)getApplication()).adapter.isDiscovering()){
			((EcclesApplication)getApplication()).adapter.cancelDiscovery();
		}

		super.onPause();

	}

	@Override
	public void onConnected(BluetoothSocket socket){
		super.onConnected(socket);
		switch (index){
			case 1:
				Intent intent= new Intent(this,ChatActivity.class);
				intent.putExtra("device",socket.getRemoteDevice());
				ActivityCompat.startActivity(this,intent,null);
				break;
			case 2:
				Intent intent1= new Intent(this,CallActivity.class);
				intent1.putExtra("device",socket.getRemoteDevice());
				ActivityCompat.startActivity(this,intent1,null);
				break;
			case 3:
				if(checkPro(null)){
					Intent intent2= new Intent(this,VideoActivity.class);
					intent2.putExtra("device",socket.getRemoteDevice());
					ActivityCompat.startActivity(this,intent2,null);
				}
				break;
		}
	}

	public void processDisconnect(BluetoothDevice d){
		runOnUiThread(()->{
		try{
		EcclesPojo p= pairedAdapter.findPojoWithTag(d.getAddress());
		if(p != null && "Active".equals(p.status)){
			pairedAdapter.pojos.remove(p);
			pairedAdapter.notifyDataSetChanged();
		}
		} catch(Exception e){ android.util.Log.e("SelectActivity", "Suppressed exception", e); }
		});
	}

	@Override
	public void state(boolean on){
		if(on){
			ActivityCompat.recreate(this);
		}
		super.state(on);
	}
}