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
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.SearchView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
	private SwipeRefreshLayout swipe;

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

		showSnack("Swipe down to refresh the list","Got it",0xFF9BF2C4,3000,(View)findViewById(R.id.paired_list).getParent(),null);

		swipe= findViewById(R.id.swiper);
		swipe.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener(){
			public void onRefresh(){
				// This used to call ActivityCompat.recreate() - the actual bug behind "kept
				// rotating and can't swipe again": SwipeRefreshLayout saves/restores its own
				// "is refreshing" state across a recreate (the same mechanism that keeps the
				// spinner up through a screen rotation mid-refresh), so the freshly recreated
				// Activity's new SwipeRefreshLayout came back already in the "refreshing" state
				// - and nothing anywhere ever called setRefreshing(false) on it, since recreate()
				// isn't an operation with a completion callback the way a real refresh is. Stuck
				// permanently spinning, and SwipeRefreshLayout ignores new pull gestures while
				// already "refreshing", so it could never be triggered again either. Repopulating
				// in place - what getPaired()/getActives()/startScan() already do, just without
				// tearing down and rebuilding the whole Activity - avoids all of that, and
				// stopRefreshing() (called from here if nothing is actually going to run, and
				// from AvailReceiver once a scan it did start genuinely finishes) is what
				// actually clears the spinner.
				refreshDeviceList();
				if(app.adapter.isDiscovering()){
					// A scan already in flight (e.g. from onResume()) will call stopRefreshing()
					// itself once it finishes - starting a second one here would register a
					// redundant AvailReceiver, which dedupes found devices only within itself,
					// so two receivers for the same scan would double up every device found.
					return;
				}
				startScan();
				if(!app.adapter.isDiscovering()){
					// startScan() didn't actually start anything (permission denied, the
					// "don't scan" preference, or startDiscovery() itself failing) - nothing
					// will ever call stopRefreshing() for this attempt, so do it now instead of
					// leaving the spinner stuck forever, which is the exact bug this replaces.
					stopRefreshing();
				}
			}
		});

		paired= findViewById(R.id.paired_list);
		paired.setLayoutManager(new LinearLayoutManager(this));

	    pairedAdapter= new EcclesAdapter(this,true);
	    pairedAdapter.setContextMenuRegistrar(this::registerForContextMenu);

		getSupportActionBar().setTitle("Select Device");

		paired.setAdapter(pairedAdapter);

		SpannableString sps= new SpannableString("Tap and hold on a device for options");
		sps.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,3,0);
		sps.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),8,12,0);
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

	/**
	 * Repopulates the device list's Active/Available/Paired sections against current reality,
	 * without kicking off a new Bluetooth discovery scan (unlike the pull-to-refresh handler,
	 * which calls this and then separately decides whether to also (re)scan) - cheap enough to
	 * run on a timer (see onResume()'s REFRESH_INTERVAL_MS) without the battery/radio cost a
	 * full scan every 10s would have.
	 * <p>
	 * NOT pojos.clear() - the "Active"/"Available"/"Paired" section headers (ac/av/pa) and the
	 * search-hint row are only ever created once, in EcclesAdapter's constructor, and nothing
	 * ever re-adds them afterward. Clearing the whole list wiped them out permanently (this was
	 * the "removed the actives, available, paired, and their descriptions from the list" bug
	 * from an earlier fix) and also broke add()'s section placement, which looks up
	 * al.indexOf(ac)/(av)/(pa) to decide where a device belongs - with the headers gone, every
	 * device landed at index 0 with no section at all. Removing only the actual device rows
	 * keeps every special row (headers, search hint) exactly where the constructor put it.
	 */
	private void refreshDeviceList(){
		// Only the rows this method itself regenerates (Paired / Active) are cleared. The
		// "Available" rows come from the Bluetooth scan (AvailReceiver) and are NOT rebuilt
		// here - removing them as well made every found device vanish at the next 10s tick or
		// pull-to-refresh, leaving just the header and its "N Available devices found" count.
		pairedAdapter.pojos.removeIf(p -> !p.isHeader && !p.isSearchHint
				&& ("Paired".equals(p.status) || "Active".equals(p.status)));
		getPaired(pairedAdapter);
		getActives();
		// A device that is now paired or connected shouldn't also be listed as Available.
		final java.util.HashSet<String> known= new java.util.HashSet<>();
		for(EcclesPojo p: pairedAdapter.pojos){
			if(("Paired".equals(p.status) || "Active".equals(p.status)) && p.tag != null) known.add(p.tag);
		}
		pairedAdapter.pojos.removeIf(p -> "Available".equals(p.status) && p.tag != null && known.contains(p.tag));
		getSupportActionBar().setSubtitle(pairedAdapter.pojos.size()-4+" Devices Found");
		pairedAdapter.notifyDataSetChanged();
	}

	/** Asks every paired device (and every connected one) whether it is running Bluechat, so the
	 *  rows can show a trustworthy green Online / red Offline. Connected devices are online by
	 *  definition, so they're never probed. */
	@Override
	public void onPeerProfileChanged(String address){
		if(pairedAdapter != null) refreshDeviceList();
	}

	public void probePaired(boolean force){
		EcclesApplication ea= (EcclesApplication) getApplication();
		if(ea == null || ea.adapter == null) return;
		try{
			for(BluetoothSocket cs: ea.connectionList.values()){
				starking.eccles.util.PresenceTracker.markOnline(cs.getRemoteDevice().getAddress());
			}
			for(BluetoothDevice d: ea.adapter.getBondedDevices()){
				if(ea.isConnected(d.getAddress())) continue;
				starking.eccles.util.PresenceTracker.probe(this,d,force);
			}
		} catch(SecurityException e){
			android.util.Log.w("SelectActivity","can't look up paired devices without Bluetooth permission",e);
		}
	}

	private static final long REFRESH_INTERVAL_MS= 10_000;
	private final android.os.Handler refreshHandler= new android.os.Handler(android.os.Looper.getMainLooper());
	private final Runnable refreshRunnable= new Runnable(){
		@Override
		public void run(){
			refreshDeviceList();
			probePaired(false);
			refreshHandler.postDelayed(this,REFRESH_INTERVAL_MS);
		}
	};

	/** Stops the pull-to-refresh spinner - called once a scan it kicked off genuinely finishes
	 *  (from {@link AvailReceiver}'s ACTION_DISCOVERY_FINISHED handling) or immediately if
	 *  nothing was actually going to run for this refresh attempt. */
	public void stopRefreshing(){
		if(swipe != null) swipe.setRefreshing(false);
	}

	public void getPaired(EcclesAdapter adapter){
		EcclesApplication ea= (EcclesApplication) getApplication();

		if(ea != null){
			BluetoothAdapter ad= ea.adapter;

			// Order: people you have chatted with or connected to before, then everything
			// else that can run Bluechat, then devices that can't (headphones etc.); online
			// ones first within each group, then alphabetical. EcclesAdapter#add inserts each
			// row directly under its section header, so rows are added worst-first.
			final java.util.Set<String> known= new java.util.HashSet<>(ea.chatBase != null ? ea.chatBase.addresses() : new java.util.HashSet<String>());
			java.util.ArrayList<BluetoothDevice> list= new java.util.ArrayList<>(ad.getBondedDevices());
			final java.util.HashMap<String,Integer> rank= new java.util.HashMap<>();
			for(BluetoothDevice d:list){
				boolean met= known.contains(d.getAddress()) || ClassicCompat.isRecepient(this,d.getAddress());
				boolean supported= ClassicCompat.queryDeviceType(d);
				boolean online= ea.isConnected(d.getAddress()) || starking.eccles.util.PresenceTracker.isOnline(d);
				rank.put(d.getAddress(),(met?0:4)+(supported||online?0:2)+(online?0:1));
			}
			java.util.Collections.sort(list,(a,b)->{
				int c= rank.get(a.getAddress())-rank.get(b.getAddress());
				if(c != 0) return c;
				return starking.eccles.util.PeerStore.displayName(a).compareToIgnoreCase(starking.eccles.util.PeerStore.displayName(b));
			});
			for(int i= list.size()-1;i>=0;i--){
				BluetoothDevice d= list.get(i);
				EcclesPojo p= new EcclesPojo(d,this,R.layout.pojo_view,EcclesIcon.resize(60,60,ClassicCompat.queryDeviceIcon(d.getAddress(),this),this),true,"Paired",ClassicCompat.queryDeviceType(d)?0:-1);
				adapter.add(adapter.pojos,p);
			}
		}
	}

	public void getActives(){
		if(!((EcclesApplication)getApplication()).connectionList.isEmpty()){
			pairedAdapter.setHeaderSubtitle(pairedAdapter.ac,((EcclesApplication)getApplication()).connectionList.size()+" Active devices found");
			for (BluetoothSocket s:app.connectionList.values()){
				BluetoothDevice device= s.getRemoteDevice();
				//if (pairedAdapter.findPojoWithTag(device.getAddress()) != null) continue;
				EcclesPojo pojo= new EcclesPojo(device,this,R.layout.pojo_view,EcclesIcon.resize(60,60,ClassicCompat.queryDeviceIcon(device.getAddress(),this),this),true,"Active",0);
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
		starking.eccles.util.PresenceTracker.setListener(() -> {
			if(pairedAdapter != null) pairedAdapter.notifyDataSetChanged();
		});
		probePaired(true);
		if(!requested){
			startScan();
			requested= true;
		}
		// Safety net for "Active"/connected status going stale while this screen is open and a
		// device disconnects or reconnects in the background - only ticks while actually
		// visible, same reasoning as MainFragment's equivalent loop.
		refreshHandler.removeCallbacks(refreshRunnable);
		refreshHandler.postDelayed(refreshRunnable,REFRESH_INTERVAL_MS);
		super.onResume();
	}

	public void startScan(){
		if(app.pref.getBoolean("dont_scan",false) && app.connectionList.size()>0)return;
		boolean locationOk = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
				|| requestPermission(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERM);
		if(locationOk){
			IntentFilter filter= new IntentFilter();
			filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
			filter.addAction(BluetoothDevice.ACTION_FOUND);
			filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

			AvailReceiver rec= new AvailReceiver(this);

			androidx.core.content.ContextCompat.registerReceiver(this,rec,filter, ContextCompat.RECEIVER_EXPORTED);
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
		refreshHandler.removeCallbacks(refreshRunnable);
		starking.eccles.util.PresenceTracker.setListener(null);
		starking.eccles.util.PresenceTracker.cancelAll();

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